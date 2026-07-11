package dev.visorcraft.mongreldb

import java.nio.charset.StandardCharsets

class CreateTableWireShapeTest extends munit.FunSuite:
  test("createTable payload preserves column options and table checks") {
    val columns = List(
      Map[String, Any]("id" -> 1, "name" -> "id", "ty" -> "int64", "primary_key" -> true),
      Map[String, Any](
        "id" -> 2,
        "name" -> "status",
        "ty" -> "enum",
        "enum_variants" -> List("draft", "open"),
        "default_value" -> "draft"
      ),
      Map[String, Any]("id" -> 3, "name" -> "retries", "ty" -> "int64", "default_value" -> 3),
      Map[String, Any]("id" -> 4, "name" -> "created_at", "ty" -> "timestamp", "default_expr" -> "now")
    )
    val constraints = Map[String, Any](
      "checks" -> List(Map(
        "id" -> 1,
        "name" -> "ck_status",
        "expr" -> Map("IsNotNull" -> 2)
      ))
    )

    val json = String(
      Json.toBytes(MongrelDB.createTablePayload("orders", columns, constraints)),
      StandardCharsets.UTF_8
    )
    assert(json.contains("\"enum_variants\":[\"draft\",\"open\"]"))
    assert(json.contains("\"default_value\":\"draft\""))
    assert(json.contains("\"default_value\":3"))
    assert(json.contains("\"default_expr\":\"now\""))
    assert(json.contains("\"constraints\""))
    assert(json.contains("\"checks\""))
    assert(json.contains("\"IsNotNull\":2"))
  }
