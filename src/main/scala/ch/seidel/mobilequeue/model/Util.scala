package ch.seidel.mobilequeue.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import spray.json.{ JsString, JsValue, JsonReader, JsonWriter, _ }

import scala.util.Try

trait EnrichedJson {
  implicit class RichJson(jsValue: JsValue) {
    def asOpt[T](implicit reader: JsonReader[T]): Option[T] = Try(jsValue.convertTo[T]).toOption
    def canConvert[T](implicit reader: JsonReader[T]): Boolean = Try(jsValue.convertTo[T]).isSuccess
    def withoutFields(fieldnames: String*) = {
      jsValue.asJsObject.copy(jsValue.asJsObject.fields -- fieldnames)
    }
    def addFields(fieldnames: Map[String, JsValue]) = {
      jsValue.asJsObject.copy(jsValue.asJsObject.fields ++ fieldnames)
    }
    def toJsonStringWithType[T](t: T) = {
      jsValue.addFields(Map(("type" -> JsString(t.getClass.getSimpleName)))).compactPrint
    }
  }

  implicit class JsonString(string: String) {
    def asType[T](implicit reader: JsonReader[T]): T = string.parseJson.convertTo[T]
    def asJsonOpt[T](implicit reader: JsonReader[T]): Option[T] = Try(string.parseJson.convertTo[T]).toOption
    def canConvert[T](implicit reader: JsonReader[T]): Boolean = Try(string.parseJson.convertTo[T]).isSuccess
  }

  import scala.reflect.ClassTag

  import scala.reflect.runtime.universe._

  def getObjectInstance(clsName: String): AnyRef = {
    val mirror = runtimeMirror(getClass.getClassLoader)
    val module = mirror.staticModule(clsName)
    mirror.reflectModule(module).instance.asInstanceOf[AnyRef]
  }

  def objectBy[T: ClassTag](name: String): T = {
    val c = implicitly[ClassTag[T]]
    try {
      getObjectInstance(c + "$" + name + "$").asInstanceOf[T]
    } catch {
      case e: Exception => {
        val cnn = c.toString
        val cn = cnn.substring(0, cnn.lastIndexOf("."))
        getObjectInstance(cn + "." + name + "$").asInstanceOf[T]
      }
    }
  }

  def string2trait[T: TypeTag: ClassTag]: Map[JsValue, T] = {
    val clazz = typeOf[T].typeSymbol.asClass
    clazz.knownDirectSubclasses.map { sc =>
      val objectName = sc.toString.stripPrefix("object ")
      (JsString(objectName), objectBy[T](objectName))
    }.toMap
  }

  class CaseObjectJsonSupport[T: TypeTag: ClassTag] extends RootJsonFormat[T] {
    val string2T: Map[JsValue, T] = string2trait[T]
    def defaultValue: T = deserializationError(s"${implicitly[ClassTag[T]].runtimeClass.getCanonicalName} expected")
    override def read(json: JsValue): T = string2T.getOrElse(json, defaultValue)
    override def write(value: T) = JsString(value.toString())
  }

}

trait Hashing {
  def sha256(text: String): String = {
    // Create a message digest every time since it isn't thread safe!
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(text.getBytes(StandardCharsets.UTF_8)).map("%02X".format(_)).mkString
  }
}
