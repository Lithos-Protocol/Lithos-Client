package node

import node.rest.{NodeHttpConfig, RestNodeApi}
import okhttp3.mockwebserver.{MockResponse, MockWebServer}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.util.concurrent.TimeUnit

class NodeHttpBoundsSpec extends AnyFlatSpec with Matchers {
  "Bounded node HTTP" should "refuse an oversized body before JSON decoding" in {
    val server = new MockWebServer()
    server.start()
    try {
      server.enqueue(new MockResponse().setBody("x" * 1025))
      val api = RestNodeApi(NodeHttpConfig(server.url("/").toString, maxResponseBytes = 1024))
      val result = api.info()
      result.isFailure shouldBe true
      result.failed.get.getMessage should include("byte limit")
    } finally server.shutdown()
  }
  it should "bound an entire stalled call" in {
    val server = new MockWebServer()
    server.start()
    try {
      server.enqueue(new MockResponse().setBody("{}").setHeadersDelay(2, TimeUnit.SECONDS))
      val api = RestNodeApi(NodeHttpConfig(server.url("/").toString, callTimeoutMs = 100L))
      val start = System.nanoTime()
      api.info().isFailure shouldBe true
      ((System.nanoTime() - start) / 1000000L) should be < 1500L
    } finally server.shutdown()
  }
}