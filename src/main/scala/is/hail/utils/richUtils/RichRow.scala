package is.hail.utils.richUtils

import is.hail.variant.{GenotypeStream, Variant}
import org.apache.spark.sql.Row

import scala.collection.mutable

class RichRow(r: Row) {

  def update(i: Int, a: Any): Row = {
    val arr = Array.tabulate(r.size)(r.get)
    arr(i) = a
    Row.fromSeq(arr)
  }

  def getOrIfNull[T](i: Int, t: T): T = {
    if (r.isNullAt(i))
      t
    else
      r.getAs[T](i)
  }

  def getOption(i: Int): Option[Any] = {
    Option(r.get(i))
  }

  def getAsOption[T](i: Int): Option[T] = {
    if (r.isNullAt(i))
      None
    else
      Some(r.getAs[T](i))
  }

  def delete(i: Int): Row = {
    val ab = mutable.ArrayBuilder.make[Any]
    (0 until i).foreach(ab += r.get(_))
    (i + 1 until r.size).foreach(ab += r.get(_))
    val result = ab.result()
    if (result.isEmpty)
      null
    else
      Row.fromSeq(result)
  }

  def append(a: Any): Row = {
    val ab = mutable.ArrayBuilder.make[Any]
    ab ++= r.toSeq
    ab += a
    Row.fromSeq(ab.result())
  }

  def insertBefore(i: Int, a: Any): Row = {
    val ab = mutable.ArrayBuilder.make[Any]
    (0 until i).foreach(ab += r.get(_))
    ab += a
    (i until r.size).foreach(ab += r.get(_))
    Row.fromSeq(ab.result())
  }

  def getVariant(i: Int) = Variant.fromRow(r.getAs[Row](i))

  def getGenotypeStream(v: Variant, i: Int, isLinearScale: Boolean) = GenotypeStream.fromRow(v.nAlleles, isLinearScale, r.getAs[Row](i))
}
