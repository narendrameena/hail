package is.hail.vds

import is.hail.SparkSuite
import org.testng.annotations.Test

class GenotypeKeyTableSuite extends SparkSuite {
  @Test def test() {
    val vds = hc.importVCF("src/test/resources/sample.vcf")

    vds.genotypeKT().typeCheck()

    assert(vds.genotypeKT().rdd.count() == vds.countVariants() * vds.nSamples)
  }
}
