// Example: basic CRUD operations with the MongrelDB Scala client.
//
// Run: scala-cli run examples/BasicCrud.scala --dep org.scalameta::munit:0.7.29
//   or (sbt):  sbt "examples/runMain com.example.BasicCrud"
// Requires: mongreldb-server running on http://127.0.0.1:8453
//
// Creates a table, inserts three rows, counts them, queries all rows, upserts
// (updates) one row by primary key, deletes one row, then drops the table.

package com.example

import dev.visorcraft.mongreldb.MongrelDB
import java.lang.Long.toHexString

object BasicCrud:
  def main(args: Array[String]): Unit =
    val url = "http://127.0.0.1:8453"
    // Unique suffix per run so concurrent/repeated runs don't collide.
    val table = s"example_crud_${System.currentTimeMillis() / 1000}_${toHexString(System.nanoTime())}"

    val db = MongrelDB(url)
    if !db.health then
      System.err.println(s"daemon not reachable at $url")
      sys.exit(1)
    println("Connected to MongrelDB")

    try
      val tid = db.createTable(table, List(
        Map("id" -> 1, "name" -> "id", "ty" -> "int64", "primary_key" -> true, "nullable" -> false),
        Map("id" -> 2, "name" -> "role", "ty" -> "enum",
            "enum_variants" -> List("admin", "member", "guest"),
            "default_value" -> "member", "primary_key" -> false, "nullable" -> false),
        Map("id" -> 3, "name" -> "name", "ty" -> "varchar", "primary_key" -> false, "nullable" -> false),
        Map("id" -> 4, "name" -> "score", "ty" -> "float64", "default_value" -> 0,
            "primary_key" -> false, "nullable" -> false)
      ))
      println(s"Created table $table (id $tid)")

      db.put(table, Map(1L -> 1L, 2L -> "admin", 3L -> "Alice", 4L -> 95.5))
      db.put(table, Map(1L -> 2L, 3L -> "Bob", 4L -> 82.0))   // role defaults to "member"
      db.put(table, Map(1L -> 3L, 2L -> "guest", 3L -> "Carol", 4L -> 78.3))
      println("Inserted 3 rows")

      println(s"Total rows: ${db.count(table)}")

      val all = db.query(table).execute()
      println(s"Query returned ${all.length} rows:")
      all.foreach(row => println(s"  $row"))

      db.upsert(table, Map(1L -> 1L, 2L -> "admin", 3L -> "Alice", 4L -> 100.0),
                updateCells = Map(2L -> "admin", 3L -> "Alice", 4L -> 100.0))
      println("Upserted Alice's score to 100.0")
      println(s"Total rows after upsert: ${db.count(table)}")

      db.deleteByPk(table, 3L)
      println(s"Deleted Carol; remaining rows: ${db.count(table)}")
    finally
      try db.dropTable(table)
      catch case _: Exception => ()
      println(s"Dropped table $table")
