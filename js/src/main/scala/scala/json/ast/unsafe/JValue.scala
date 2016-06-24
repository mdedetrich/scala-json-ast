package scala.json.ast.unsafe

import scala.annotation.meta.field
import scala.json.ast
import scala.json.ast._
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExport

/** Represents a JSON Value which may be invalid. Internally uses mutable
  * collections when its desirable to do so, for performance and other reasons
  * (such as ordering and duplicate keys)
  *
  * @author Matthew de Detrich
  * @see https://www.ietf.org/rfc/rfc4627.txt
  */
sealed abstract class JValue extends Serializable with Product {

  /**
    * Converts a [[unsafe.JValue]] to a [[ast.JValue]]. Note that
    * when converting [[unsafe.JNumber]], this can throw runtime error if the underlying
    * string representation is not a correct number. Also when converting a [[ast.JObject]]
    * to a [[ast.JObject]], its possible to lose data if you have duplicate keys.
    *
    * @see https://www.ietf.org/rfc/rfc4627.txt
    * @return
    */
  def toStandard: ast.JValue

  /**
    * Converts a [[unsafe.JValue]] to a Javascript object/value that can be used within
    * Javascript
    *
    * @return
    */
  def toJsAny: js.Any
}

object JValue {
  private val undefined = js.undefined

  private def unsafeAny2JValue(input: Any): JValue = input match {
    case null        => JNull
    case s: String   => JString(s)
    case b: Boolean  => JBoolean(b)
    case d: Double   => JNumber(d.toString)
    case `undefined` => JNull

    case a: js.Array[js.Dynamic @unchecked] =>
      JArray(a.map(v => unsafeAny2JValue(v)))

    case o: js.Object =>
      JObject(o.asInstanceOf[js.Dictionary[js.Dynamic]]
        .map { case (k, v) => JField(k, unsafeAny2JValue(v)) }.toArray)

    case _ => throw new IllegalArgumentException()
  }

  /**
    * Converts a Javascript object/value coming from Javascript to a [[JValue]].
    *
    * @return
    */
  def fromJsAny(json: js.Any): Option[JValue] =
    try {
      Some(unsafeAny2JValue(json))
    } catch {
      case e: IllegalArgumentException => None
    }
}

/** Represents a JSON null value
  *
  * @author Matthew de Detrich
  */
case object JNull extends JValue {
  override def toStandard: ast.JValue = ast.JNull

  override def toJsAny: js.Any = null
}

/** Represents a JSON string value
  *
  * @author Matthew de Detrich
  */
case class JString(value: String) extends JValue {
  override def toStandard: ast.JValue = ast.JString(value)

  override def toJsAny: js.Any = value
}

object JNumber {
  def apply(value: Int): JNumber = JNumber(value.toString)

  def apply(value: Short): JNumber = JNumber(value.toString)

  def apply(value: Long): JNumber = JNumber(value.toString)

  def apply(value: BigInt): JNumber = JNumber(value.toString)

  def apply(value: BigDecimal): JNumber = JNumber(value.toString)

  def apply(value: Float): JNumber = JNumber(value.toString)

  def apply(value: Double): JNumber = JNumber(value.toString)

  def apply(value: Integer): JNumber = JNumber(value.toString)

  def apply(value: Array[Char]): JNumber = JNumber(value.mkString)
}

/** Represents a JSON number value.
  *
  * If you are passing in a NaN or Infinity as a [[Double]], [[unsafe.JNumber]]
  * will contain "NaN" or "Infinity" as a String which means it will cause
  * issues for users when they use the value at runtime. It may be
  * preferable to check values yourself when constructing [[unsafe.JValue]]
  * to prevent this. This isn't checked by default for performance reasons.
  *
  * @author Matthew de Detrich
  */
// JNumber is internally represented as a string, to improve performance
case class JNumber(@(JSExport @field) value: String) extends JValue {
  def to[B](implicit jNumberConverter: JNumberConverter[B]) =
    jNumberConverter(value)

  override def toStandard: ast.JValue = ast.JNumber(value)

  def this(value: Double) = {
    this(value.toString)
  }

  override def toJsAny: js.Any = value.toDouble match {
    case n if n.isNaN => null
    case n if n.isInfinity => null
    case n => n
  }
}

/** Represents a JSON Boolean value, which can either be a
  * [[JTrue]] or a [[JFalse]]
  *
  * @author Matthew de Detrich
  */
// Implements named extractors so we can avoid boxing
sealed abstract class JBoolean extends JValue {
  def get: Boolean

  override def toJsAny: js.Any = get
}

object JBoolean {
  def apply(x: Boolean): JBoolean = if (x) JTrue else JFalse

  def unapply(x: JBoolean): Some[Boolean] = Some(x.get)
}

/** Represents a JSON Boolean true value
  *
  * @author Matthew de Detrich
  */
case object JTrue extends JBoolean {
  override def get = true

  override def toStandard: ast.JValue = ast.JTrue
}

/** Represents a JSON Boolean false value
  *
  * @author Matthew de Detrich
  */
case object JFalse extends JBoolean {
  override def get = false

  override def toStandard: ast.JValue = ast.JFalse
}

case class JField(field: String, value: JValue)

object JObject {
  import js.JSConverters._
  def apply(value: JField, values: JField*): JObject =
    JObject(js.Array(value) ++ values.toJSArray)

  def apply(value: Array[JField]): JObject = JObject(value.toJSArray)
}

/** Represents a JSON Object value. Duplicate keys
  * are allowed and ordering is respected
  *
  * @author Matthew de Detrich
  */
// JObject is internally represented as a mutable Array, to improve sequential performance
case class JObject(value: js.Array[JField] = js.Array()) extends JValue {
  def this(value: js.Dictionary[JValue]) = {
    this({
      val array: js.Array[JField] = new js.Array()
      for (key <- value.keys) {
        array.push(JField(key, value(key)))
      }
      array
    })
  }

  override def toStandard: ast.JValue = {
    // Javascript array.length across all major browsers has near constant cost, so we
    // use this to build the array http://jsperf.com/length-comparisons
    val length = value.length

    if (length == 0) {
      ast.JObject(Map.newBuilder[String, ast.JValue].result())
    } else {
      val b = Map.newBuilder[String, ast.JValue]
      var index = 0
      while (index < length) {
        val v = value(index)
        b += ((v.field, v.value.toStandard))
        index += 1
      }
      ast.JObject(b.result())
    }
  }

  override def toJsAny: js.Any = {
    val length = value.length

    if (length == 0) {
      js.Dictionary[js.Any]().asInstanceOf[js.Object]
    } else {
      val dict = js.Dictionary[js.Any]()
      var index = 0
      while (index < length) {
        val v = value(index)
        dict(v.field) = v.value.toJsAny
        index += 1
      }
      dict.asInstanceOf[js.Object]
    }
  }
}

object JArray {
  import js.JSConverters._
  def apply(value: JValue, values: JValue*): JArray =
    JArray(js.Array(value) ++ values.toJSArray)

  def apply(value: Array[JValue]): JArray = JArray(value.toJSArray)
}

/** Represents a JSON Array value
  *
  * @author Matthew de Detrich
  */
// JArray is internally represented as a mutable js.Array, to improve sequential performance
case class JArray(value: js.Array[JValue] = js.Array()) extends JValue {
  override def toStandard: ast.JValue = {
    // Javascript array.length across all major browsers has near constant cost, so we
    // use this to build the array http://jsperf.com/length-comparisons
    val length = value.length
    if (length == 0) {
      ast.JArray(Vector.newBuilder[ast.JValue].result())
    } else {
      val b = Vector.newBuilder[ast.JValue]
      var index = 0
      while (index < length) {
        b += value(index).toStandard
        index += 1
      }
      ast.JArray(b.result())
    }
  }

  override def toJsAny: js.Any = value
}
