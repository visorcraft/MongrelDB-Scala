// Example: basic CRUD with the native embedded MongrelDB engine (Tier 1).
//
// Unlike BasicCrud.scala which connects to a daemon over HTTP, this example
// runs the engine in-process via JNI. No daemon needed.
//
// Run: sbt "examples/runMain com.example.NativeBasicCrud"
//   Set MONGRELDB_NATIVE_DIR to the directory containing libmongreldb_jni
//   Download from https://github.com/visorcraft/MongrelDB/releases

package com.example

import com.visorcraft.mongreldb.native_mode.NativeDB

object NativeBasicCrud:
  def main(args: Array[String]): Unit =
    if !NativeDB.nativeAvailable() then
      System.err.println("Native library not available.")
      System.err.println("Set MONGRELDB_NATIVE_DIR to the directory containing")
      System.err.println("libmongreldb_jni.so (Linux), .dylib (macOS), or .dll (Windows).")
      System.err.println("Download from https://github.com/visorcraft/MongrelDB/releases")
      return

    val dbDir = System.getProperty("java.io.tmpdir") + "/mdb_native_example_" + System.currentTimeMillis()
    val schemaJson =
      """{"tables":[{"id":1,"name":"users",
      "columns":[
      {"id":1,"name":"id","storage_type":"int64","application_type":"int64","nullable":false,"primary_key":true,"default":null,"generated":false},
      {"id":2,"name":"name","storage_type":"text","application_type":"text","nullable":true,"primary_key":false,"default":null,"generated":false},
      {"id":3,"name":"email","storage_type":"text","application_type":"text","nullable":true,"primary_key":false,"default":null,"generated":false}
      ],"primary_key":["id"]}]}"""

    println("=== Native Embedded Basic CRUD ===")
    println(s"Database dir: $dbDir")
    println()

    val db = NativeDB.create(dbDir, schemaJson)
    try
      println("1. Database created with schema (users table)")

      // Insert rows via SQL.
      db.sqlRows("INSERT INTO users (id, name, email) VALUES (1, 'Alice', 'alice@example.com')")
      db.sqlRows("INSERT INTO users (id, name, email) VALUES (2, 'Bob', 'bob@example.com')")
      db.sqlRows("INSERT INTO users (id, name, email) VALUES (3, 'Carol', 'carol@example.com')")
      println("2. Inserted 3 rows via SQL")

      // SELECT via SQL (JSON rows).
      val rows = db.sqlRows("SELECT id, name, email FROM users ORDER BY id")
      println("3. SELECT all rows:")
      println(s"   $rows")

      // Arrow IPC for columnar reads.
      val arrow = db.sqlArrow("SELECT id FROM users")
      println(s"4. Arrow IPC: ${arrow.length} bytes")

      // Migration: add an orders table.
      val migrations =
        """[{"version":1,"name":"add_orders",
        "ops":[{"raw_sql":"CREATE TABLE orders (id INT64 PRIMARY KEY, user_id INT64, total FLOAT64)"}]}]"""
      db.migrate(migrations)
      println("5. Migration: created 'orders' table")

      // Insert into the migrated table.
      db.sqlRows("INSERT INTO orders (id, user_id, total) VALUES (1, 1, 99.99)")
      db.sqlRows("INSERT INTO orders (id, user_id, total) VALUES (2, 2, 49.99)")

      // SQL JOIN across both tables.
      val joinResult = db.sqlRows(
        "SELECT u.name, o.total FROM users u " +
        "JOIN orders o ON u.id = o.user_id ORDER BY o.total DESC")
      println("6. SQL JOIN (users + orders):")
      println(s"   $joinResult")

      // Kit query builder: SELECT.
      val selectJson =
        """{"table":"users","columns":[],"filter":null,"order_by":[],"limit":null,"offset":null}"""
      val queryResult = db.querySelect(selectJson)
      println("7. Kit query builder SELECT:")
      println(s"   $queryResult")

      // Read back applied migrations.
      val applied = db.appliedMigrations()
      println(s"8. Applied migrations: $applied")

      println()
      println("=== All operations completed successfully! ===")
    finally
      db.close()
