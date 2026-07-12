package com.visorcraft.mongreldb

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
      Map[String, Any]("id" -> 3, "name" -> "retries", "ty" -> "int64", "default_value" -> 7),
      Map[String, Any]("id" -> 4, "name" -> "created_at", "ty" -> "timestamp", "default_expr" -> "now"),
      Map[String, Any]("id" -> 5, "name" -> "enabled", "ty" -> "bool", "default_value" -> true),
      Map[String, Any]("id" -> 6, "name" -> "optional", "ty" -> "varchar", "default_value" -> null),
      Map[String, Any]("id" -> 7, "name" -> "now_literal", "ty" -> "varchar", "default_value" -> "now"),
      Map[String, Any]("id" -> 8, "name" -> "uuid_literal", "ty" -> "varchar", "default_value" -> "uuid")
    )
    val constraints = Map[String, Any](
      "checks" -> List(Map(
        "id" -> 1,
        "name" -> "ck_status",
        "expr" -> Map("IsNotNull" -> 2)
      ))
    )

    val payload = MongrelDB.createTablePayload("orders", columns, constraints)
    val json = Json.toBytes(payload)
    val parsed = Json.parse(json).asInstanceOf[Map[String, Any]]
    val cols = parsed("columns").asInstanceOf[List[Map[String, Any]]]

    def col(name: String): Map[String, Any] =
      cols.find(_.get("name").contains(name)).getOrElse(fail(s"column $name not found"))

    assertEquals(col("status").get("enum_variants"), Some(List("draft", "open")))
    assertEquals(col("status").get("default_value"), Some("draft"))
    assertEquals(col("retries").get("default_value"), Some(7))
    assertEquals(col("created_at").get("default_expr"), Some("now"))
    assertEquals(col("created_at").get("default_value"), None)
    assertEquals(col("enabled").get("default_value"), Some(true))
    assertEquals(col("optional").get("default_value"), Some(null))
    assertEquals(col("now_literal").get("default_value"), Some("now"))
    assertEquals(col("uuid_literal").get("default_value"), Some("uuid"))

    val checks = parsed("constraints").asInstanceOf[Map[String, Any]]("checks").asInstanceOf[List[Map[String, Any]]]
    assertEquals(checks.head.get("name"), Some("ck_status"))
    assertEquals(checks.head.get("expr"), Some(Map("IsNotNull" -> 2)))
  }
