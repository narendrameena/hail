package is.hail.annotations

import is.hail.expr._
import is.hail.utils.Interval
import is.hail.variant._
import org.apache.spark.sql.Row

import scala.collection.mutable

object Annotation {

  final val SAMPLE_HEAD = "sa"

  final val VARIANT_HEAD = "va"

  final val GLOBAL_HEAD = "global"

  final val GENOTYPE_HEAD = "g"

  def empty: Annotation = null

  def emptyIndexedSeq(n: Int): IndexedSeq[Annotation] = Array.fill[Annotation](n)(Annotation.empty)

  def printAnnotation(a: Any, nSpace: Int = 0): String = {
    val spaces = " " * nSpace
    a match {
      case null => "Null"
      case r: Row =>
        "Struct:\n" +
          r.toSeq.zipWithIndex.map { case (elem, index) =>
            s"""$spaces[$index] ${printAnnotation(elem, nSpace + 4)}"""
          }
            .mkString("\n")
      case a => a.toString + ": " + a.getClass.getSimpleName
    }
  }

  def expandType(t: Type): Type = t match {
    case TVariant => Variant.expandedType
    case TGenotype => Genotype.expandedType
    case TLocus => Locus.expandedType
    case TArray(elementType) =>
      TArray(expandType(elementType))
    case TStruct(fields) =>
      TStruct(fields.map { f => f.copy(typ = expandType(f.typ)) })
    case TSet(elementType) =>
      TArray(expandType(elementType))
    case TDict(keyType, valueType) =>
      TArray(TStruct(
        "key" -> expandType(keyType),
        "value" -> expandType(valueType)))
    case TAltAllele => AltAllele.expandedType
    case TInterval =>
      TStruct(
        "start" -> Locus.expandedType,
        "end" -> Locus.expandedType)
    case _ => t
  }

  def expandAnnotation(a: Annotation, t: Type): Annotation =
    if (a == null)
      null
    else
      t match {
        case TVariant => a.asInstanceOf[Variant].toRow
        case TGenotype => a.asInstanceOf[Genotype].toRow
        case TLocus => a.asInstanceOf[Locus].toRow

        case TArray(elementType) =>
          a.asInstanceOf[IndexedSeq[_]].map(expandAnnotation(_, elementType))
        case TStruct(fields) =>
          Row.fromSeq((a.asInstanceOf[Row].toSeq, fields).zipped.map { case (ai, f) =>
            expandAnnotation(ai, f.typ)
          })

        case TSet(elementType) =>
          (a.asInstanceOf[Set[_]]
            .toArray[Any] : IndexedSeq[_])
            .map(expandAnnotation(_, elementType))

        case TDict(keyType, valueType) =>
          (a.asInstanceOf[Map[String, _]]

            .toArray[(Any, Any)]: IndexedSeq[(Any, Any)])
            .map { case (k, v) => Annotation(expandAnnotation(k, keyType), expandAnnotation(v, valueType)) }

        case TAltAllele => a.asInstanceOf[AltAllele].toRow

        case TInterval =>
          val i = a.asInstanceOf[Interval[Locus]]
          Annotation(i.start.toRow,
            i.end.toRow)

        // including TChar, TSample
        case _ => a
      }

  def flattenType(t: Type): Type = t match {
    case t: TStruct =>
      val flatFields = t.fields.flatMap { f =>
        flattenType(f.typ) match {
          case t2: TStruct =>
            t2.fields.map { f2 => (f.name + "." + f2.name, f2.typ) }

          case _ => Seq(f.name -> f.typ)
        }
      }

      TStruct(flatFields: _*)

    case _ => t
  }

  def flattenAnnotation(a: Annotation, t: Type): Annotation = t match {
    case t: TStruct =>
      val s =
        if (a == null)
          Seq.fill(t.fields.length)(null)
        else
          a.asInstanceOf[Row].toSeq

      val fs = (s, t.fields).zipped.flatMap { case (ai, f) =>
        f.typ match {
          case t: TStruct =>
            flattenAnnotation(ai, f.typ).asInstanceOf[Row].toSeq

          case _ =>
            Seq(ai)
        }
      }
      Row.fromSeq(fs)

    case _ => a
  }

  def apply(args: Any*): Annotation = Row.fromSeq(args)

  def fromSeq(values: Seq[Any]): Annotation = Row.fromSeq(values)

  def buildInserter(code: String, t: Type, ec: EvalContext, expectedHead: String): (Type, Inserter) = {
    val (paths, types, f) = Parser.parseAnnotationExprs(code, ec, Some(expectedHead))

    val inserterBuilder = mutable.ArrayBuilder.make[Inserter]
    val finalType = (paths, types).zipped.foldLeft(t) { case (t, (ids, signature)) =>
      val (s, i) = t.insert(signature, ids)
      inserterBuilder += i
      s
    }

    val inserters = inserterBuilder.result()

    val insF = (left: Annotation, right: Annotation) => {
      ec.setAll(left, right)

      var newAnnotation = left
      val queries = f()
      queries.indices.foreach { i =>
        newAnnotation = inserters(i)(newAnnotation, queries(i))
      }
      newAnnotation
    }

    (finalType, insF)
  }
}

