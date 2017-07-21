package is.hail.variant.vsm

import is.hail.SparkSuite
import is.hail.check.{Gen, Prop}
import is.hail.variant.{VSMSubgen, VariantDataset, VariantSampleMatrix}
import org.testng.annotations.Test

class PartitioningSuite extends SparkSuite {

  def compare(vds1: VariantDataset, vds2: VariantDataset): Boolean = {
    val s1 = vds1.variantsAndAnnotations
      .mapPartitionsWithIndex { case (i, it) => it.zipWithIndex.map(x => (i, x)) }
      .collect()
      .toSet
    val s2 = vds2.variantsAndAnnotations
      .mapPartitionsWithIndex { case (i, it) => it.zipWithIndex.map(x => (i, x)) }
      .collect()
      .toSet
    s1 == s2
  }

  @Test def testParquetWriteRead() {
    Prop.forAll(VariantSampleMatrix.gen(hc, VSMSubgen.random), Gen.choose(1, 10)) { case (vds, nPar) =>

      val out = tmpDir.createTempFile("out", ".vds")
      val out2 = tmpDir.createTempFile("out", ".vds")

      val orig = vds.coalesce(nPar)
        orig.write(out)
      val problem = hc.readVDS(out)

      hc.readVDS(out).annotateVariantsExpr("va = va").countVariants()

      // need to do 2 writes to ensure that the RDD is ordered
      hc.readVDS(out)
        .write(out2)

      val readback = hc.readVDS(out2)

      compare(orig, readback)
    }.check()
  }
}
