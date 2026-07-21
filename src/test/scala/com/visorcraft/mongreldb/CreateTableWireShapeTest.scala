package com.visorcraft.mongreldb

class CreateTableWireShapeTest extends munit.FunSuite:
  test("query builder includes offset") {
    val payload = new QueryBuilder(null, "orders").limit(10).offset(12).build()
    assertEquals(payload("limit"), 10L)
    assertEquals(payload("offset"), 12L)
  }

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

  test("createTable payload preserves ANN index options for swappable backends") {
    val columns = List(
      Map[String, Any]("id" -> 1, "name" -> "id", "ty" -> "int64", "primary_key" -> true),
      Map[String, Any]("id" -> 2, "name" -> "embedding", "ty" -> "embedding(384)")
    )
    // DiskANN backend with dense vectors: algorithm, quantization, and the
    // diskann-specific hyperparameters must all survive the round-trip.
    val indexes = List(Map[String, Any](
      "name" -> "ann",
      "column_id" -> 2,
      "kind" -> "ann",
      "predicate" -> "embedding IS NOT NULL",
      "options" -> Map[String, Any](
        "ann" -> Map[String, Any](
          "algorithm" -> "diskann",
          "quantization" -> "dense",
          "diskann" -> Map[String, Any](
            "r" -> 128,
            "l" -> 256,
            "beam_width" -> 8,
            "alpha" -> 1.2
          )
        )
      )
    ))

    val payload = MongrelDB.createTablePayload("vectors", columns, Map.empty, indexes)
    val json = Json.toBytes(payload)
    val parsed = Json.parse(json).asInstanceOf[Map[String, Any]]

    // Indexes must reach the wire as a top-level array named "indexes".
    val wireIndexes = parsed("indexes").asInstanceOf[List[Map[String, Any]]]
    assertEquals(wireIndexes.length, 1)
    val ann = wireIndexes.head
    assertEquals(ann("kind"), "ann")
    assertEquals(ann("predicate"), "embedding IS NOT NULL")

    val annOpts = ann("options").asInstanceOf[Map[String, Any]]("ann").asInstanceOf[Map[String, Any]]
    assertEquals(annOpts("algorithm"), "diskann")
    assertEquals(annOpts("quantization"), "dense")
    val diskann = annOpts("diskann").asInstanceOf[Map[String, Any]]
    assertEquals(diskann("r"), 128L)
    assertEquals(diskann("l"), 256L)
    assertEquals(diskann("beam_width"), 8L)
    assertEquals(diskann("alpha"), 1.2)

    // The raw JSON must carry the algorithm and quantization markers verbatim so
    // the daemon's ANN-backend selector can dispatch on them.
    val raw = new String(json, java.nio.charset.StandardCharsets.UTF_8)
    assert(raw.contains("\"algorithm\":\"diskann\""), s"missing algorithm marker: $raw")
    assert(raw.contains("\"quantization\":\"dense\""), s"missing quantization marker: $raw")
    assert(raw.contains("\"diskann\":{"), s"missing diskann options block: $raw")
  }
