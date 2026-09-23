package controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.http.{DefaultFileMimeTypesProvider, DefaultHttpErrorHandler, FileMimeTypesConfiguration}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.{Environment, Mode}

import java.net.URLClassLoader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global

class WebPanelControllerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem("web-panel-spec")
  private implicit val mat: Materializer = Materializer(system)

  /** A built site as the postbuild leaves it, on a classpath of its own. */
  private val root: Path = Files.createTempDirectory("web-panel-")
  private def write(file: String, body: String): Unit = {
    val target = root.resolve(s"public/$file")
    Files.createDirectories(target.getParent)
    Files.write(target, body.getBytes(UTF_8))
  }
  write("index.html", "home")
  write("404.html", "not found page")
  write("mining/payments/index.html", "payments page")
  write("mining/payments.html", "payments sibling")
  write("docs/intro.html", "intro sibling only")
  write("js/main.abc123.js", "console.log(1)")

  private val environment = Environment(root.toFile, new URLClassLoader(Array(root.toUri.toURL), null), Mode.Test)
  private val controller = {
    val mimeTypes = new DefaultFileMimeTypesProvider(
      FileMimeTypesConfiguration(Map("html" -> "text/html", "js" -> "application/javascript"))).get
    val meta = new DefaultAssetsMetadata(environment, AssetsConfiguration(), mimeTypes)
    new WebPanelController(new Assets(new DefaultHttpErrorHandler(), meta), environment, stubControllerComponents())
  }
  private final case class Served(status: Int, body: String, contentType: Option[String])

  /** Reads each body exactly once: an unread asset stream holds its file open, and Windows then refuses cleanup. */
  private def get(file: String): Served = {
    val result = controller.page(file).apply(FakeRequest(GET, s"/assets/$file"))
    Served(status(result), contentAsString(result), contentType(result))
  }

  override def afterAll(): Unit = {
    system.terminate()
    val files = Files.walk(root)
    try files.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
    finally files.close()
  }

  "The web panel" should "serve a page URL as its index.html, so a refresh finds it" in {
    get("mining/payments") shouldBe Served(OK, "payments page", Some("text/html"))
    get("mining/payments/").body shouldBe "payments page"
  }

  it should "serve the site root for the bare mount" in {
    get("").body shouldBe "home"
  }

  it should "fall back to a sibling .html when a page has no directory" in {
    get("docs/intro").body shouldBe "intro sibling only"
  }

  it should "serve files with an extension exactly as before" in {
    get("js/main.abc123.js") shouldBe Served(OK, "console.log(1)", Some("application/javascript"))
    get("mining/payments.html").body shouldBe "payments sibling"
    get("js/missing.js").status shouldBe NOT_FOUND
  }

  it should "answer an unknown page with the site's 404 page and a 404 status" in {
    get("mining/nowhere") shouldBe Served(NOT_FOUND, "not found page", Some("text/html"))
  }

  it should "never look outside public for a page" in {
    get("../public/mining/payments").status shouldBe NOT_FOUND
    get("mining/../../secret").status shouldBe NOT_FOUND
  }
}
