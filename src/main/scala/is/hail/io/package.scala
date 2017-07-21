package is.hail

import is.hail.expr.Type
import is.hail.utils._
import org.apache.hadoop.conf.Configuration

package object io {
  def exportTypes(filename: String, hConf: Configuration, info: Array[(String, Type)]) {
    val sb = new StringBuilder
    hConf.writeTextFile(filename) { out =>
      info.foreachBetween { case (name, t) =>
        sb.append(prettyIdentifier(name))
        sb.append(":")
        t.pretty(sb, printAttrs = true, compact = true)
      } { sb += ',' }

      out.write(sb.result())
    }
  }
}
