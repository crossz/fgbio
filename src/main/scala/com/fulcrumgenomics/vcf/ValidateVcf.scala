/*
 * The MIT License
 *
 * Copyright (c) 2020 Fulcrum Genomics
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package com.fulcrumgenomics.vcf

import com.fulcrumgenomics.FgBioDef.{PathToVcf, SafelyClosable}
import com.fulcrumgenomics.cmdline.{ClpGroups, FgBioTool}
import com.fulcrumgenomics.commons.util.{LazyLogging, LogLevel, Logger, SimpleCounter}
import com.fulcrumgenomics.sopt.{arg, clp}
import com.fulcrumgenomics.util.Io
import com.fulcrumgenomics.vcf.api.VcfHeader._
import com.fulcrumgenomics.vcf.api._
import com.fulcrumgenomics.vcf.validation.ValidationResult._
import com.fulcrumgenomics.vcf.validation.VariantValidator.{VariantFormatValidator, VariantInfoValidator}
import com.fulcrumgenomics.vcf.validation.{ValidationResult, VcfHeaderValidator, _}

@clp(group=ClpGroups.VcfOrBcf, description=
  """
    |Validates a VCF.
    |
    |# Header
    |
    |Errors if:
    |- Reserved INFO/FORMAT lines are have the proper type and count
    |- Duplicate INFO/FORMAT identifiers are found
    |- Duplicate contig names are found
    |
    |Warns if:
    |- No contig lines are present
    |
    |# Variants
    |
    |When checking variants, the header is updated to use the VCF-specification reserved INFO/FORMAT field definitions.
    |
    |Errors if:
    |- INFO/FORMAT field has the wrong type compared to the VCF header
    |- INFO/FORMAT field has the wrong count compared to the VCF header
    |- INFO/FORMAT field is not defined in the VCF header
    |
    |# Future work
    |
    |Validate:
    |- values for specific fields (ex. CIGAR)
    |- values across variants (ex. phase set, spanning alleles)
    |- across fields (ex. allele depth vs allele frequency, allele depth vs forward/reverse allele dpeth)
    |- additional contig lines (ALT/PEDIGREE)
    |- structural variant and complex re-arrangements
    |- gVCFs explicitly
  """
)
class ValidateVcf
(@arg(flag='i', doc="Input VCF file.")  val input: PathToVcf,
 @arg(flag='l', doc="The level of issues to emit.") val level: LogLevel = LogLevel.Info,
 @arg(flag='e', doc="Emit only the first N messages.") val emitFirstN: Option[Int] = None,
 @arg(flag='n', doc="Process only the first N records.") val examineFirstN: Option[Int] = None,
 @arg(flag='k', doc="Allow a mismatch between the actual INFO/FORMAT type in the VCF record and the type defined in the VCF header.")
  val allowTypeMismatch: Boolean = false,
 @arg(flag='x', doc="Allow INFO/FORMAT fields in the VCF record that are not present in the VCF header.")
  val allowExtraFields: Boolean = false,
 @arg(flag='s', doc="Summarize counts of messages.") val summarizeMessages: Boolean = false
) extends FgBioTool with LazyLogging {
  Io.assertReadable(input)

  private val levelCounter: SimpleCounter[LogLevel] = new SimpleCounter[LogLevel]()
  private val messageCounter: SimpleCounter[String] = new SimpleCounter[String]()

  private implicit class ProcessValidationResults(results: Seq[ValidationResult]) {
    def process(): Unit = results.foreach { result =>
      val newResult = new ProcessValidationResult(result=result)
      newResult.process()
    }
  }

  private implicit class ProcessValidationResult(result: ValidationResult) {
    def process(): Unit = {
      levelCounter.count(result.level)
      if (summarizeMessages) messageCounter.count(result.message)
      if (emitFirstN.forall(levelCounter.total <= _)) result.emit(logger)
      if (emitFirstN.contains(levelCounter.total)) {
        logger.info(s"Reached ${levelCounter.total} messages, suppressing...")
      }
    }
  }

  override def execute(): Unit = {
    Logger.level = this.level

    // Validate the VCF header
    val reader                = VcfSource(path=input, allowKindMismatch=allowTypeMismatch, allowExtraFields=allowExtraFields)
    val headerValidators      = VcfHeaderValidator.Validators
    val headerEntryValidators = VcfHeaderEntryValidator.ReservedVcfHeaderEntryValidators
    headerValidators.foreach { validator => validator.validate(header=reader.header).process() }
    reader.header.entries.foreach { entry =>
      headerEntryValidators.foreach { validator =>
        validator.validate(entry=entry).process()
      }
    }

    val infoKeys   = reader.header.info.keySet
    val formatKeys = reader.header.format.keySet

    // Validate the variants
    val variantInfoFormatValidators = {
      val reservedInfoIds = ReservedVcfInfoHeaders.map(_.id).toSet
      VariantValidator.VariantInfoValidators ++ reader
        .header
        .infos
        .filterNot(info => reservedInfoIds.contains(info.id))
        .map(info => VariantInfoValidator(info=info))
    }

    val variantFormatFormatValidators = {
      val reservedFormatIds = ReservedVcfFormatHeaders.map(_.id).toSet
      VariantValidator.VariantFormatValidators ++ reader
        .header
        .formats
        .filterNot(format => reservedFormatIds.contains(format.id))
        .map(format => VariantFormatValidator(format=format))
    }
    val iter = examineFirstN match {
      case None    => reader.view
      case Some(n) => reader.take(n)
    }
    iter.foreach { variant: Variant =>
      // INFO
      variantInfoFormatValidators.foreach { validator =>
        validator.validate(variant=variant).process()
      }
      // check for INFO values not described in the header
      variant.attrs.keys.filterNot(infoKeys.contains).foreach { id =>
        error(s"INFO.$id found in record but missing in header", variant=variant)
      }

      // FORMAT
      variant.genotypes.foreach { case (_, genotype) =>
        variantFormatFormatValidators.foreach { validator =>
          validator.validate(variant=variant, genotype=genotype).process()
        }
      }
      // check for FORMAT values not described in the header
      variant.genotypes.flatMap { case (_, genotype) => genotype.attrs.keySet }.toSeq.distinct.filterNot(formatKeys.contains).foreach { id: String =>
        error(s"FORMAT.$id found in record but missing in header", variant=variant).process()
      }
    }

    reader.safelyClose

    if (summarizeMessages) {
      messageCounter.foreach { case (message, count) =>
        val extra = if (count > 1) "s" else ""
        logger.info(f"Found $count%,d message$extra: $message")
      }
    }

    levelCounter.foreach { case (level, count) =>
      val extra = if (count > 1) "s" else ""
      logger.info(f"Found $count%,d at $level$extra")
    }
  }
}
