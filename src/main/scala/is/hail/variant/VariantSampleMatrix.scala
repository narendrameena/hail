package is.hail.variant

import java.io.FileNotFoundException
import java.nio.ByteBuffer

import is.hail.annotations._
import is.hail.check.Gen
import is.hail.expr._
import is.hail.io._
import is.hail.keytable.KeyTable
import is.hail.methods.Aggregators.SampleFunctions
import is.hail.methods.{Aggregators, DuplicateReport, Filter, VEP}
import is.hail.sparkextras._
import is.hail.utils._
import is.hail.variant.Variant.orderedKey
import is.hail.{HailContext, utils}
import org.apache.hadoop
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.{Partitioner, SparkContext, SparkEnv}
import org.json4s._
import org.json4s.jackson.{JsonMethods, Serialization}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.{implicitConversions, existentials}
import scala.reflect.{classTag, ClassTag}

object VariantSampleMatrix {
  final val fileVersion: Int = 4

  def read(hc: HailContext, dirname: String,
    dropSamples: Boolean = false, dropVariants: Boolean = false): VariantSampleMatrix[_] = {

    val sqlContext = hc.sqlContext
    val sc = hc.sc
    val hConf = sc.hadoopConfiguration

    val (fileMetadata, parquetGenotypes) = readFileMetadata(hConf, dirname)

    val metadata = fileMetadata.metadata

    val vaSignature = metadata.vaSignature
    val vaRequiresConversion = SparkAnnotationImpex.requiresConversion(vaSignature)

    val genotypeSignature = metadata.genotypeSignature
    val gRequiresConversion = SparkAnnotationImpex.requiresConversion(genotypeSignature)
    val isGenericGenotype = metadata.isGenericGenotype
    val isLinearScale = metadata.isLinearScale

    if (!parquetGenotypes && !isGenericGenotype) {
      return new VariantDataset(hc,
        metadata,
        MatrixRead(hc, dirname, metadata, dropSamples = dropSamples, dropVariants = dropVariants,
          MatrixType(metadata)))
    }

    val parquetFile = dirname + "/rdd.parquet"

    val partitioner: OrderedPartitioner[Locus, Variant] =
      try {
        val jv = hConf.readFile(dirname + "/partitioner.json.gz")(JsonMethods.parse(_))
        jv.fromJSON[OrderedPartitioner[Locus, Variant]]
      } catch {
        case _: FileNotFoundException =>
          fatal("missing partitioner.json.gz when loading VDS, create with HailContext.write_partitioning.")
      }

    val localValue =
      if (dropSamples)
        fileMetadata.localValue.dropSamples()
      else
        fileMetadata.localValue

    type ImportGT = ((Variant, Row) => Iterable[T], ClassTag[T]) forSome {type T}
    val genotypeImporter =
      if (isGenericGenotype) {
        ((v: Variant, r: Row) => r.getSeq[Any](2).lazyMap(
          if (gRequiresConversion)
            (g: Any) => SparkAnnotationImpex.importAnnotation(g, genotypeSignature)
          else
            (g: Any) => g: Annotation), classTag[Annotation]): ImportGT
      } else {
        (if (parquetGenotypes)
          (v: Variant, r: Row) => r.getSeq[Row](2).lazyMap(new RowGenotype(_))
        else
          (v: Variant, r: Row) => r.getGenotypeStream(v, 2, isLinearScale), classTag[Genotype]): ImportGT
      }

    // Scala is bad with existentials, so we use a universally quantified function instead
    def doRead[T](genotypeImporter: ((Variant, Row) => Iterable[T], ClassTag[T])): VariantSampleMatrix[T] = {
      val (importGenotypes, tct) = genotypeImporter

      def importVa(r: Row): Annotation =
        if (vaRequiresConversion) SparkAnnotationImpex.importAnnotation(r.get(1), vaSignature) else r.get(1)

      def importRow(r: Row) = {
        val v = r.getVariant(0)
        (v, (importVa(r),
          if (dropSamples)
            Iterable.empty[T]
          else
            importGenotypes(v, r)))
      }

      val orderedRDD =
        if (dropVariants)
          OrderedRDD.empty[Locus, Variant, (Annotation, Iterable[T])](sc)
        else {
          val columns = someIf(dropSamples, Array("variant", "annotations"))
          OrderedRDD[Locus, Variant, (Annotation, Iterable[T])](sqlContext.readParquetSorted(parquetFile, columns).map(importRow), partitioner)
        }

      new VariantSampleMatrix[T](hc, metadata, localValue, orderedRDD)(tct)
    }

    doRead(genotypeImporter)
  }

  def readFileMetadata(hConf: hadoop.conf.Configuration, dirname: String,
    requireParquetSuccess: Boolean = true): (VSMFileMetadata, Boolean) = {
    if (!dirname.endsWith(".vds") && !dirname.endsWith(".vds/"))
      fatal(s"input path ending in `.vds' required, found `$dirname'")

    if (!hConf.exists(dirname))
      fatal(s"no VDS found at `$dirname'")

    val metadataFile = dirname + "/metadata.json.gz"
    val pqtSuccess = dirname + "/rdd.parquet/_SUCCESS"

    if (!hConf.exists(pqtSuccess) && requireParquetSuccess)
      fatal(
        s"""corrupt VDS: no parquet success indicator
           |  Unexpected shutdown occurred during `write'
           |  Recreate VDS.""".stripMargin)

    if (!hConf.exists(metadataFile))
      fatal(
        s"""corrupt or outdated VDS: invalid metadata
           |  No `metadata.json.gz' file found in VDS directory
           |  Recreate VDS with current version of Hail.""".stripMargin)

    val json = try {
      hConf.readFile(metadataFile)(
        in => JsonMethods.parse(in))
    } catch {
      case e: Throwable => fatal(
        s"""
           |corrupt VDS: invalid metadata file.
           |  Recreate VDS with current version of Hail.
           |  caught exception: ${ expandException(e, logMessage = true) }
         """.stripMargin)
    }

    val fields = json match {
      case jo: JObject => jo.obj.toMap
      case _ =>
        fatal(
          s"""corrupt VDS: invalid metadata value
             |  Recreate VDS with current version of Hail.""".stripMargin)
    }

    def getAndCastJSON[T <: JValue](fname: String)(implicit tct: ClassTag[T]): T =
      fields.get(fname) match {
        case Some(t: T) => t
        case Some(other) =>
          fatal(
            s"""corrupt VDS: invalid metadata
               |  Expected `${ tct.runtimeClass.getName }' in field `$fname', but got `${ other.getClass.getName }'
               |  Recreate VDS with current version of Hail.""".stripMargin)
        case None =>
          fatal(
            s"""corrupt VDS: invalid metadata
               |  Missing field `$fname'
               |  Recreate VDS with current version of Hail.""".stripMargin)
      }

    val version = getAndCastJSON[JInt]("version").num

    if (version != VariantSampleMatrix.fileVersion)
      fatal(
        s"""Invalid VDS: old version [$version]
           |  Recreate VDS with current version of Hail.
         """.stripMargin)

    val wasSplit = getAndCastJSON[JBool]("split").value
    val isDosage = (fields.get("isDosage"), fields.get("isLinearScale")) match {
      case (Some(t: JBool), None) => t.value
      case (None, Some(t: JBool)) => t.value
      case (Some(other), None) => fatal(
        s"""corrupt VDS: invalid metadata
           |  Expected `JBool' in field `isDosage', but got `${ other.getClass.getName }'
           |  Recreate VDS with current version of Hail.""".stripMargin)
      case (None, Some(other)) => fatal(
        s"""corrupt VDS: invalid metadata
           |  Expected `JBool' in field `isLinearScale', but got `${ other.getClass.getName }'
           |  Recreate VDS with current version of Hail.""".stripMargin)
      case (Some(l), Some(r)) => fatal(
        s"""corrupt VDS: invalid metadata
           |  Cannot have fields for both `isDosage` and `isLinearScale'.'
           |  Recreate VDS with current version of Hail.""".stripMargin)
      case (None, None) => false
    }

    val parquetGenotypes = fields.get("parquetGenotypes") match {
      case Some(t: JBool) => t.value
      case Some(other) => fatal(
        s"""corrupt VDS: invalid metadata
           |  Expected `JBool' in field `parquetGenotypes', but got `${ other.getClass.getName }'
           |  Recreate VDS with current version of Hail.""".stripMargin)
      case _ => false
    }

    val sSignature = fields.get("sample_schema") match {
      case Some(t: JString) => Parser.parseType(t.s)
      case Some(other) => fatal(
        s"""corrupt VDS: invalid metadata
           |  Expected `JString' in field `sample_schema', but got `${ other.getClass.getName }'
           |  Recreate VDS with current version of Hail.""".stripMargin)
      case _ => TString
    }

    val vSignature = fields.get("variant_schema") match {
      case Some(t: JString) => Parser.parseType(t.s)
      case Some(other) => fatal(
        s"""corrupt VDS: invalid metadata
           |  Expected `JString' in field `variant_schema', but got `${ other.getClass.getName }'
           |  Recreate VDS with current version of Hail.""".stripMargin)
      case _ => TVariant
    }

    val genotypeSignature = fields.get("genotype_schema") match {
      case Some(t: JString) => Parser.parseType(t.s)
      case Some(other) => fatal(
        s"""corrupt VDS: invalid metadata
           |  Expected `JString' in field `genotype_schema', but got `${ other.getClass.getName }'
           |  Recreate VDS with current version of Hail.""".stripMargin)
      case _ => TGenotype
    }

    val isGenericGenotype = fields.get("isGenericGenotype") match {
      case Some(t: JBool) => t.value
      case Some(other) => fatal(
        s"""corrupt VDS: invalid metadata
           |  Expected `JBool' in field `isGenericGenotype', but got `${ other.getClass.getName }'
           |  Recreate VDS with current version of Hail.""".stripMargin)
      case _ => false
    }

    assert(!isGenericGenotype || !parquetGenotypes)

    val saSignature = Parser.parseType(getAndCastJSON[JString]("sample_annotation_schema").s)
    val vaSignature = Parser.parseType(getAndCastJSON[JString]("variant_annotation_schema").s)
    val globalSignature = Parser.parseType(getAndCastJSON[JString]("global_annotation_schema").s)

    val sampleInfoSchema = TStruct(("id", sSignature), ("annotation", saSignature))
    val sampleInfo = getAndCastJSON[JArray]("sample_annotations")
      .arr
      .map {
        case JObject(List(("id", JString(id)), ("annotation", jv: JValue))) =>
          (id, JSONAnnotationImpex.importAnnotation(jv, saSignature, "sample_annotations"))
        case other => fatal(
          s"""corrupt VDS: invalid metadata
             |  Invalid sample annotation metadata
             |  Recreate VDS with current version of Hail.""".stripMargin)
      }
      .toArray

    val globalAnnotation = JSONAnnotationImpex.importAnnotation(getAndCastJSON[JValue]("global_annotation"),
      globalSignature, "global")

    val ids = sampleInfo.map(_._1)
    val annotations = sampleInfo.map(_._2)

    (VSMFileMetadata(VSMMetadata(sSignature, saSignature, vSignature, vaSignature, globalSignature, genotypeSignature, wasSplit, isDosage, isGenericGenotype),
      VSMLocalValue(globalAnnotation, ids, annotations)), parquetGenotypes)
  }

  def writePartitioning(sqlContext: SQLContext, dirname: String): Unit = {
    val sc = sqlContext.sparkContext
    val hConf = sc.hadoopConfiguration

    if (hConf.exists(dirname + "/partitioner.json.gz")) {
      warn("write partitioning: partitioner.json.gz already exists, nothing to do")
      return
    }

    val parquetFile = dirname + "/rdd.parquet"

    val fastKeys = sqlContext.readParquetSorted(parquetFile, Some(Array("variant")))
      .map(_.getVariant(0))
    val kvRDD = fastKeys.map(k => (k, ()))

    val ordered = kvRDD.toOrderedRDD(fastKeys)

    hConf.writeTextFile(dirname + "/partitioner.json.gz") { out =>
      Serialization.write(ordered.orderedPartitioner.toJSON, out)
    }
  }

  def gen[T](hc: HailContext,
    gen: VSMSubgen[T])(implicit tct: ClassTag[T]): Gen[VariantSampleMatrix[T]] =
    gen.gen(hc)

  def genGeneric(hc: HailContext): Gen[VariantSampleMatrix[Annotation]] =
    for (tSig <- Type.genArb.resize(3);
      vsm <- VSMSubgen[Annotation](
        sampleIdGen = Gen.distinctBuildableOf[Array, String](Gen.identifier),
        saSigGen = Type.genArb,
        vaSigGen = Type.genArb,
        globalSigGen = Type.genArb,
        tSig = tSig,
        saGen = (t: Type) => t.genValue,
        vaGen = (t: Type) => t.genValue,
        globalGen = (t: Type) => t.genValue,
        vGen = Variant.gen,
        tGen = (v: Variant) => tSig.genValue.resize(20),
        isGenericGenotype = true).gen(hc)
    ) yield vsm
}

case class VSMSubgen[T](
  sampleIdGen: Gen[Array[String]],
  saSigGen: Gen[Type],
  vaSigGen: Gen[Type],
  globalSigGen: Gen[Type],
  tSig: Type,
  saGen: (Type) => Gen[Annotation],
  vaGen: (Type) => Gen[Annotation],
  globalGen: (Type) => Gen[Annotation],
  vGen: Gen[Variant],
  tGen: (Variant) => Gen[T],
  isLinearScale: Boolean = false,
  wasSplit: Boolean = false,
  isGenericGenotype: Boolean = false) {

  def gen(hc: HailContext)(implicit tct: ClassTag[T]): Gen[VariantSampleMatrix[T]] =
    for (size <- Gen.size;
      subsizes <- Gen.partitionSize(5).resize(size / 10);
      vaSig <- vaSigGen.resize(subsizes(0));
      saSig <- saSigGen.resize(subsizes(1));
      globalSig <- globalSigGen.resize(subsizes(2));
      global <- globalGen(globalSig).resize(subsizes(3));
      nPartitions <- Gen.choose(1, 10);

      (l, w) <- Gen.squareOfAreaAtMostSize.resize((size / 10) * 9);

      sampleIds <- sampleIdGen.resize(w);
      nSamples = sampleIds.length;
      saValues <- Gen.buildableOfN[Array, Annotation](nSamples, saGen(saSig)).resize(subsizes(4));
      rows <- Gen.distinctBuildableOf[Array, (Variant, (Annotation, Iterable[T]))](
        for (subsubsizes <- Gen.partitionSize(3);
          v <- vGen.resize(subsubsizes(0));
          va <- vaGen(vaSig).resize(subsubsizes(1));
          ts <- Gen.buildableOfN[Array, T](nSamples, tGen(v)).resize(subsubsizes(2)))
          yield (v, (va, ts: Iterable[T]))).resize(l))
      yield {
        new VariantSampleMatrix[T](hc,
          VSMMetadata(TString, saSig, TVariant, vaSig, globalSig, tSig, wasSplit = wasSplit, isLinearScale = isLinearScale, isGenericGenotype = isGenericGenotype),
          VSMLocalValue(global, sampleIds, saValues),
          hc.sc.parallelize(rows, nPartitions).toOrderedRDD)
      }
}

object VSMSubgen {
  val random = VSMSubgen[Genotype](
    sampleIdGen = Gen.distinctBuildableOf[Array, String](Gen.identifier),
    saSigGen = Type.genArb,
    vaSigGen = Type.genArb,
    globalSigGen = Type.genArb,
    tSig = TGenotype,
    saGen = (t: Type) => t.genValue,
    vaGen = (t: Type) => t.genValue,
    globalGen = (t: Type) => t.genValue,
    vGen = Variant.gen,
    tGen = Genotype.genExtreme)

  val plinkSafeBiallelic = random.copy(
    sampleIdGen = Gen.distinctBuildableOf[Array, String](Gen.plinkSafeIdentifier),
    vGen = VariantSubgen.plinkCompatible.copy(nAllelesGen = Gen.const(2)).gen,
    wasSplit = true)

  val realistic = random.copy(
    tGen = Genotype.genRealistic)

  val dosageGenotype = random.copy(
    tGen = Genotype.genDosageGenotype, isLinearScale = true)
}

class VariantSampleMatrix[T](val hc: HailContext, val metadata: VSMMetadata,
  val ast: MatrixAST[T])(implicit val tct: ClassTag[T]) extends JoinAnnotator {

  def this(hc: HailContext,
    metadata: VSMMetadata,
    localValue: VSMLocalValue,
    rdd: OrderedRDD[Locus, Variant, (Annotation, Iterable[T])])(implicit tct: ClassTag[T]) =
    this(hc, metadata,
      MatrixLiteral(
        MatrixType(metadata),
        MatrixValue(localValue, rdd)))

  def this(hc: HailContext, fileMetadata: VSMFileMetadata,
    rdd: OrderedRDD[Locus, Variant, (Annotation, Iterable[T])])(implicit tct: ClassTag[T]) =
    this(hc, fileMetadata.metadata, fileMetadata.localValue, rdd)

  def requireSampleTString(method: String) {
    if (sSignature != TString)
      fatal(s"in $method: column key (sample) schema must be String, but found: $sSignature")
  }

  val VSMMetadata(sSignature, saSignature, vSignature, vaSignature, globalSignature, genotypeSignature, wasSplit, isLinearScale, isGenericGenotype) = metadata

  lazy val value: MatrixValue[T] = {
    val opt = MatrixAST.optimize(ast)
    opt.execute(hc)
  }

  lazy val MatrixValue(VSMLocalValue(globalAnnotation, sampleIds, sampleAnnotations), rdd) = value

  def stringSampleIds: IndexedSeq[String] = {
    assert(sSignature == TString)
    sampleIds.map(_.asInstanceOf[String])
  }

  def stringSampleIdSet: Set[String] = stringSampleIds.toSet

  type RowT = (Variant, (Annotation, Iterable[T]))

  lazy val sampleIdsBc = sparkContext.broadcast(sampleIds)

  lazy val sampleAnnotationsBc = sparkContext.broadcast(sampleAnnotations)

  /**
    * Aggregate by user-defined key and aggregation expressions.
    *
    * Equivalent of a group-by operation in SQL.
    *
    * @param keyExpr Named expression(s) for which fields are keys
    * @param aggExpr Named aggregation expression(s)
    */
  def aggregateByKey(keyExpr: String, aggExpr: String): KeyTable = {
    val aggregationST = Map(
      "global" -> (0, globalSignature),
      "v" -> (1, vSignature),
      "va" -> (2, vaSignature),
      "s" -> (3, sSignature),
      "sa" -> (4, saSignature),
      "g" -> (5, genotypeSignature))

    val ec = EvalContext(aggregationST.map { case (name, (i, t)) => name -> (i, TAggregable(t, aggregationST)) })

    val keyEC = EvalContext(Map(
      "global" -> (0, globalSignature),
      "v" -> (1, vSignature),
      "va" -> (2, vaSignature),
      "s" -> (3, sSignature),
      "sa" -> (4, saSignature),
      "g" -> (5, genotypeSignature)))

    val (keyNames, keyTypes, keyF) = Parser.parseNamedExprs(keyExpr, keyEC)
    val (aggNames, aggTypes, aggF) = Parser.parseNamedExprs(aggExpr, ec)

    val signature = TStruct((keyNames ++ aggNames, keyTypes ++ aggTypes).zipped.toSeq: _*)

    val (zVals, seqOp, combOp, resultOp) = Aggregators.makeFunctions[Annotation](ec, { case (ec, a) =>
      ec.setAllFromRow(a.asInstanceOf[Row])
    })

    val localGlobalAnnotation = globalAnnotation

    val ktRDD = mapPartitionsWithAll { it =>
      it.map { case (v, va, s, sa, g) =>
        keyEC.setAll(localGlobalAnnotation, v, va, s, sa, g)
        val key = Annotation.fromSeq(keyF())
        (key, Row(localGlobalAnnotation, v, va, s, sa, g))
      }
    }.aggregateByKey(zVals)(seqOp, combOp)
      .map { case (k, agg) =>
        resultOp(agg)
        Row.fromSeq(k.asInstanceOf[Row].toSeq ++ aggF())
      }

    KeyTable(hc, ktRDD, signature, keyNames)
  }


  def aggregateBySamplePerVariantKey(keyName: String, variantKeysVA: String, aggExpr: String, singleKey: Boolean = false): KeyTable = {

    val (keysType, keysQuerier) = queryVA(variantKeysVA)

    val (keyType, keyedRdd) =
      if (singleKey) {
        (keysType, rdd.flatMap { case (v, (va, gs)) => Option(keysQuerier(va)).map(key => (key, (v, va, gs))) })
      } else {
        val keyType = keysType match {
          case TArray(e) => e
          case TSet(e) => e
          case _ => fatal(s"With single_key=False, variant keys must be of type Set[T] or Array[T], got $keysType")
        }
        (keyType, rdd.flatMap { case (v, (va, gs)) =>
          Option(keysQuerier(va).asInstanceOf[Iterable[_]]).getOrElse(Iterable.empty).map(key => (key, (v, va, gs)))
        })
      }

    val SampleFunctions(zero, seqOp, combOp, resultOp, resultType) = Aggregators.makeSampleFunctions[T](this, aggExpr)

    val ktRDD = keyedRdd
      .aggregateByKey(zero)(seqOp, combOp)
      .map { case (key, agg) =>
        val results = resultOp(agg)
        results(0) = key
        Row.fromSeq(results)
      }

    val signature = TStruct((keyName -> keyType) +: stringSampleIds.map(id => id -> resultType): _*)

    new KeyTable(hc, ktRDD, signature, key = Array(keyName))
  }

  def aggregateBySample[U](zeroValue: U)(
    seqOp: (U, T) => U,
    combOp: (U, U) => U)(implicit uct: ClassTag[U]): RDD[(Annotation, U)] =
    aggregateBySampleWithKeys(zeroValue)((e, v, s, g) => seqOp(e, g), combOp)

  def aggregateBySampleWithKeys[U](zeroValue: U)(
    seqOp: (U, Variant, Annotation, T) => U,
    combOp: (U, U) => U)(implicit uct: ClassTag[U]): RDD[(Annotation, U)] = {
    aggregateBySampleWithAll(zeroValue)((e, v, va, s, sa, g) => seqOp(e, v, s, g), combOp)
  }

  def aggregateBySampleWithAll[U](zeroValue: U)(
    seqOp: (U, Variant, Annotation, Annotation, Annotation, T) => U,
    combOp: (U, U) => U)(implicit uct: ClassTag[U]): RDD[(Annotation, U)] = {

    val serializer = SparkEnv.get.serializer.newInstance()
    val zeroBuffer = serializer.serialize(zeroValue)
    val zeroArray = new Array[Byte](zeroBuffer.limit)
    zeroBuffer.get(zeroArray)
    val localSampleIdsBc = sampleIdsBc
    val localSampleAnnotationsBc = sampleAnnotationsBc

    rdd
      .mapPartitions { (it: Iterator[(Variant, (Annotation, Iterable[T]))]) =>
        val serializer = SparkEnv.get.serializer.newInstance()

        def copyZeroValue() = serializer.deserialize[U](ByteBuffer.wrap(zeroArray))

        val arrayZeroValue = Array.fill[U](localSampleIdsBc.value.length)(copyZeroValue())

        localSampleIdsBc.value.iterator
          .zip(it.foldLeft(arrayZeroValue) { case (acc, (v, (va, gs))) =>
            for ((g, i) <- gs.iterator.zipWithIndex) {
              acc(i) = seqOp(acc(i), v, va,
                localSampleIdsBc.value(i), localSampleAnnotationsBc.value(i), g)
            }
            acc
          }.iterator)
      }.foldByKey(zeroValue)(combOp)
  }

  def aggregateByVariant[U](zeroValue: U)(
    seqOp: (U, T) => U,
    combOp: (U, U) => U)(implicit uct: ClassTag[U]): RDD[(Variant, U)] =
    aggregateByVariantWithAll(zeroValue)((e, v, va, s, sa, g) => seqOp(e, g), combOp)

  def aggregateByVariantWithAll[U](zeroValue: U)(
    seqOp: (U, Variant, Annotation, Annotation, Annotation, T) => U,
    combOp: (U, U) => U)(implicit uct: ClassTag[U]): RDD[(Variant, U)] = {

    // Serialize the zero value to a byte array so that we can apply a new clone of it on each key
    val zeroBuffer = SparkEnv.get.serializer.newInstance().serialize(zeroValue)
    val zeroArray = new Array[Byte](zeroBuffer.limit)
    zeroBuffer.get(zeroArray)

    val localSampleIdsBc = sampleIdsBc
    val localSampleAnnotationsBc = sampleAnnotationsBc

    rdd
      .mapPartitions({ (it: Iterator[(Variant, (Annotation, Iterable[T]))]) =>
        val serializer = SparkEnv.get.serializer.newInstance()
        it.map { case (v, (va, gs)) =>
          val zeroValue = serializer.deserialize[U](ByteBuffer.wrap(zeroArray))
          (v, gs.iterator.zipWithIndex.map { case (g, i) => (localSampleIdsBc.value(i), localSampleAnnotationsBc.value(i), g) }
            .foldLeft(zeroValue) { case (acc, (s, sa, g)) =>
              seqOp(acc, v, va, s, sa, g)
            })
        }
      }, preservesPartitioning = true)

    /*
        rdd
          .map { case (v, gs) =>
            val serializer = SparkEnv.get.serializer.newInstance()
            val zeroValue = serializer.deserialize[U](ByteBuffer.wrap(zeroArray))

            (v, gs.zipWithIndex.foldLeft(zeroValue) { case (acc, (g, i)) =>
              seqOp(acc, v, localSamplesBc.value(i), g)
            })
          }
    */
  }

  def aggregateByVariantWithKeys[U](zeroValue: U)(
    seqOp: (U, Variant, Annotation, T) => U,
    combOp: (U, U) => U)(implicit uct: ClassTag[U]): RDD[(Variant, U)] = {
    aggregateByVariantWithAll(zeroValue)((e, v, va, s, sa, g) => seqOp(e, v, s, g), combOp)
  }

  def annotateGlobal(a: Annotation, t: Type, code: String): VariantSampleMatrix[T] = {
    val (newT, i) = insertGlobal(t, Parser.parseAnnotationRoot(code, Annotation.GLOBAL_HEAD))
    copy(globalSignature = newT, globalAnnotation = i(globalAnnotation, a))
  }

  /**
    * Create and destroy global annotations with expression language.
    *
    * @param expr Annotation expression
    */
  def annotateGlobalExpr(expr: String): VariantSampleMatrix[T] = {
    val ec = EvalContext(Map(
      "global" -> (0, globalSignature)))

    val (paths, types, f) = Parser.parseAnnotationExprs(expr, ec, Option(Annotation.GLOBAL_HEAD))

    val inserterBuilder = mutable.ArrayBuilder.make[Inserter]

    val finalType = (paths, types).zipped.foldLeft(globalSignature) { case (v, (ids, signature)) =>
      val (s, i) = v.insert(signature, ids)
      inserterBuilder += i
      s
    }

    val inserters = inserterBuilder.result()

    ec.set(0, globalAnnotation)
    val ga = inserters
      .zip(f())
      .foldLeft(globalAnnotation) { case (a, (ins, res)) =>
        ins(a, res)
      }

    copy(globalAnnotation = ga,
      globalSignature = finalType)
  }

  def insertGlobal(sig: Type, path: List[String]): (Type, Inserter) = {
    globalSignature.insert(sig, path)
  }

  def annotateSamples(signature: Type, path: List[String], annotation: (Annotation) => Annotation): VariantSampleMatrix[T] = {
    val (t, i) = insertSA(signature, path)
    annotateSamples(annotation, t, i)
  }

  def annotateSamplesExpr(expr: String): VariantSampleMatrix[T] = {
    val ec = sampleEC

    val (paths, types, f) = Parser.parseAnnotationExprs(expr, ec, Some(Annotation.SAMPLE_HEAD))

    val inserterBuilder = mutable.ArrayBuilder.make[Inserter]
    val finalType = (paths, types).zipped.foldLeft(saSignature) { case (sas, (ids, signature)) =>
      val (s, i) = sas.insert(signature, ids)
      inserterBuilder += i
      s
    }
    val inserters = inserterBuilder.result()

    val sampleAggregationOption = Aggregators.buildSampleAggregations(hc, value, ec)

    ec.set(0, globalAnnotation)
    val newAnnotations = sampleIdsAndAnnotations.map { case (s, sa) =>
      sampleAggregationOption.foreach(f => f.apply(s))
      ec.set(1, s)
      ec.set(2, sa)
      f().zip(inserters)
        .foldLeft(sa) { case (sa, (v, inserter)) =>
          inserter(sa, v)
        }
    }

    copy(
      sampleAnnotations = newAnnotations,
      saSignature = finalType
    )
  }

  def annotateSamples(annotations: Map[Annotation, Annotation], signature: Type, code: String): VariantSampleMatrix[T] = {
    val (t, i) = insertSA(signature, Parser.parseAnnotationRoot(code, Annotation.SAMPLE_HEAD))
    annotateSamples(s => annotations.getOrElse(s, null), t, i)
  }

  def annotateSamplesTable(kt: KeyTable, vdsKey: java.util.ArrayList[String],
    root: String, expr: String, product: Boolean): VariantSampleMatrix[T] =
    annotateSamplesTable(kt, if (vdsKey != null) vdsKey.asScala else null, root, expr, product)

  def annotateSamplesTable(kt: KeyTable, vdsKey: Seq[String] = null,
    root: String = null, expr: String = null, product: Boolean = false): VariantSampleMatrix[T] = {

    if (root == null && expr == null || root != null && expr != null)
      fatal("method `annotateSamplesTable' requires one of `root' or 'expr', but not both")

    var (joinSignature, f): (Type, Annotation => Annotation) = kt.valueSignature.size match {
      case 0 => (TBoolean, _ != null)
      case 1 => (kt.valueSignature.fields.head.typ, x => if (x != null) x.asInstanceOf[Row].get(0) else null)
      case _ => (kt.valueSignature, identity[Annotation])
    }

    if (product) {
      joinSignature = if (joinSignature == TBoolean) TInt else TArray(joinSignature)
      f = if (kt.valueSignature.size == 0)
        _.asInstanceOf[IndexedSeq[_]].length
      else {
        val g = f
        _.asInstanceOf[IndexedSeq[_]].map(g)
      }
    }

    val (finalType, inserter): (Type, (Annotation, Annotation) => Annotation) = {
      val (t, ins) = if (expr != null) {
        val ec = EvalContext(Map(
          "sa" -> (0, saSignature),
          "table" -> (1, joinSignature)))
        Annotation.buildInserter(expr, saSignature, ec, Annotation.SAMPLE_HEAD)
      } else insertSA(joinSignature, Parser.parseAnnotationRoot(root, Annotation.SAMPLE_HEAD))

      (t, (a: Annotation, toIns: Annotation) => ins(a, f(toIns)))
    }

    val keyTypes = kt.keyFields.map(_.typ)

    val keyedRDD = kt.keyedRDD()
      .filter { case (k, v) => k.toSeq.forall(_ != null) }

    val nullValue: IndexedSeq[Annotation] = if (product) IndexedSeq() else null

    if (vdsKey != null) {
      val keyEC = EvalContext(Map("s" -> (0, sSignature), "sa" -> (1, saSignature)))
      val (vdsKeyType, vdsKeyFs) = vdsKey.map(Parser.parseExpr(_, keyEC)).unzip

      if (!keyTypes.sameElements(vdsKeyType))
        fatal(
          s"""method `annotateSamplesTable' encountered a mismatch between table keys and computed keys.
             |  Computed keys:  [ ${ vdsKeyType.mkString(", ") } ]
             |  Key table keys: [ ${ keyTypes.mkString(", ") } ]""".stripMargin)

      val keyFuncArray = vdsKeyFs.toArray

      val thisRdd = sparkContext.parallelize(sampleIdsAndAnnotations.map { case (s, sa) =>
        keyEC.setAll(s, sa)
        (Row.fromSeq(keyFuncArray.map(_ ())), s)
      })

      var r = keyedRDD.join(thisRdd).map { case (_, (tableAnnotation, s)) => (s, tableAnnotation: Annotation) }
      if (product)
        r = r.groupByKey().mapValues(is => (is.toArray[Annotation]: IndexedSeq[Annotation]): Annotation)

      val m = r.collectAsMap()

      annotateSamples(m.getOrElse(_, nullValue), finalType, inserter)
    } else {
      keyTypes match {
        case Array(`sSignature`) =>
          var r = keyedRDD.map { case (k, v) => (k.asInstanceOf[Row].get(0), v: Annotation) }

          if (product)
            r = r.groupByKey()
              .map { case (s, rows) => (s, (rows.toArray[Annotation]: IndexedSeq[_]): Annotation) }

          val m = r.collectAsMap()

          annotateSamples(m.getOrElse(_, nullValue), finalType, inserter)
        case other =>
          fatal(
            s"""method 'annotate_samples_table' expects a key table keyed by [ $sSignature ]
               |  Found key [ ${ other.mkString(", ") } ] instead.""".stripMargin)
      }
    }
  }

  def annotateSamples(annotation: (Annotation) => Annotation, newSignature: Type, inserter: Inserter): VariantSampleMatrix[T] = {
    val newAnnotations = sampleIds.zipWithIndex.map { case (id, i) =>
      val sa = sampleAnnotations(i)
      val newAnnotation = inserter(sa, annotation(id))
      newSignature.typeCheck(newAnnotation)
      newAnnotation
    }

    copy(sampleAnnotations = newAnnotations, saSignature = newSignature)
  }

  def annotateVariants(otherRDD: OrderedRDD[Locus, Variant, Annotation], signature: Type,
    code: String): VariantSampleMatrix[T] = {
    val (newSignature, ins) = insertVA(signature, Parser.parseAnnotationRoot(code, Annotation.VARIANT_HEAD))
    annotateVariants(otherRDD, newSignature, ins, product = false)
  }

  def annotateVariantsExpr(expr: String): VariantSampleMatrix[T] = {
    val localGlobalAnnotation = globalAnnotation

    val ec = variantEC
    val (paths, types, f) = Parser.parseAnnotationExprs(expr, ec, Some(Annotation.VARIANT_HEAD))

    val inserterBuilder = mutable.ArrayBuilder.make[Inserter]
    val finalType = (paths, types).zipped.foldLeft(vaSignature) { case (vas, (ids, signature)) =>
      val (s, i) = vas.insert(signature, ids)
      inserterBuilder += i
      s
    }
    val inserters = inserterBuilder.result()

    val aggregateOption = Aggregators.buildVariantAggregations(this, ec)

    mapAnnotations { case (v, va, gs) =>
      ec.setAll(localGlobalAnnotation, v, va)

      aggregateOption.foreach(f => f(v, va, gs))
      f().zip(inserters)
        .foldLeft(va) { case (va, (v, inserter)) =>
          inserter(va, v)
        }
    }.copy(vaSignature = finalType)
  }

  def annotateVariantsTable(kt: KeyTable, vdsKey: java.util.ArrayList[String],
    root: String, expr: String, product: Boolean): VariantSampleMatrix[T] =
    annotateVariantsTable(kt, if (vdsKey != null) vdsKey.asScala else null, root, expr, product)

  def annotateVariantsTable(kt: KeyTable, vdsKey: Seq[String] = null,
    root: String = null, expr: String = null, product: Boolean = false): VariantSampleMatrix[T] = {

    if (root == null && expr == null || root != null && expr != null)
      fatal("method `annotateVariantsTable' requires one of `root' or 'expr', but not both")

    var (joinSignature, f): (Type, Annotation => Annotation) = kt.valueSignature.size match {
      case 0 => (TBoolean, _ != null)
      case 1 => (kt.valueSignature.fields.head.typ, x => if (x != null) x.asInstanceOf[Row].get(0) else null)
      case _ => (kt.valueSignature, identity[Annotation])
    }

    if (product) {
      joinSignature = if (joinSignature == TBoolean) TInt else TArray(joinSignature)
      f = if (kt.valueSignature.size == 0)
        _.asInstanceOf[IndexedSeq[_]].length
      else {
        val g = f
        _.asInstanceOf[IndexedSeq[_]].map(g)
      }
    }

    val (finalType, inserter): (Type, (Annotation, Annotation) => Annotation) = {
      val (t, ins) = if (expr != null) {
        val ec = EvalContext(Map(
          "va" -> (0, vaSignature),
          "table" -> (1, joinSignature)))
        Annotation.buildInserter(expr, vaSignature, ec, Annotation.VARIANT_HEAD)
      } else insertVA(joinSignature, Parser.parseAnnotationRoot(root, Annotation.VARIANT_HEAD))

      (t, (a: Annotation, toIns: Annotation) => ins(a, f(toIns)))
    }

    val keyTypes = kt.keyFields.map(_.typ)

    val keyedRDD = kt.keyedRDD()
      .filter { case (k, v) => k.toSeq.forall(_ != null) }

    if (vdsKey != null) {
      val keyEC = EvalContext(Map("v" -> (0, vSignature), "va" -> (1, vaSignature)))
      val (vdsKeyType, vdsKeyFs) = vdsKey.map(Parser.parseExpr(_, keyEC)).unzip

      if (!keyTypes.sameElements(vdsKeyType))
        fatal(
          s"""method `annotateVariantsTable' encountered a mismatch between table keys and computed keys.
             |  Computed keys:  [ ${ vdsKeyType.mkString(", ") } ]
             |  Key table keys: [ ${ keyTypes.mkString(", ") } ]""".stripMargin)

      val thisRdd = rdd.map { case (v, (va, gs)) =>
        keyEC.setAll(v, va)
        (Row.fromSeq(vdsKeyFs.map(_ ())), v)
      }

      val joinedRDD = keyedRDD
        .join(thisRdd)
        .map { case (_, (table, v)) => (v, table: Annotation) }
        .orderedRepartitionBy(rdd.orderedPartitioner)

      annotateVariants(joinedRDD, finalType, inserter, product = product)

    } else {
      keyTypes match {
        case Array(`vSignature`) =>
          val ord = keyedRDD
            .map { case (k, v) => (k.getAs[Variant](0), v: Annotation) }
            .toOrderedRDD(rdd.orderedPartitioner)

          annotateVariants(ord, finalType, inserter, product = product)
        case Array(TLocus) =>
          import LocusImplicits.orderedKey
          val ord = keyedRDD
            .map { case (k, v) => (k.asInstanceOf[Row].get(0).asInstanceOf[Locus], v: Annotation) }
            .toOrderedRDD(rdd.orderedPartitioner.mapMonotonic[Locus])

          annotateLoci(ord, finalType, inserter, product = product)

        case Array(TInterval) =>
          val partBc = sparkContext.broadcast(rdd.orderedPartitioner)
          val partitionKeyedIntervals = keyedRDD
            .flatMap { case (k, v) =>
              val interval = k.getAs[Interval[Locus]](0)
              val start = partBc.value.getPartitionT(interval.start)
              val end = partBc.value.getPartitionT(interval.end)
              (start to end).view.map(i => (i, (interval, v)))
            }

          type IntervalT = (Interval[Locus], Annotation)
          val nParts = rdd.partitions.length
          val zipRDD = partitionKeyedIntervals.partitionBy(new Partitioner {
            def getPartition(key: Any): Int = key.asInstanceOf[Int]

            def numPartitions: Int = nParts
          }).values

          val res = rdd.zipPartitions(zipRDD, preservesPartitioning = true) { case (it, intervals) =>
            val iTree = IntervalTree.annotationTree[Locus, Annotation](intervals.toArray)

            it.map { case (v, (va, gs)) =>
              val queries = iTree.queryValues(v.locus)
              val annot = if (product)
                queries: IndexedSeq[Annotation]
              else
                queries.headOption.orNull

              (v, (inserter(va, annot), gs))
            }
          }.asOrderedRDD

          copy(rdd = res, vaSignature = finalType)
        case other =>
          fatal(
            s"""method 'annotate_variants_table' expects a key table keyed by one of the following:
               |  [ $vSignature ]
               |  [ Locus ]
               |  [ Interval ]
               |  Found key [ ${ keyTypes.mkString(", ") } ] instead.""".stripMargin)
      }
    }
  }

  def annotateLoci(lociRDD: OrderedRDD[Locus, Locus, Annotation], newSignature: Type,
    inserter: Inserter, product: Boolean): VariantSampleMatrix[T] = {

    import LocusImplicits.orderedKey
    def annotate[S](joinedRDD: RDD[(Locus, ((Variant, (Annotation, Iterable[T])), S))],
      ins: (Annotation, S) => Annotation): OrderedRDD[Locus, Variant, (Annotation, Iterable[T])] = {
      OrderedRDD(joinedRDD.mapPartitions({ it =>
        it.map { case (l, ((v, (va, gs)), annotation)) => (v, (ins(va, annotation), gs)) }
      }),
        rdd.orderedPartitioner)
    }

    val locusKeyedRDD = rdd
      .mapMonotonic(OrderedKeyFunction(_.locus), { case (v, vags) => (v, vags) })

    val newRDD =
      if (product)
        annotate[Array[Annotation]](locusKeyedRDD.orderedLeftJoin(lociRDD),
          (va, a) => inserter(va, a: IndexedSeq[_]))
      else
        annotate[Option[Annotation]](locusKeyedRDD.orderedLeftJoinDistinct(lociRDD),
          (va, a) => inserter(va, a.orNull))

    copy(rdd = newRDD, vaSignature = newSignature)
  }

  def nPartitions: Int = rdd.partitions.length

  def annotateVariants(otherRDD: OrderedRDD[Locus, Variant, Annotation], newSignature: Type,
    inserter: Inserter, product: Boolean): VariantSampleMatrix[T] = {
    val newRDD = if (product)
      rdd.orderedLeftJoin(otherRDD)
        .mapValues { case ((va, gs), annotation) =>
          (inserter(va, annotation: IndexedSeq[_]), gs)
        }.asOrderedRDD
    else
      rdd.orderedLeftJoinDistinct(otherRDD)
        .mapValues { case ((va, gs), annotation) =>
          (inserter(va, annotation.orNull), gs)
        }.asOrderedRDD

    copy(rdd = newRDD, vaSignature = newSignature)
  }

  def annotateVariantsVDS(other: VariantSampleMatrix[_],
    root: Option[String] = None, code: Option[String] = None): VariantSampleMatrix[T] = {

    val (isCode, annotationExpr) = (root, code) match {
      case (Some(r), None) => (false, r)
      case (None, Some(c)) => (true, c)
      case _ => fatal("this module requires one of `root' or 'code', but not both")
    }

    val (finalType, inserter): (Type, (Annotation, Annotation) => Annotation) =
      if (isCode) {
        val ec = EvalContext(Map(
          "va" -> (0, vaSignature),
          "vds" -> (1, other.vaSignature)))
        Annotation.buildInserter(annotationExpr, vaSignature, ec, Annotation.VARIANT_HEAD)
      } else insertVA(other.vaSignature, Parser.parseAnnotationRoot(annotationExpr, Annotation.VARIANT_HEAD))

    annotateVariants(other.variantsAndAnnotations, finalType, inserter, product = false)
  }

  def count(): (Long, Long) = (nSamples, variants.count())

  def countVariants(): Long = variants.count()

  def variants: RDD[Variant] = rdd.keys

  def deduplicate(): VariantSampleMatrix[T] = {
    DuplicateReport.initialize()

    val acc = DuplicateReport.accumulator
    copy(rdd = rdd.mapPartitions({ it =>
      new SortedDistinctPairIterator(it, (v: Variant) => acc += v)
    }, preservesPartitioning = true).asOrderedRDD)
  }

  def deleteGlobal(args: String*): (Type, Deleter) = deleteGlobal(args.toList)

  def deleteGlobal(path: List[String]): (Type, Deleter) = globalSignature.delete(path)

  def deleteSA(args: String*): (Type, Deleter) = deleteSA(args.toList)

  def deleteSA(path: List[String]): (Type, Deleter) = saSignature.delete(path)

  def deleteVA(args: String*): (Type, Deleter) = deleteVA(args.toList)

  def deleteVA(path: List[String]): (Type, Deleter) = vaSignature.delete(path)

  def dropSamples(): VariantSampleMatrix[T] =
    copy(sampleIds = IndexedSeq.empty[Annotation],
      sampleAnnotations = IndexedSeq.empty[Annotation],
      rdd = rdd.mapValues { case (va, gs) => (va, Iterable.empty[T]) }.asOrderedRDD)

  def dropVariants(): VariantSampleMatrix[T] = copy(rdd = OrderedRDD.empty(sparkContext))

  def expand(): RDD[(Variant, Annotation, T)] =
    mapWithKeys[(Variant, Annotation, T)]((v, s, g) => (v, s, g))

  def expandWithAll(): RDD[(Variant, Annotation, Annotation, Annotation, T)] =
    mapWithAll[(Variant, Annotation, Annotation, Annotation, T)]((v, va, s, sa, g) => (v, va, s, sa, g))

  def mapWithAll[U](f: (Variant, Annotation, Annotation, Annotation, T) => U)(implicit uct: ClassTag[U]): RDD[U] = {
    val localSampleIdsBc = sampleIdsBc
    val localSampleAnnotationsBc = sampleAnnotationsBc

    rdd
      .flatMap { case (v, (va, gs)) =>
        localSampleIdsBc.value.lazyMapWith2[Annotation, T, U](localSampleAnnotationsBc.value, gs, { case (s, sa, g) => f(v, va, s, sa, g)
        })
      }
  }

  def exportGenotypes(path: String, expr: String, typeFile: Boolean, filterF: T => Boolean, parallel: Boolean = false) {
    val symTab = Map(
      "v" -> (0, vSignature),
      "va" -> (1, vaSignature),
      "s" -> (2, sSignature),
      "sa" -> (3, saSignature),
      "g" -> (4, genotypeSignature),
      "global" -> (5, globalSignature))

    val ec = EvalContext(symTab)
    ec.set(5, globalAnnotation)
    val (names, ts, f) = Parser.parseExportExprs(expr, ec)

    val hadoopConf = hc.hadoopConf
    if (typeFile) {
      hadoopConf.delete(path + ".types", recursive = false)
      val typeInfo = names
        .getOrElse(ts.indices.map(i => s"_$i").toArray)
        .zip(ts)
      exportTypes(path + ".types", hadoopConf, typeInfo)
    }

    hadoopConf.delete(path, recursive = true)

    mapPartitionsWithAll { it =>
      val sb = new StringBuilder()
      it
        .filter { case (v, va, s, sa, g) => filterF(g)
        }
        .map { case (v, va, s, sa, g) =>
          ec.setAll(v, va, s, sa, g)
          sb.clear()

          f().foreachBetween(x => sb.append(x))(sb += '\t')
          sb.result()
        }
    }.writeTable(path, hc.tmpDir, names.map(_.mkString("\t")), parallelWrite = parallel)
  }

  def exportSamples(path: String, expr: String, typeFile: Boolean = false) {
    val localGlobalAnnotation = globalAnnotation

    val ec = sampleEC

    val (names, types, f) = Parser.parseExportExprs(expr, ec)
    val hadoopConf = hc.hadoopConf
    if (typeFile) {
      hadoopConf.delete(path + ".types", recursive = false)
      val typeInfo = names
        .getOrElse(types.indices.map(i => s"_$i").toArray)
        .zip(types)
      exportTypes(path + ".types", hadoopConf, typeInfo)
    }

    val sampleAggregationOption = Aggregators.buildSampleAggregations(hc, value, ec)

    hadoopConf.delete(path, recursive = true)

    val sb = new StringBuilder()
    val lines = for ((s, sa) <- sampleIdsAndAnnotations) yield {
      sampleAggregationOption.foreach(f => f.apply(s))
      sb.clear()
      ec.setAll(localGlobalAnnotation, s, sa)
      f().foreachBetween(x => sb.append(x))(sb += '\t')
      sb.result()
    }

    hadoopConf.writeTable(path, lines, names.map(_.mkString("\t")))
  }

  def exportVariants(path: String, expr: String, typeFile: Boolean = false, parallel: Boolean = false) {
    val vas = vaSignature
    val hConf = hc.hadoopConf

    val localGlobalAnnotations = globalAnnotation
    val ec = variantEC

    val (names, types, f) = Parser.parseExportExprs(expr, ec)

    val hadoopConf = hc.hadoopConf
    if (typeFile) {
      hadoopConf.delete(path + ".types", recursive = false)
      val typeInfo = names
        .getOrElse(types.indices.map(i => s"_$i").toArray)
        .zip(types)
      exportTypes(path + ".types", hadoopConf, typeInfo)
    }

    val variantAggregations = Aggregators.buildVariantAggregations(this, ec)

    hadoopConf.delete(path, recursive = true)

    rdd
      .mapPartitions { it =>
        val sb = new StringBuilder()
        it.map { case (v, (va, gs)) =>
          variantAggregations.foreach { f => f(v, va, gs) }
          ec.setAll(localGlobalAnnotations, v, va)
          sb.clear()
          f().foreachBetween(x => sb.append(x))(sb += '\t')
          sb.result()
        }
      }.writeTable(path, hc.tmpDir, names.map(_.mkString("\t")), parallelWrite = parallel)
  }

  def filterIntervals(intervals: java.util.ArrayList[Interval[Locus]], keep: Boolean): VariantSampleMatrix[T] = {
    val iList = IntervalTree[Locus](intervals.asScala.toArray)
    filterIntervals(iList, keep)
  }

  def filterIntervals(iList: IntervalTree[Locus, _], keep: Boolean): VariantSampleMatrix[T] = {
    import scala.language.existentials
    if (keep)
      copy(rdd = rdd.filterIntervals(iList))
    else {
      val iListBc = sparkContext.broadcast(iList)
      filterVariants { (v, va, gs) => !iListBc.value.contains(v.locus) }
    }
  }

  def filterVariants(p: (Variant, Annotation, Iterable[T]) => Boolean): VariantSampleMatrix[T] =
    copy(rdd = rdd.filter { case (v, (va, gs)) => p(v, va, gs) }.asOrderedRDD)

  // FIXME see if we can remove broadcasts elsewhere in the code
  def filterSamples(p: (Annotation, Annotation) => Boolean): VariantSampleMatrix[T] = {
    val mask = sampleIdsAndAnnotations.map { case (s, sa) => p(s, sa) }
    val maskBc = sparkContext.broadcast(mask)
    val localtct = tct
    copy[T](sampleIds = sampleIds.zipWithIndex
      .filter { case (s, i) => mask(i) }
      .map(_._1),
      sampleAnnotations = sampleAnnotations.zipWithIndex
        .filter { case (sa, i) => mask(i) }
        .map(_._1),
      rdd = rdd.mapValues { case (va, gs) =>
        (va, gs.lazyFilterWith(maskBc.value, (g: T, m: Boolean) => m))
      }.asOrderedRDD)
  }

  /**
    * Filter samples using the Hail expression language.
    *
    * @param filterExpr Filter expression involving `s' (sample) and `sa' (sample annotations)
    * @param keep       keep where filterExpr evaluates to true
    */
  def filterSamplesExpr(filterExpr: String, keep: Boolean = true): VariantSampleMatrix[T] = {
    var filterAST = Parser.expr.parse(filterExpr)
    if (!keep)
      filterAST = Apply(filterAST.getPos, "!", Array(filterAST))
    copyAST(ast = FilterSamples(ast, filterAST))
  }

  def filterSamplesList(samples: java.util.ArrayList[Annotation], keep: Boolean): VariantSampleMatrix[T] =
    filterSamplesList(samples.asScala.toSet, keep)

  /**
    * Filter samples using a text file containing sample IDs
    *
    * @param samples Set of samples to keep or remove
    * @param keep    Keep listed samples.
    */
  def filterSamplesList(samples: Set[Annotation], keep: Boolean = true): VariantSampleMatrix[T] = {
    val p = (s: Annotation, sa: Annotation) => Filter.keepThis(samples.contains(s), keep)
    filterSamples(p)
  }

  def filterSamplesTable(table: KeyTable, keep: Boolean): VariantSampleMatrix[T] = {
    table.keyFields.map(_.typ) match {
      case Array(`sSignature`) =>
        val sampleSet = table.keyedRDD()
          .map { case (k, v) => k.get(0) }
          .filter(_ != null)
          .collectAsSet()
        filterSamplesList(sampleSet.toSet, keep)

      case other => fatal(
        s"""method 'filterSamplesTable' requires a table with key [ $sSignature ]
           |  Found key [ ${ other.mkString(", ") } ]""".stripMargin)
    }
  }

  /**
    * Filter variants using the Hail expression language.
    *
    * @param filterExpr filter expression
    * @param keep       keep variants where filterExpr evaluates to true
    * @return
    */
  def filterVariantsExpr(filterExpr: String, keep: Boolean = true): VariantSampleMatrix[T] = {
    var filterAST = Parser.expr.parse(filterExpr)
    if (!keep)
      filterAST = Apply(filterAST.getPos, "!", Array(filterAST))
    copyAST(ast = FilterVariants(ast, filterAST))
  }

  def filterVariantsList(variants: java.util.ArrayList[Variant], keep: Boolean): VariantSampleMatrix[T] =
    filterVariantsList(variants.asScala.toSet, keep)

  def filterVariantsList(variants: Set[Variant], keep: Boolean): VariantSampleMatrix[T] = {
    if (keep) {
      val partitionVariants = variants
        .groupBy(v => rdd.orderedPartitioner.getPartition(v))
        .toArray
        .sortBy(_._1)

      val adjRDD = new AdjustedPartitionsRDD[RowT](rdd,
        partitionVariants.map { case (oldPart, variantsSet) =>
          Array(Adjustment[RowT](oldPart,
            _.filter { case (v, _) =>
              variantsSet.contains(v)
            }))
        })

      val adjRangeBounds =
        if (partitionVariants.isEmpty)
          Array.empty[Locus]
        else
          partitionVariants.init.map { case (oldPart, _) =>
            rdd.orderedPartitioner.rangeBounds(oldPart)
          }

      copy(rdd = OrderedRDD(adjRDD, OrderedPartitioner(adjRangeBounds, partitionVariants.length)(
        rdd.kOk)))
    } else {
      val variantsBc = hc.sc.broadcast(variants)
      filterVariants { case (v, _, _) => !variantsBc.value.contains(v) }
    }
  }

  def filterVariantsTable(kt: KeyTable, keep: Boolean = true): VariantSampleMatrix[T] = {
    val keyFields = kt.keyFields.map(_.typ)
    val filt = keyFields match {
      case Array(`vSignature`) =>
        val variantRDD = kt.keyedRDD()
          .map { case (k, v) => (k.getAs[Variant](0), ()) }
          .filter(_._1 != null)
          .orderedRepartitionBy(rdd.orderedPartitioner)

        rdd.orderedLeftJoinDistinct(variantRDD)
          .mapPartitions(_.filter { case (_, (_, o)) =>
            Filter.keepThis(o.isDefined, keep)
          }.map { case (v, (vags, _)) =>
            (v, vags)
          }, preservesPartitioning = true)
          .asOrderedRDD

      case Array(TLocus) =>
        import LocusImplicits.orderedKey
        val locusRDD = kt.keyedRDD()
          .map { case (k, v) => (k.getAs[Locus](0), ()) }
          .filter(_._1 != null)
          .orderedRepartitionBy(rdd.orderedPartitioner.mapMonotonic(orderedKey))

        rdd.mapMonotonic(OrderedKeyFunction(_.locus), { case (v, vags) => (v, vags) })
          .orderedLeftJoinDistinct(locusRDD)
          .mapPartitions(_.filter { case (_, (_, o)) =>
            Filter.keepThis(o.isDefined, keep)
          }.map { case (_, ((v, vags), _)) =>
            (v, vags)
          }, preservesPartitioning = true)

      case Array(TInterval) =>
        val partBc = sparkContext.broadcast(rdd.orderedPartitioner)
        val intRDD = kt.keyedRDD()
          .map { case (k, _) => k.getAs[Interval[Locus]](0) }
          .filter(_ != null)
          .flatMap { interval =>
            val start = partBc.value.getPartitionT(interval.start)
            val end = partBc.value.getPartitionT(interval.end)
            (start to end).view.map(i => (i, interval))
          }

        val overlapPartitions = intRDD.keys.collectAsSet().toArray.sorted
        val partitionMap = overlapPartitions.zipWithIndex.toMap
        val leftTotalPartitions = rdd.partitions.length

        if (keep) {
          if (overlapPartitions.length < rdd.partitions.length)
            info(s"filtered to ${ overlapPartitions.length } of ${ leftTotalPartitions } partitions")


          val zipRDD = intRDD.partitionBy(new Partitioner {
            def getPartition(key: Any): Int = partitionMap(key.asInstanceOf[Int])

            def numPartitions: Int = overlapPartitions.length
          }).values

          rdd.subsetPartitions(overlapPartitions)
            .zipPartitions(zipRDD, preservesPartitioning = true) { case (it, intervals) =>
              val itree = IntervalTree.apply[Locus](intervals.toArray)
              it.filter { case (v, _) => itree.contains(v.locus) }
            }
        } else {
          val zipRDD = intRDD.partitionBy(new Partitioner {
            def getPartition(key: Any): Int = key.asInstanceOf[Int]

            def numPartitions: Int = leftTotalPartitions
          }).values

          rdd.zipPartitions(zipRDD, preservesPartitioning = true) { case (it, intervals) =>
            val itree = IntervalTree.apply[Locus](intervals.toArray)
            it.filter { case (v, _) => !itree.contains(v.locus) }
          }
        }

      case _ => fatal(
        s"""method 'filterVariantsTable' requires a table with one of the following keys:
           |  [ $vSignature ]
           |  [ Locus ]
           |  [ Interval ]
           |  Found [ ${ keyFields.mkString(", ") } ]""".stripMargin)
    }
    val keyIndex = kt.signature.fieldIdx(kt.key.head)

    copy(rdd = filt.asOrderedRDD)
  }

  def sparkContext: SparkContext = hc.sc

  def flatMap[U](f: T => TraversableOnce[U])(implicit uct: ClassTag[U]): RDD[U] =
    flatMapWithKeys((v, s, g) => f(g))

  def flatMapWithKeys[U](f: (Variant, Annotation, T) => TraversableOnce[U])(implicit uct: ClassTag[U]): RDD[U] = {
    val localSampleIdsBc = sampleIdsBc

    rdd
      .flatMap { case (v, (va, gs)) => localSampleIdsBc.value.lazyFlatMapWith(gs,
        (s: Annotation, g: T) => f(v, s, g))
      }
  }

  /**
    * The function {@code f} must be monotonic with respect to the ordering on {@code Locus}
    */
  def flatMapVariants(f: (Variant, Annotation, Iterable[T]) => TraversableOnce[(Variant, (Annotation, Iterable[T]))]): VariantSampleMatrix[T] =
    copy(rdd = rdd.flatMapMonotonic[(Annotation, Iterable[T])] { case (v, (va, gs)) => f(v, va, gs) })

  def hadoopConf: hadoop.conf.Configuration = hc.hadoopConf

  def insertGlobal(sig: Type, args: String*): (Type, Inserter) = insertGlobal(sig, args.toList)

  def insertSA(sig: Type, args: String*): (Type, Inserter) = insertSA(sig, args.toList)

  def insertSA(sig: Type, path: List[String]): (Type, Inserter) = saSignature.insert(sig, path)

  def insertVA(sig: Type, args: String*): (Type, Inserter) = insertVA(sig, args.toList)

  def insertVA(sig: Type, path: List[String]): (Type, Inserter) = {
    vaSignature.insert(sig, path)
  }

  /**
    *
    * @param right right-hand dataset with which to join
    */
  def join(right: VariantSampleMatrix[T]): VariantSampleMatrix[T] = {
    if (wasSplit != right.wasSplit) {
      warn(
        s"""cannot join split and unsplit datasets
           |  left was split: ${ wasSplit }
           |  light was split: ${ right.wasSplit }""".stripMargin)
    }

    if (genotypeSignature != right.genotypeSignature) {
      fatal(
        s"""cannot join datasets with different genotype schemata
           |  left sample schema: @1
           |  right sample schema: @2""".stripMargin,
        genotypeSignature.toPrettyString(compact = true, printAttrs = true),
        right.genotypeSignature.toPrettyString(compact = true, printAttrs = true))
    }

    if (saSignature != right.saSignature) {
      fatal(
        s"""cannot join datasets with different sample schemata
           |  left sample schema: @1
           |  right sample schema: @2""".stripMargin,
        saSignature.toPrettyString(compact = true, printAttrs = true),
        right.saSignature.toPrettyString(compact = true, printAttrs = true))
    }

    val newSampleIds = sampleIds ++ right.sampleIds
    val duplicates = newSampleIds.duplicates()
    if (duplicates.nonEmpty)
      fatal("duplicate sample IDs: @1", duplicates)

    val joined = rdd.orderedInnerJoinDistinct(right.rdd)
      .mapValues { case ((lva, lgs), (rva, rgs)) =>
        (lva, lgs ++ rgs)
      }.asOrderedRDD

    copy(
      sampleIds = newSampleIds,
      sampleAnnotations = sampleAnnotations ++ right.sampleAnnotations,
      rdd = joined)
  }

  def makeKT(variantCondition: String, genotypeCondition: String, keyNames: Array[String] = Array.empty, seperator: String = "."): KeyTable = {
    requireSampleTString("make table")

    val vSymTab = Map(
      "v" -> (0, vSignature),
      "va" -> (1, vaSignature))
    val vEC = EvalContext(vSymTab)
    val vA = vEC.a

    val (vNames, vTypes, vf) = Parser.parseNamedExprs(variantCondition, vEC)

    val gSymTab = Map(
      "v" -> (0, vSignature),
      "va" -> (1, vaSignature),
      "s" -> (2, sSignature),
      "sa" -> (3, saSignature),
      "g" -> (4, genotypeSignature))
    val gEC = EvalContext(gSymTab)
    val gA = gEC.a

    val (gNames, gTypes, gf) = Parser.parseNamedExprs(genotypeCondition, gEC)

    val sig = TStruct(((vNames, vTypes).zipped ++
      stringSampleIds.flatMap { s =>
        (gNames, gTypes).zipped.map { case (n, t) =>
          (if (n.isEmpty)
            s
          else
            s + seperator + n, t)
        }
      }).toSeq: _*)

    val localNSamples = nSamples
    val localSampleIdsBc = sampleIdsBc
    val localSampleAnnotationsBc = sampleAnnotationsBc

    KeyTable(hc,
      rdd.mapPartitions { it =>
        val n = vNames.length + gNames.length * localNSamples

        it.map { case (v, (va, gs)) =>
          val a = new Array[Any](n)

          var j = 0
          vEC.setAll(v, va)
          vf().foreach { x =>
            a(j) = x
            j += 1
          }

          gs.iterator.zipWithIndex.foreach { case (g, i) =>
            val s = localSampleIdsBc.value(i)
            val sa = localSampleAnnotationsBc.value(i)
            gEC.setAll(v, va, s, sa, g)
            gf().foreach { x =>
              a(j) = x
              j += 1
            }
          }

          assert(j == n)
          Row.fromSeq(a)
        }
      },
      sig,
      keyNames)
  }

  def map[U](f: T => U)(implicit uct: ClassTag[U]): RDD[U] =
    mapWithKeys((v, s, g) => f(g))

  def mapWithKeys[U](f: (Variant, Annotation, T) => U)(implicit uct: ClassTag[U]): RDD[U] = {
    val localSampleIdsBc = sampleIdsBc

    rdd
      .flatMap { case (v, (va, gs)) =>
        localSampleIdsBc.value.lazyMapWith[T, U](gs,
          (s, g) => f(v, s, g))
      }
  }

  def mapAnnotations(f: (Variant, Annotation, Iterable[T]) => Annotation): VariantSampleMatrix[T] =
    copy[T](rdd = rdd.mapValuesWithKey { case (v, (va, gs)) => (f(v, va, gs), gs) }.asOrderedRDD)

  def mapAnnotationsWithAggregate[U](zeroValue: U, newVAS: Type)(
    seqOp: (U, Variant, Annotation, Annotation, Annotation, T) => U,
    combOp: (U, U) => U,
    mapOp: (Annotation, U) => Annotation)
    (implicit uct: ClassTag[U]): VariantSampleMatrix[T] = {

    // Serialize the zero value to a byte array so that we can apply a new clone of it on each key
    val zeroBuffer = SparkEnv.get.serializer.newInstance().serialize(zeroValue)
    val zeroArray = new Array[Byte](zeroBuffer.limit)
    zeroBuffer.get(zeroArray)

    val localSampleIdsBc = sampleIdsBc
    val localSampleAnnotationsBc = sampleAnnotationsBc

    copy(vaSignature = newVAS,
      rdd = rdd.mapValuesWithKey { case (v, (va, gs)) =>
        val serializer = SparkEnv.get.serializer.newInstance()
        val zeroValue = serializer.deserialize[U](ByteBuffer.wrap(zeroArray))

        (mapOp(va, gs.iterator
          .zip(localSampleIdsBc.value.iterator
            .zip(localSampleAnnotationsBc.value.iterator)).foldLeft(zeroValue) { case (acc, (g, (s, sa))) =>
          seqOp(acc, v, va, s, sa, g)
        }), gs)
      }.asOrderedRDD)
  }

  def mapPartitionsWithAll[U](f: Iterator[(Variant, Annotation, Annotation, Annotation, T)] => Iterator[U])
    (implicit uct: ClassTag[U]): RDD[U] = {
    val localSampleIdsBc = sampleIdsBc
    val localSampleAnnotationsBc = sampleAnnotationsBc

    rdd.mapPartitions { it =>
      f(it.flatMap { case (v, (va, gs)) =>
        localSampleIdsBc.value.lazyMapWith2[Annotation, T, (Variant, Annotation, Annotation, Annotation, T)](
          localSampleAnnotationsBc.value, gs, { case (s, sa, g) => (v, va, s, sa, g) })
      })
    }
  }

  def mapValues[U](f: (T) => U)(implicit uct: ClassTag[U]): VariantSampleMatrix[U] = {
    mapValuesWithAll((v, va, s, sa, g) => f(g))
  }

  def mapValuesWithKeys[U](f: (Variant, Annotation, T) => U)
    (implicit uct: ClassTag[U]): VariantSampleMatrix[U] = {
    mapValuesWithAll((v, va, s, sa, g) => f(v, s, g))
  }

  def mapValuesWithAll[U](f: (Variant, Annotation, Annotation, Annotation, T) => U)
    (implicit uct: ClassTag[U]): VariantSampleMatrix[U] = {
    val localSampleIdsBc = sampleIdsBc
    val localSampleAnnotationsBc = sampleAnnotationsBc
    copy(rdd = rdd.mapValuesWithKey { case (v, (va, gs)) =>
      (va, localSampleIdsBc.value.lazyMapWith2[Annotation, T, U](
        localSampleAnnotationsBc.value, gs, { case (s, sa, g) => f(v, va, s, sa, g) }))
    }.asOrderedRDD)
  }

  def minRep(maxShift: Int = 100): VariantSampleMatrix[T] = {
    require(maxShift > 0, s"invalid value for maxShift: $maxShift. Parameter must be a positive integer.")
    val minrepped = rdd.map { case (v, (va, gs)) =>
      (v.minRep, (va, gs))
    }
    copy(rdd = minrepped.smartShuffleAndSort(rdd.orderedPartitioner, maxShift))
  }

  def queryGenotypes(expr: String): (Annotation, Type) = {
    val qv = queryGenotypes(Array(expr))
    assert(qv.length == 1)
    qv.head
  }

  def queryGenotypes(exprs: Array[String]): Array[(Annotation, Type)] = {
    val aggregationST = Map(
      "global" -> (0, globalSignature),
      "g" -> (1, genotypeSignature),
      "v" -> (2, vSignature),
      "va" -> (3, vaSignature),
      "s" -> (4, sSignature),
      "sa" -> (5, saSignature))
    val ec = EvalContext(Map(
      "global" -> (0, globalSignature),
      "gs" -> (1, TAggregable(genotypeSignature, aggregationST))))

    val ts = exprs.map(e => Parser.parseExpr(e, ec))

    val localGlobalAnnotation = globalAnnotation
    val (zVal, seqOp, combOp, resOp) = Aggregators.makeFunctions[T](ec, { case (ec, g) =>
      ec.set(1, g)
    })

    val globalBc = sparkContext.broadcast(globalAnnotation)
    val localSampleIdsBc = sampleIdsBc
    val localSampleAnnotationsBc = sampleAnnotationsBc

    val result = rdd.mapPartitions { it =>
      val zv = zVal.map(_.copy())
      ec.set(0, globalBc.value)
      it.foreach { case (v, (va, gs)) =>
        var i = 0
        ec.set(2, v)
        ec.set(3, va)
        gs.foreach { g =>
          ec.set(4, localSampleIdsBc.value(i))
          ec.set(5, localSampleAnnotationsBc.value(i))
          seqOp(zv, g)
          i += 1
        }
      }
      Iterator(zv)
    }.fold(zVal.map(_.copy()))(combOp)
    resOp(result)

    ec.set(0, localGlobalAnnotation)
    ts.map { case (t, f) => (f(), t) }
  }

  def queryGlobal(path: String): (Type, Annotation) = {
    val st = Map(Annotation.GLOBAL_HEAD -> (0, globalSignature))
    val ec = EvalContext(st)
    val a = ec.a

    val (t, f) = Parser.parseExpr(path, ec)

    val f2: Annotation => Any = { annotation =>
      a(0) = annotation
      f()
    }

    (t, f2(globalAnnotation))
  }

  def querySA(code: String): (Type, Querier) = {

    val st = Map(Annotation.SAMPLE_HEAD -> (0, saSignature))
    val ec = EvalContext(st)
    val a = ec.a

    val (t, f) = Parser.parseExpr(code, ec)

    val f2: Annotation => Any = { annotation =>
      a(0) = annotation
      f()
    }

    (t, f2)
  }

  def querySamples(expr: String): (Annotation, Type) = {
    val qs = querySamples(Array(expr))
    assert(qs.length == 1)
    qs.head
  }

  def querySamples(exprs: Array[String]): Array[(Annotation, Type)] = {
    val aggregationST = Map(
      "global" -> (0, globalSignature),
      "s" -> (1, sSignature),
      "sa" -> (2, saSignature))
    val ec = EvalContext(Map(
      "global" -> (0, globalSignature),
      "samples" -> (1, TAggregable(sSignature, aggregationST))))

    val ts = exprs.map(e => Parser.parseExpr(e, ec))

    val localGlobalAnnotation = globalAnnotation
    val (zVal, seqOp, combOp, resOp) = Aggregators.makeFunctions[(Annotation, Annotation)](ec, { case (ec, (s, sa)) =>
      ec.setAll(localGlobalAnnotation, s, sa)
    })

    val results = sampleIdsAndAnnotations
      .aggregate(zVal)(seqOp, combOp)
    resOp(results)
    ec.set(0, localGlobalAnnotation)

    ts.map { case (t, f) => (f(), t) }
  }

  def queryVA(code: String): (Type, Querier) = {

    val st = Map(Annotation.VARIANT_HEAD -> (0, vaSignature))
    val ec = EvalContext(st)
    val a = ec.a

    val (t, f) = Parser.parseExpr(code, ec)

    val f2: Annotation => Any = { annotation =>
      a(0) = annotation
      f()
    }

    (t, f2)
  }

  def queryVariants(expr: String): (Annotation, Type) = {
    val qv = queryVariants(Array(expr))
    assert(qv.length == 1)
    qv.head
  }

  def queryVariants(exprs: Array[String]): Array[(Annotation, Type)] = {

    val aggregationST = Map(
      "global" -> (0, globalSignature),
      "v" -> (1, vSignature),
      "va" -> (2, vaSignature))
    val ec = EvalContext(Map(
      "global" -> (0, globalSignature),
      "variants" -> (1, TAggregable(vSignature, aggregationST))))

    val ts = exprs.map(e => Parser.parseExpr(e, ec))

    val localGlobalAnnotation = globalAnnotation
    val (zVal, seqOp, combOp, resOp) = Aggregators.makeFunctions[(Variant, Annotation)](ec, { case (ec, (v, va)) =>
      ec.setAll(localGlobalAnnotation, v, va)
    })

    val result = variantsAndAnnotations
      .treeAggregate(zVal)(seqOp, combOp, depth = treeAggDepth(hc, nPartitions))
    resOp(result)

    ec.setAll(localGlobalAnnotation)
    ts.map { case (t, f) => (f(), t) }
  }

  def renameSamples(mapping: java.util.Map[Annotation, Annotation]): VariantSampleMatrix[T] =
    renameSamples(mapping.asScala.toMap)

  def renameSamples(mapping: Map[Annotation, Annotation]): VariantSampleMatrix[T] = {
    requireSampleTString("rename samples")

    val newSamples = mutable.Set.empty[Annotation]
    val newSampleIds = sampleIds
      .map { s =>
        val news = mapping.getOrElse(s, s)
        if (newSamples.contains(news))
          fatal(s"duplicate sample ID `$news' after rename")
        newSamples += news
        news
      }
    copy(sampleIds = newSampleIds)
  }

  def same(that: VariantSampleMatrix[T], tolerance: Double = utils.defaultTolerance): Boolean = {
    var metadataSame = true
    if (vaSignature != that.vaSignature) {
      metadataSame = false
      println(
        s"""different va signature:
           |  left:  ${ vaSignature.toPrettyString(compact = true) }
           |  right: ${ that.vaSignature.toPrettyString(compact = true) }""".stripMargin)
    }
    if (saSignature != that.saSignature) {
      metadataSame = false
      println(
        s"""different sa signature:
           |  left:  ${ saSignature.toPrettyString(compact = true) }
           |  right: ${ that.saSignature.toPrettyString(compact = true) }""".stripMargin)
    }
    if (globalSignature != that.globalSignature) {
      metadataSame = false
      println(
        s"""different global signature:
           |  left:  ${ globalSignature.toPrettyString(compact = true) }
           |  right: ${ that.globalSignature.toPrettyString(compact = true) }""".stripMargin)
    }
    if (sampleIds != that.sampleIds) {
      metadataSame = false
      println(
        s"""different sample ids:
           |  left:  $sampleIds
           |  right: ${ that.sampleIds }""".stripMargin)
    }
    if (!sampleAnnotationsSimilar(that, tolerance)) {
      metadataSame = false
      println(
        s"""different sample annotations:
           |  left:  $sampleAnnotations
           |  right: ${ that.sampleAnnotations }""".stripMargin)
    }
    if (sampleIds != that.sampleIds) {
      metadataSame = false
      println(
        s"""different global annotation:
           |  left:  $globalAnnotation
           |  right: ${ that.globalAnnotation }""".stripMargin)
    }
    if (wasSplit != that.wasSplit) {
      metadataSame = false
      println(
        s"""different was split:
           |  left:  $wasSplit
           |  right: ${ that.wasSplit }""".stripMargin)
    }
    if (!metadataSame)
      println("metadata were not the same")
    val vaSignatureBc = sparkContext.broadcast(vaSignature)
    val gSignatureBc = sparkContext.broadcast(genotypeSignature)
    var printed = false
    metadataSame &&
      rdd.mapPartitionsWithIndex { case (i, it) => it.map { case (v, (va, gs)) => (v, (va, gs, i)) } }
        .fullOuterJoin(that.rdd.mapPartitionsWithIndex { case (i, it) => it.map { case (v, (va, gs)) => (v, (va, gs, i)) } })
        .forall {
          case (v, (Some((va1, it1, p1)), Some((va2, it2, p2)))) =>
            val annotationsSame = vaSignatureBc.value.valuesSimilar(va1, va2, tolerance)
            if (!annotationsSame && !printed) {
              println(
                s"""at variant `$v', annotations were not the same:
                   |  $va1
                   |  $va2""".stripMargin)
              printed = true
            }
            val genotypesSame = (it1, it2).zipped.forall { case (g1, g2) =>
              val gSame = gSignatureBc.value.valuesSimilar(g1, g2, tolerance)
              if (!gSame)
                println(s"genotypes $g1, $g2 were not the same")
              gSame
            }
            annotationsSame && genotypesSame
          case (v, (l, r)) =>
            println(s"Found unmatched variant $v: left part = ${ l.map(_._3) }, right part = ${ r.map(_._3) }")
            false
        }
  }

  def sampleEC: EvalContext = {
    val aggregationST = Map(
      "global" -> (0, globalSignature),
      "s" -> (1, sSignature),
      "sa" -> (2, saSignature),
      "g" -> (3, genotypeSignature),
      "v" -> (4, vSignature),
      "va" -> (5, vaSignature))
    EvalContext(Map(
      "global" -> (0, globalSignature),
      "s" -> (1, sSignature),
      "sa" -> (2, saSignature),
      "gs" -> (3, TAggregable(genotypeSignature, aggregationST))))
  }

  def sampleAnnotationsSimilar(that: VariantSampleMatrix[T], tolerance: Double = utils.defaultTolerance): Boolean = {
    require(saSignature == that.saSignature)
    sampleAnnotations.zip(that.sampleAnnotations)
      .forall { case (s1, s2) => saSignature.valuesSimilar(s1, s2, tolerance)
      }
  }

  def sampleVariants(fraction: Double, seed: Int = 1): VariantSampleMatrix[T] = {
    require(fraction > 0 && fraction < 1, s"the 'fraction' parameter must fall between 0 and 1, found $fraction")
    copy(rdd = rdd.sample(withReplacement = false, fraction, seed).asOrderedRDD)
  }

  def copy[U](rdd: OrderedRDD[Locus, Variant, (Annotation, Iterable[U])] = rdd,
    sampleIds: IndexedSeq[Annotation] = sampleIds,
    sampleAnnotations: IndexedSeq[Annotation] = sampleAnnotations,
    globalAnnotation: Annotation = globalAnnotation,
    sSignature: Type = sSignature,
    saSignature: Type = saSignature,
    vSignature: Type = vSignature,
    vaSignature: Type = vaSignature,
    globalSignature: Type = globalSignature,
    genotypeSignature: Type = genotypeSignature,
    wasSplit: Boolean = wasSplit,
    isLinearScale: Boolean = isLinearScale,
    isGenericGenotype: Boolean = isGenericGenotype)
    (implicit tct: ClassTag[U]): VariantSampleMatrix[U] =
    new VariantSampleMatrix[U](hc,
      VSMMetadata(sSignature, saSignature, vSignature, vaSignature, globalSignature, genotypeSignature, wasSplit, isLinearScale, isGenericGenotype),
      VSMLocalValue(globalAnnotation, sampleIds, sampleAnnotations), rdd)

  def copyAST[U](ast: MatrixAST[U] = ast,
    sSignature: Type = sSignature,
    saSignature: Type = saSignature,
    vSignature: Type = vSignature,
    vaSignature: Type = vaSignature,
    globalSignature: Type = globalSignature,
    genotypeSignature: Type = genotypeSignature,
    wasSplit: Boolean = wasSplit,
    isLinearScale: Boolean = isLinearScale,
    isGenericGenotype: Boolean = isGenericGenotype)(implicit tct: ClassTag[U]): VariantSampleMatrix[U] =
    new VariantSampleMatrix[U](hc,
      VSMMetadata(sSignature, saSignature, vSignature, vaSignature, globalSignature, genotypeSignature, wasSplit, isLinearScale, isGenericGenotype),
      ast)

  def samplesKT(): KeyTable = {
    KeyTable(hc, sparkContext.parallelize(sampleIdsAndAnnotations)
      .map { case (s, sa) =>
        Row(s, sa)
      },
      TStruct(
        "s" -> sSignature,
        "sa" -> saSignature),
      Array("s"))
  }

  def storageLevel: String = rdd.getStorageLevel.toReadableString()

  def setVaAttributes(path: String, kv: Map[String, String]): VariantSampleMatrix[T] = {
    setVaAttributes(Parser.parseAnnotationRoot(path, Annotation.VARIANT_HEAD), kv)
  }

  def setVaAttributes(path: List[String], kv: Map[String, String]): VariantSampleMatrix[T] = {
    vaSignature match {
      case t: TStruct => copy(vaSignature = t.setFieldAttributes(path, kv))
      case t => fatal(s"Cannot set va attributes to ${ path.mkString(".") } since va is not a Struct.")
    }
  }

  def deleteVaAttribute(path: String, attribute: String): VariantSampleMatrix[T] = {
    deleteVaAttribute(Parser.parseAnnotationRoot(path, Annotation.VARIANT_HEAD), attribute)
  }

  def deleteVaAttribute(path: List[String], attribute: String): VariantSampleMatrix[T] = {
    vaSignature match {
      case t: TStruct => copy(vaSignature = t.deleteFieldAttribute(path, attribute))
      case t => fatal(s"Cannot delete va attributes from ${ path.mkString(".") } since va is not a Struct.")
    }
  }

  override def toString =
    s"VariantSampleMatrix(metadata=$metadata, rdd=$rdd, sampleIds=$sampleIds, nSamples=$nSamples, vaSignature=$vaSignature, saSignature=$saSignature, globalSignature=$globalSignature, sampleAnnotations=$sampleAnnotations, sampleIdsAndAnnotations=$sampleIdsAndAnnotations, globalAnnotation=$globalAnnotation, wasSplit=$wasSplit)"

  def nSamples: Int = sampleIds.length

  def typecheck() {
    var foundError = false
    if (!globalSignature.typeCheck(globalAnnotation)) {
      warn(
        s"""found violation in global annotation
           |Schema: ${ globalSignature.toPrettyString() }
           |Annotation: ${ Annotation.printAnnotation(globalAnnotation) }""".stripMargin)
    }

    sampleIdsAndAnnotations.find { case (_, sa) => !saSignature.typeCheck(sa)
    }
      .foreach { case (s, sa) =>
        foundError = true
        warn(
          s"""found violation in sample annotations for sample $s
             |Schema: ${ saSignature.toPrettyString() }
             |Annotation: ${ Annotation.printAnnotation(sa) }""".stripMargin)
      }

    val localVaSignature = vaSignature
    variantsAndAnnotations.find { case (_, va) => !localVaSignature.typeCheck(va)
    }
      .foreach { case (v, va) =>
        foundError = true
        warn(
          s"""found violation in variant annotations for variant $v
             |Schema: ${ localVaSignature.toPrettyString() }
             |Annotation: ${ Annotation.printAnnotation(va) }""".stripMargin)
      }

    if (foundError)
      fatal("found one or more type check errors")
  }

  def sampleIdsAndAnnotations: IndexedSeq[(Annotation, Annotation)] = sampleIds.zip(sampleAnnotations)

  def variantsAndAnnotations: OrderedRDD[Locus, Variant, Annotation] =
    rdd.mapValuesWithKey { case (v, (va, gs)) => va }.asOrderedRDD

  def variantEC: EvalContext = {
    val aggregationST = Map(
      "global" -> (0, globalSignature),
      "v" -> (1, vSignature),
      "va" -> (2, vaSignature),
      "g" -> (3, genotypeSignature),
      "s" -> (4, sSignature),
      "sa" -> (5, saSignature))
    EvalContext(Map(
      "global" -> (0, globalSignature),
      "v" -> (1, vSignature),
      "va" -> (2, vaSignature),
      "gs" -> (3, TAggregable(genotypeSignature, aggregationST))))
  }

  def variantsKT(): KeyTable = {
    KeyTable(hc, rdd.map { case (v, (va, gs)) =>
      Row(v, va)
    },
      TStruct(
        "v" -> vSignature,
        "va" -> vaSignature),
      Array("v"))
  }

  def genotypeKT(): KeyTable = {
    KeyTable(hc,
      expandWithAll().map { case (v, va, s, sa, g) => Row(v, va, s, sa, g) },
      TStruct(
        "v" -> vSignature,
        "va" -> vaSignature,
        "s" -> sSignature,
        "sa" -> saSignature,
        "g" -> genotypeSignature),
      Array("v", "s"))
  }

  /**
    *
    * @param config    VEP configuration file
    * @param root      Variant annotation path to store VEP output
    * @param csq       Annotates with the VCF CSQ field as a string, rather than the full nested struct schema
    * @param blockSize Variants per VEP invocation
    */
  def vep(config: String, root: String = "va.vep", csq: Boolean = false,
    blockSize: Int = 1000): VariantSampleMatrix[T] = {
    VEP.annotate(this, config, root, csq, blockSize)
  }

  def writeMetadata(dirname: String, parquetGenotypes: Boolean) {
    if (!dirname.endsWith(".vds") && !dirname.endsWith(".vds/"))
      fatal(s"output path ending in `.vds' required, found `$dirname'")

    val sqlContext = hc.sqlContext
    val hConf = hc.hadoopConf
    hConf.mkDir(dirname)

    val sb = new StringBuilder

    sSignature.pretty(sb, printAttrs = true, compact = true)
    val sSchemaString = sb.result()

    sb.clear
    saSignature.pretty(sb, printAttrs = true, compact = true)
    val saSchemaString = sb.result()

    sb.clear()
    vSignature.pretty(sb, printAttrs = true, compact = true)
    val vSchemaString = sb.result()

    sb.clear()
    vaSignature.pretty(sb, printAttrs = true, compact = true)
    val vaSchemaString = sb.result()

    sb.clear()
    globalSignature.pretty(sb, printAttrs = true, compact = true)
    val globalSchemaString = sb.result()

    sb.clear()
    genotypeSignature.pretty(sb, printAttrs = true, compact = true)
    val genotypeSchemaString = sb.result()

    val sampleInfoJson = JArray(
      sampleIdsAndAnnotations
        .map { case (id, annotation) =>
          JObject(List(("id", JSONAnnotationImpex.exportAnnotation(id, sSignature)),
            ("annotation", JSONAnnotationImpex.exportAnnotation(annotation, saSignature))))
        }
        .toList
    )

    val json = JObject(
      ("version", JInt(VariantSampleMatrix.fileVersion)),
      ("split", JBool(wasSplit)),
      ("isLinearScale", JBool(isLinearScale)),
      ("isGenericGenotype", JBool(isGenericGenotype)),
      ("parquetGenotypes", JBool(parquetGenotypes)),
      ("sample_schema", JString(sSchemaString)),
      ("sample_annotation_schema", JString(saSchemaString)),
      ("variant_schema", JString(vSchemaString)),
      ("variant_annotation_schema", JString(vaSchemaString)),
      ("global_annotation_schema", JString(globalSchemaString)),
      ("genotype_schema", JString(genotypeSchemaString)),
      ("sample_annotations", sampleInfoJson),
      ("global_annotation", JSONAnnotationImpex.exportAnnotation(globalAnnotation, globalSignature))
    )

    hConf.writeTextFile(dirname + "/metadata.json.gz")(Serialization.writePretty(json, _))
  }

  def unpersist() {
    rdd.unpersist()
  }
}
