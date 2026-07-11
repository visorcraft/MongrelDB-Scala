package dev.visorcraft.mongreldb

import dev.visorcraft.mongreldb.native_mode.NativeDB
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterAll
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

class NativeDBTest extends AnyFunSuite {

  private val SchemaJson = """{
    "tables": [{
      "id": 1,
      "name": "users",
      "columns": [
        {"id":1,"name":"id","storage_type":"int64","application_type":"int64","nullable":false,"primary_key":true,"default":null,"generated":false},
        {"id":2,"name":"name","storage_type":"text","application_type":"text","nullable":true,"primary_key":false,"default":null,"generated":false}
      ],
      "primary_key": ["id"]
    }]
  }"""

  private def nativeAvailable: Boolean =
    try
      Class.forName("dev.visorcraft.mongreldb.native_mode.NativeDB")
      // Trigger the static initializer which loads the native lib.
      NativeDB.create("/tmp/_mdb_native_check_unused", SchemaJson)
      true
    catch
      case _: UnsatisfiedLinkError => false
      case _: Throwable => false

  private def withTempDir[A](f: Path => A): A =
    val dir = Files.createTempDirectory("mdb_native_test")
    try f(dir)
    finally
      try Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      catch case _: Throwable => ()

  if !nativeAvailable then
    test("native library not available - skipping") {
      cancel("libmongreldb_jni not loadable")
    }
  else
    test("create and SQL insert/select") {
      withTempDir { dir =>
        val db = NativeDB.create(dir.toString, SchemaJson)
        try
          db.sqlRows("INSERT INTO users (id, name) VALUES (1, 'alice')")
          val rows = db.sqlRows("SELECT id, name FROM users")
          assert(rows.contains("alice"))
        finally db.close()
      }
    }

    test("SQL Arrow returns Arrow magic") {
      withTempDir { dir =>
        val db = NativeDB.create(dir.toString, SchemaJson)
        try
          db.sqlRows("INSERT INTO users (id, name) VALUES (1, 'bob')")
          val arrow = db.sqlArrow("SELECT id FROM users")
          assert(arrow.length >= 6)
          assert(new String(arrow, 0, 6) == "ARROW1")
        finally db.close()
      }
    }

    test("migrate creates table and reads back") {
      withTempDir { dir =>
        val db = NativeDB.create(dir.toString, SchemaJson)
        try
          val migrations = """[{"version":1,"name":"add_orders","ops":[{"raw_sql":"CREATE TABLE orders (id INT64 PRIMARY KEY, total FLOAT64)"}]}]"""
          db.migrate(migrations)
          db.sqlRows("INSERT INTO orders (id, total) VALUES (1, 99.99)")
          val applied = db.appliedMigrations()
          assert(applied.contains("add_orders"))
        finally db.close()
      }
    }

    test("query select returns rows") {
      withTempDir { dir =>
        val db = NativeDB.create(dir.toString, SchemaJson)
        try
          db.sqlRows("INSERT INTO users (id, name) VALUES (1, 'carol')")
          val selectJson = """{"table":"users","columns":[],"filter":null,"order_by":[],"limit":null,"offset":null}"""
          val result = db.querySelect(selectJson)
          assert(result.contains("carol"))
        finally db.close()
      }
    }
}
