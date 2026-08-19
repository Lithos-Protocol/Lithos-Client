package node.rest

import com.google.gson.{JsonElement, JsonObject, JsonParser}
import node.NodeError
import okhttp3.{HttpUrl, MediaType, OkHttpClient, Request, RequestBody}

import java.util.concurrent.TimeUnit
import scala.util.{Failure, Success, Try}

case class NodeHttpConfig(baseUrl: String,
                          apiKey: Option[String] = None,
                          connectTimeoutMs: Long = 5000L,
                          readTimeoutMs: Long = 30000L,
                          writeTimeoutMs: Long = 30000L)

object NodeHttp {

  private val Json: MediaType = MediaType.parse("application/json; charset=utf-8")

  def apply(config: NodeHttpConfig): NodeHttp = {
    val client = new OkHttpClient.Builder()
      .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
      .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
      .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
      .build()
    new NodeHttp(config, client)
  }
}

class NodeHttp(config: NodeHttpConfig, client: OkHttpClient) {

  private val base: HttpUrl = Option(HttpUrl.parse(config.baseUrl))
    .getOrElse(throw new IllegalArgumentException(s"not a valid node url: ${config.baseUrl}"))

  private def url(path: String, params: Seq[(String, String)]): HttpUrl = {
    val b = base.newBuilder().addPathSegments(path.stripPrefix("/"))
    params.foreach { case (k, v) => b.addQueryParameter(k, v) }
    b.build()
  }

  private def request(path: String, params: Seq[(String, String)]): Request.Builder = {
    val b = new Request.Builder().url(url(path, params))
    config.apiKey.foreach(k => b.header("api_key", k))
    b
  }

  private def send(req: Request, endpoint: String): Try[Option[String]] =
    Try(client.newCall(req).execute()).transform({ response =>
      try {
        val code = response.code()
        val body = Option(response.body()).map(_.string()).getOrElse("")
        if (code == 404) Success(None)
        else if (response.isSuccessful) Success(Some(body))
        else Failure(errorFor(code, body, endpoint))
      } finally response.close()
    }, t => Failure(NodeError.Transport(endpoint, t)))

  private def errorFor(code: Int, body: String, endpoint: String): NodeError = {
    val parsed = Try(new JsonParser().parse(body)).toOption
      .filter(_.isJsonObject)
      .map(_.getAsJsonObject)
    val reason = parsed.flatMap(o => Option(o.get("reason"))).filter(_.isJsonPrimitive).map(_.getAsString)
    val detail = parsed.flatMap(o => Option(o.get("detail"))).filter(_.isJsonPrimitive).map(_.getAsString)
    code match {
      case 400 => NodeError.Rejected(detail.orElse(reason).getOrElse(body))
      case 401 | 403 => NodeError.Unauthorized(endpoint)
      case _ => NodeError.Http(code, reason.getOrElse("unknown"), detail)
    }
  }

  private def parse(endpoint: String)(body: String): Try[JsonElement] =
    Try(new JsonParser().parse(body)).transform(
      e => if (e == null || e.isJsonNull) Failure(NodeError.Decoding(endpoint, new NoSuchElementException("empty body")))
      else Success(e),
      t => Failure(NodeError.Decoding(endpoint, t))
    )

  private def required[A](endpoint: String)(o: Option[A]): Try[A] =
    o.fold[Try[A]](Failure(NodeError.Http(404, "not found", Some(endpoint))))(Success(_))

  def getJson(path: String, params: Seq[(String, String)] = Nil): Try[JsonElement] =
    send(request(path, params).get().build(), path).flatMap(required(path)).flatMap(parse(path))

  def getJsonOpt(path: String, params: Seq[(String, String)] = Nil): Try[Option[JsonElement]] =
    send(request(path, params).get().build(), path).flatMap {
      case None       => Success(None)
      case Some(body) => parse(path)(body).map(Some(_))
    }

  def getText(path: String, params: Seq[(String, String)] = Nil): Try[String] =
    send(request(path, params).get().build(), path).flatMap(required(path)).map(unquote)

  def getTextOpt(path: String, params: Seq[(String, String)] = Nil): Try[Option[String]] =
    send(request(path, params).get().build(), path).map(_.map(unquote))

  def postJson(path: String, body: String, params: Seq[(String, String)] = Nil): Try[JsonElement] =
    send(request(path, params).post(RequestBody.create(NodeHttp.Json, body)).build(), path)
      .flatMap(required(path)).flatMap(parse(path))

  def postJsonOpt(path: String, body: String, params: Seq[(String, String)] = Nil): Try[Option[JsonElement]] =
    send(request(path, params).post(RequestBody.create(NodeHttp.Json, body)).build(), path).flatMap {
      case None    => Success(None)
      case Some(b) => parse(path)(b).map(Some(_))
    }

  def postText(path: String, body: String, params: Seq[(String, String)] = Nil): Try[String] =
    send(request(path, params).post(RequestBody.create(NodeHttp.Json, body)).build(), path)
      .flatMap(required(path)).map(unquote)

  def postUnit(path: String, body: String, params: Seq[(String, String)] = Nil): Try[Unit] =
    send(request(path, params).post(RequestBody.create(NodeHttp.Json, body)).build(), path).map(_ => ())

  def getUnit(path: String, params: Seq[(String, String)] = Nil): Try[Unit] =
    send(request(path, params).get().build(), path).map(_ => ())

  def reachable: Boolean = getJson("/info").isSuccess

  private def unquote(s: String): String = {
    val t = s.trim
    if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t.substring(1, t.length - 1) else t
  }
}

object JsonOps {

  implicit class RichJsonObject(val o: JsonObject) extends AnyVal {

    private def field(k: String): Option[JsonElement] =
      Option(o.get(k)).filterNot(_.isJsonNull)

    def str(k: String): String = field(k).map(_.getAsString)
      .getOrElse(throw new NoSuchElementException(s"missing string field '$k'"))

    def optStr(k: String): Option[String] = field(k).map(_.getAsString)

    def int(k: String): Int = field(k).map(_.getAsInt)
      .getOrElse(throw new NoSuchElementException(s"missing int field '$k'"))

    def optInt(k: String): Option[Int] = field(k).map(_.getAsInt)

    def long(k: String): Long = field(k).map(_.getAsLong)
      .getOrElse(throw new NoSuchElementException(s"missing long field '$k'"))

    def optLong(k: String): Option[Long] = field(k).map(_.getAsLong)

    def bool(k: String): Boolean = field(k).exists(_.getAsBoolean)

    def optBool(k: String): Option[Boolean] = field(k).map(_.getAsBoolean)

    def bigInt(k: String): BigInt = field(k).map(e => BigInt(e.getAsString))
      .getOrElse(throw new NoSuchElementException(s"missing bigint field '$k'"))

    def optBigInt(k: String): Option[BigInt] = field(k).map(e => BigInt(e.getAsString))

    def obj(k: String): JsonObject = field(k).map(_.getAsJsonObject)
      .getOrElse(throw new NoSuchElementException(s"missing object field '$k'"))

    def optObj(k: String): Option[JsonObject] = field(k).map(_.getAsJsonObject)

    def objects(k: String): Seq[JsonObject] = field(k)
      .map(e => jsonArrayToSeq(e).map(_.getAsJsonObject))
      .getOrElse(Seq.empty[JsonObject])

    def strings(k: String): Seq[String] = field(k)
      .map(e => jsonArrayToSeq(e).map(_.getAsString))
      .getOrElse(Seq.empty[String])

    def ints(k: String): Seq[Int] = field(k)
      .map(e => jsonArrayToSeq(e).map(_.getAsInt))
      .getOrElse(Seq.empty[Int])

    def stringMap(k: String): Map[String, String] = field(k).map { e =>
      val entries = e.getAsJsonObject.entrySet().iterator()
      val builder = Map.newBuilder[String, String]
      while (entries.hasNext) {
        val entry = entries.next()
        if (!entry.getValue.isJsonNull) builder += entry.getKey -> entry.getValue.getAsString
      }
      builder.result()
    }.getOrElse(Map.empty[String, String])

    /**
     * An object of `key -> number`, in document order.
     *
     * The node uses this shape wherever a field is a total per id rather than a list of records —
     * `/wallet/balances` reports `assets` this way, while a box reports its `assets` as an array of
     * `{tokenId, amount}`. Reading one with the other's accessor yields nothing rather than failing,
     * so the two are kept apart here.
     */
    def longEntries(k: String): Seq[(String, Long)] = field(k).map { e =>
      val entries = e.getAsJsonObject.entrySet().iterator()
      val builder = Seq.newBuilder[(String, Long)]
      while (entries.hasNext) {
        val entry = entries.next()
        if (!entry.getValue.isJsonNull) builder += entry.getKey -> entry.getValue.getAsLong
      }
      builder.result()
    }.getOrElse(Seq.empty[(String, Long)])
  }

  def jsonArrayToSeq(e: JsonElement): Seq[JsonElement] = {
    if (e == null || e.isJsonNull || !e.isJsonArray) Seq.empty[JsonElement]
    else {
      val arr = e.getAsJsonArray
      val builder = Seq.newBuilder[JsonElement]
      var i = 0
      while (i < arr.size()) {
        builder += arr.get(i)
        i += 1
      }
      builder.result()
    }
  }

  def asObjects(e: JsonElement): Seq[JsonObject] = jsonArrayToSeq(e).map(_.getAsJsonObject)

  def asStrings(e: JsonElement): Seq[String] = jsonArrayToSeq(e).map(_.getAsString)
}
