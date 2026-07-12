package com.visorcraft.mongreldb

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class HistoryRetentionWireShapeTest extends munit.FunSuite:
  test("GET /history/retention parses both keys") {
    withServer { exchange =>
      assertEquals(exchange.getRequestMethod, "GET")
      assertEquals(exchange.getRequestURI.getPath, "/history/retention")
      val body = """{"history_retention_epochs":100,"earliest_retained_epoch":7}"""
      exchange.getResponseHeaders.set("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, body.length)
      exchange.getResponseBody.write(body.getBytes(StandardCharsets.UTF_8))
      exchange.close()
    } { url =>
      val db = MongrelDB(url)
      assertEquals(db.historyRetentionEpochs, 100L)
      assertEquals(db.earliestRetainedEpoch, 7L)
    }
  }

  test("PUT /history/retention sends exactly history_retention_epochs") {
    withServer { exchange =>
      assertEquals(exchange.getRequestMethod, "PUT")
      assertEquals(exchange.getRequestURI.getPath, "/history/retention")
      assertEquals(exchange.getRequestHeaders.getFirst("Content-Type"), "application/json")
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val parsed = Json.parse(body.getBytes(StandardCharsets.UTF_8)).asInstanceOf[Map[String, Any]]
      assertEquals(parsed.get("history_retention_epochs"), Some(200L))
      assertEquals(parsed.get("earliest_retained_epoch"), None)

      val resp = """{"history_retention_epochs":200,"earliest_retained_epoch":3}"""
      exchange.getResponseHeaders.set("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, resp.length)
      exchange.getResponseBody.write(resp.getBytes(StandardCharsets.UTF_8))
      exchange.close()
    } { url =>
      val db = MongrelDB(url)
      val resp = db.setHistoryRetentionEpochs(200L)
      assertEquals(resp.get("history_retention_epochs"), Some(200L))
      assertEquals(resp.get("earliest_retained_epoch"), Some(3L))
    }
  }

  test("setHistoryRetentionEpochs rejects negative epochs") {
    val db = MongrelDB("http://127.0.0.1:1")
    intercept[IllegalArgumentException] {
      db.setHistoryRetentionEpochs(-1L)
    }
  }

  test("non-2xx retention response maps to QueryException") {
    withServer { exchange =>
      val body = """{"error":{"message":"history_retention_epochs must be a u64"}}"""
      exchange.getResponseHeaders.set("Content-Type", "application/json")
      exchange.sendResponseHeaders(400, body.length)
      exchange.getResponseBody.write(body.getBytes(StandardCharsets.UTF_8))
      exchange.close()
    } { url =>
      val db = MongrelDB(url)
      intercept[QueryException] {
        db.setHistoryRetentionEpochs(1L)
      }
    }
  }

  private def withServer(handler: HttpHandler)(test: String => Unit): Unit =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", handler)
    server.start()
    try
      test(s"http://127.0.0.1:${server.getAddress.getPort}")
    finally
      server.stop(0)
