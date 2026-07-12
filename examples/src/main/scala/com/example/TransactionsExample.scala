// Example: atomic batch transactions with the MongrelDB Scala client.
//
// Run: scala-cli run examples/TransactionsExample.scala
//   or (sbt):  sbt "examples/runMain com.example.TransactionsExample"
// Requires: mongreldb-server running on http://127.0.0.1:8453
//
// Creates a table, stages three inserts in a single transaction, commits them
// atomically, verifies the count, then demonstrates idempotent retries by
// re-committing with the same idempotency key. Cleans up by dropping the table.

package com.example

import com.visorcraft.mongreldb.MongrelDB
import java.lang.Long.toHexString

object TransactionsExample:
  def main(args: Array[String]): Unit =
    val url = "http://127.0.0.1:8453"
    val suffix = s"${System.currentTimeMillis() / 1000}_${toHexString(System.nanoTime())}"
    val table = s"example_txn_$suffix"
    val idempotencyKey = s"example-txn-$suffix"

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

      val txn = db.begin()
      txn.put(table, Map(1L -> 1L, 2L -> "Alice", 3L -> 95.5))
      txn.put(table, Map(1L -> 2L, 2L -> "Bob", 3L -> 82.0))
      txn.put(table, Map(1L -> 3L, 2L -> "Carol", 3L -> 78.3))
      println(s"Staged ${txn.count} operations")

      val results = txn.commit(null)
      println(s"Committed atomically: ${results.length} operations applied")
      println(s"Verified row count after commit: ${db.count(table)}")

      // Idempotent retry: same key on a second identical commit; the daemon
      // replays the original result and applies no extra rows.
      val retry1 = db.begin()
      retry1.put(table, Map(1L -> 4L, 2L -> "Dave", 3L -> 60.0))
      retry1.commit(idempotencyKey)
      println(s"After first idempotent commit: ${db.count(table)} rows")

      val retry2 = db.begin()
      retry2.put(table, Map(1L -> 4L, 2L -> "Dave", 3L -> 60.0))
      retry2.commit(idempotencyKey)
      println(s"After duplicate idempotent commit (same key): ${db.count(table)} rows (no double-apply)")
    finally
      try db.dropTable(table)
      catch case _: Exception => ()
      println(s"Dropped table $table")
