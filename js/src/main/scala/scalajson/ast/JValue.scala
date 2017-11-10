package scalajson.ast

import scala.scalajs.js

/** Represents a valid JSON Value
  *
  * @author Matthew de Detrich
  * @see https://www.ietf.org/rfc/rfc4627.txt
  */
sealed abstract class JValue extends Product with Serializable {

  /**
    * Converts a [[JValue]] to a [[unsafe.JValue]]. Note that
    * when converting [[JObject]], this can produce [[unsafe.JObject]] of
    * unknown ordering, since ordering on a [[scala.collection.Map]] isn't defined.
    * Duplicate keys will also be removed in an undefined manner.
    *
    * @see https://www.ietf.org/rfc/rfc4627.txt
    * @return
    */
  def toUnsafe: unsafe.JValue

  /**
    * Converts a [[JValue]] to a Javascript object/value that can be used within
    * Javascript
    *
    * @return
    */
  def toJsAny: js.Any
}

/** Represents a JSON null value
  *
  * @author Matthew de Detrich
  */
final case object JNull extends JValue {
  override def toUnsafe: unsafe.JValue = unsafe.JNull

  override def toJsAny: js.Any = null
}

/** Represents a JSON string value
  *
  * @author Matthew de Detrich
  */
final case class JString(value: String) extends JValue {
  override def toUnsafe: unsafe.JValue = unsafe.JString(value)

  override def toJsAny: js.Any = value
}

object JNumber {
  def apply(value: Int): JNumber = new JNumber(value.toString)

  def apply(value: Integer): JNumber = new JNumber(value.toString)

  def apply(value: Long): JNumber = new JNumber(value.toString)

  def apply(value: BigInt): JNumber = new JNumber(value.toString())

  def apply(value: BigDecimal): JNumber = new JNumber(value.toString())

  /**
    * @param value
    * @return Will return a JNull if value is a Nan or Infinity
    */
  def apply(value: Double): JValue = value match {
    case n if n.isNaN      => JNull
    case n if n.isInfinity => JNull
    case _                 => new JNumber(value.toString)
  }

  /**
    * @param value
    * @return Will return a JNull if value is a Nan or Infinity
    */
  def apply(value: Float): JValue = value match {
    case n if java.lang.Float.isNaN(n) => JNull
    case n if n.isInfinity             => JNull
    case _                             => new JNumber(value.toString)
  }

  def apply(value: String): Option[JNumber] =
    fromString(value)

  def fromString(value: String): Option[JNumber] =
    value match {
      case jNumberRegex(_*) => Some(new JNumber(value))
      case _                => None
    }
}

/** Represents a JSON number value. If you are passing in a
  * NaN or Infinity as a [[scala.Double]] or [[scala.Float]], [[JNumber]] will
  * return a [[JNull]].
  *
  * @author Matthew de Detrich
  */
final case class JNumber private[ast] (value: String) extends JValue {

  override def toUnsafe: unsafe.JValue = unsafe.JNumber(value)

  override def toJsAny: js.Any = value.toDouble match {
    case n if n.isNaN      => null
    case n if n.isInfinity => null
    case n                 => n
  }

  override def equals(obj: Any): Boolean =
    obj match {
      case jNumber: JNumber =>
        numericStringEquals(value, jNumber.value)
      case _ => false
    }

  override def hashCode: Int =
    numericStringHashcode(value)

  def copy(value: String): JNumber =
    value match {
      case jNumberRegex(_*) => new JNumber(value)
      case _                => throw new NumberFormatException(value)
    }

  def toInt: Option[Int] = scalajson.ast.toInt(value)

  def toBigInt: Option[BigInt] = scalajson.ast.toBigInt(value)

  def toLong: Option[Long] = scalajson.ast.toLong(value)

  def toDouble: Option[Double] = scalajson.ast.toDouble(value)

  def toBigDecimal: Option[BigDecimal] = scalajson.ast.toBigDecimal(value)
}

/** Represents a JSON Boolean value, which can either be a
  * [[JTrue]] or a [[JFalse]]
  *
  * @author Matthew de Detrich
  */
// Implements named extractors so we can avoid boxing
sealed abstract class JBoolean extends JValue {
  def isEmpty: Boolean = false
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
final case object JTrue extends JBoolean {
  override def get = true

  override def toUnsafe: unsafe.JValue = unsafe.JTrue
}

/** Represents a JSON Boolean false value
  *
  * @author Matthew de Detrich
  */
final case object JFalse extends JBoolean {
  override def get = false

  override def toUnsafe: unsafe.JValue = unsafe.JFalse
}

/** Represents a JSON Object value. Keys must be unique
  * and are unordered
  *
  * @author Matthew de Detrich
  */
final case class JObject(value: Map[String, JValue] = Map.empty)
    extends JValue {

  /**
    * Construct a JObject using Javascript's object type, i.e. {} or new Object
    *
    * @param value
    */
  def this(value: js.Dictionary[JValue]) = {
    this(value.toMap)
  }

  override def toUnsafe: unsafe.JValue = {
    if (value.isEmpty) {
      unsafe.JObject(js.Array[unsafe.JField]())
    } else {
      val array = js.Array[unsafe.JField]()
      value.iterator.foreach { x =>
        array.push(unsafe.JField(x._1, x._2.toUnsafe))
      }
      unsafe.JObject(array)
    }
  }

  override def toJsAny: js.Any = {
    if (value.isEmpty) {
      js.Dictionary[js.Any]().asInstanceOf[js.Object]
    } else {
      val dict = js.Dictionary[js.Any]()
      value.iterator.foreach { x =>
        dict(x._1) = x._2.toJsAny
      }
      dict.asInstanceOf[js.Object]
    }
  }
}

object JArray {
  def apply(value: JValue, values: JValue*): JArray =
    JArray(value +: values.toVector)
}

/** Represents a JSON Array value
  *
  * @author Matthew de Detrich
  */
final case class JArray(value: Vector[JValue] = Vector.empty) extends JValue {

  /**
    *
    * Construct a JArray using Javascript's array type, i.e. `[]` or `new Array`
    *
    * @param value
    */
  def this(value: js.Array[JValue]) = {
    this(value.toVector)
  }

  override def toUnsafe: unsafe.JValue = {
    if (value.isEmpty) {
      unsafe.JArray(js.Array[unsafe.JValue]())
    } else {
      val iterator = value.iterator
      val array = js.Array[unsafe.JValue]()
      while (iterator.hasNext) {
        array.push(iterator.next().toUnsafe)
      }
      unsafe.JArray(array)
    }
  }

  override def toJsAny: js.Any = {
    if (value.isEmpty) {
      js.Array[js.Any]()
    } else {
      val iterator = value.iterator
      val array = js.Array[js.Any]()
      while (iterator.hasNext) {
        array.push(iterator.next().toJsAny)
      }
      array
    }
  }
}
