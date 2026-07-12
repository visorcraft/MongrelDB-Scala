// Example: query builder conditions with the MongrelDB Scala client.
//
// Run: scala-cli run examples/QueryBuilderExample.scala
//   or (sbt):  sbt "examples/runMain com.example.QueryBuilderExample"
// Requires: mongreldb-server running on http://127.0.0.1:8453
//
// Creates a table, inserts five rows with varying scores, then uses the native
// query builder to fetch rows by a float range condition and by an exact
// primary-key match. Cleans up by dropping the table.

package com.example

import com.visorcraft.mongreldb.MongrelDB
import java.lang.Long.toHexString

object QueryBuilderExample:
  def main(args: Array[String]): Unit =
    val url = "http://127.0.0.1:8453"
    val table = s"example_query_${System.currentTimeMillis() / 1000}_${toHexString(System.nanoTime())}"

    val db = MongrelDB(url)
    if !db.health then
      System.err.println(s"daemon not reachable at $url")
      sys.exit(1)
    println("Connected to MongrelDB")

    try
      db.createTable(table, List(
        Map("id" -> 1, "name" -> "id", "ty" -> "int64", "primary_key" -> true, "nullable" -> false),
        Map("id" -> 2, "name" -> "name", "ty" -> "varchar", "primary_key" -> false, "nullable" -> false),
        Map("id" -> 3, "name" -> "score", "ty" -> "float64", "primary_key" -> false, "nullable" -> false)
      ))
      println(s"Created table $table")

      db.put(table, Map(1L -> 1L, 2L -> "Alice", 3L -> 40.0))
      db.put(table, Map(1L -> 2L, 2L -> "Bob", 3L -> 65.0))
      db.put(table, Map(1L -> 3L, 2L -> "Carol", 3L -> 82.0))
      db.put(table, Map(1L -> 4L, 2L -> "Dave", 3L -> 91.0))
      db.put(table, Map(1L -> 5L, 2L -> "Eve", 3L -> 12.5))
      println("Inserted 5 rows")

      // Range condition: scores in [60.0, 90.0]. "score" is float64, so use
      // range_f64 (plain "range" expects an i64 bound).
      val rng = db.query(table)
        .where("range_f64", Map("column" -> 3L, "min" -> 60.0, "max" -> 90.0,
                                "min_inclusive" -> true, "max_inclusive" -> true))
        .execute()
      println(s"Range query (score in [60,90]) returned ${rng.length} rows:")
      rng.foreach(row => println(s"  $row"))

      val pk = db.query(table).where("pk", Map("value" -> 4L)).execute()
      println(s"PK query (id == 4) returned ${pk.length} rows:")
      pk.foreach(row => println(s"  $row"))
    finally
      try db.dropTable(table)
      catch case _: Exception => ()
      println(s"Dropped table $table")
