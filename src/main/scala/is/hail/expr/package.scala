package is.hail

import scala.language.implicitConversions
import is.hail.asm4s.Code

package object expr extends HailRepFunctions {
  type SymbolTable = Map[String, (Int, Type)]
  def emptySymTab = Map.empty[String, (Int, Type)]

  type Aggregator = TypedAggregator[Any]

  abstract class TypedAggregator[+S] extends Serializable {
    def seqOp(x: Any): Unit

    def combOp(agg2: this.type): Unit

    def result: S

    def copy(): TypedAggregator[S]
  }

  implicit def toRichParser[T](parser: Parser.Parser[T]): RichParser[T] = new RichParser(parser)

  type CPS[T] = (T => Unit) => Unit
  type CMCodeCPS[T] = (Code[T] => CM[Code[Unit]]) => CM[Code[Unit]]
}
