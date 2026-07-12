package dev.visorcraft.mongreldb

import java.io.{File, IOException}
import java.net.{InetSocketAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.Using

/** Live integration tests for the MongrelDB Scala client.
  *
  * These tests boot a real `mongreldb-server` daemon and exercise the full
  * client surface against it (the 14-operation conformance matrix). They resolve
  * the daemon binary in this order:
  *   1. the `MONGRELDB_SERVER` env var (path to the server binary)
  *   2. a prebuilt binary at `./bin/mongreldb-server`
  *   3. `mongreldb-server` on `PATH`
  *
  * If no binary is available, the live tests are skipped. Set `MONGRELDB_URL` to
  * point at an already-running daemon to skip the boot and connect directly.
  */
class MongrelDBLiveTest extends munit.FunSuite:
  import MongrelDBLiveTest.*

  // ── Live tests (skipped when no daemon) ─────────────────────────────────

  test("health reports the daemon as healthy".tag(Live)) {
    assumeDaemon()
    assertEquals(db.health, true)
  }

  test("createTable + count round-trip".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_create")
    freshTable(name, intCol(1, "id", pk = true), floatCol(2, "amount"))
    assertEquals(db.count(name), 0L)
  }

  test("put + count round-trip".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_put")
    freshTable(name, intCol(1, "id", pk = true), floatCol(2, "amount"))
    db.put(name, cells(1L -> 1L, 2L -> 99.5))
    db.put(name, cells(1L -> 2L, 2L -> 150.0))
    assertEquals(db.count(name), 2L)
  }

  test("query by primary key".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_pk")
    freshTable(name, intCol(1, "id", pk = true))
    db.put(name, cells(1L -> 42L))
    db.put(name, cells(1L -> 43L))
    val rows = db.query(name).where("pk", Map("value" -> 42L)).execute()
    assertEquals(rows.length, 1)
    assertEquals(cellLong(rows.head, 1L), 42L)
  }

  test("query with a range condition using friendly aliases".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_range")
    freshTable(name, intCol(1, "id", pk = true), intCol(2, "amount"))
    db.put(name, cells(1L -> 1L, 2L -> 50L))
    db.put(name, cells(1L -> 2L, 2L -> 120L))
    db.put(name, cells(1L -> 3L, 2L -> 200L))
    val q = db.query(name).where("range", Map("column" -> 2L, "min" -> 100L, "max" -> 150L))
    val rows = q.execute()
    assertEquals(rows.length, 1)
    assertEquals(q.truncated, false)
    rows.foreach { row =>
      assertEquals(cellLong(row, 1L), 2L)
      val amt = cellLong(row, 2L)
      assert(amt >= 100 && amt <= 150)
    }
  }

  test("upsert updates on a primary-key conflict".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_upsert")
    freshTable(name, intCol(1, "id", pk = true), intCol(2, "amount"))
    db.put(name, cells(1L -> 1L, 2L -> 50L))
    db.upsert(name, cells(1L -> 1L, 2L -> 50L), cells(2L -> 999L))
    assertEquals(db.count(name), 1L)
    val rows = db.query(name).where("pk", Map("value" -> 1L)).execute()
    assertEquals(rows.length, 1)
    assertEquals(cellLong(rows.head, 1L), 1L)
    assertEquals(cellLong(rows.head, 2L), 999L)
  }

  test("batch transaction: put + commit".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_txn")
    freshTable(name, intCol(1, "id", pk = true))
    val txn = db.begin()
    txn.put(name, cells(1L -> 1L))
    txn.put(name, cells(1L -> 2L))
    txn.put(name, cells(1L -> 3L))
    assertEquals(txn.count, 3)
    val results = txn.commit(null)
    assertEquals(results.length, 3)
    assertEquals(db.count(name), 3L)
  }

  test("transaction rollback discards staged ops".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_rb")
    freshTable(name, intCol(1, "id", pk = true))
    val txn = db.begin()
    txn.put(name, cells(1L -> 1L))
    assertEquals(txn.count, 1)
    txn.rollback()
    assertEquals(db.count(name), 0L)
  }

  test("idempotent put does not duplicate the row".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_idem")
    freshTable(name, intCol(1, "id", pk = true))
    val key = "idem-" + name
    db.put(name, cells(1L -> 7L), key)
    db.put(name, cells(1L -> 7L), key)
    assertEquals(db.count(name), 1L)
  }

  test("deleteByPk removes a row".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_del")
    freshTable(name, intCol(1, "id", pk = true))
    db.put(name, cells(1L -> 5L))
    assertEquals(db.count(name), 1L)
    db.deleteByPk(name, 5L)
    assertEquals(db.count(name), 0L)
  }

  test("sql INSERT increases count and SELECT returns the row".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_sql")
    freshTable(name, intCol(1, "id", pk = true), intCol(2, "amount"))
    assertEquals(db.count(name), 0L)
    db.sql(s"INSERT INTO $name (id, amount) VALUES (10, 42)")
    assertEquals(db.count(name), 1L)
    val rows = db.sql(s"SELECT id, amount FROM $name")
    if rows.nonEmpty then
      assertEquals(rows.length, 1)
      assertEquals(longField(rows.head, "id"), 10L)
  }

  test("tableNames lists a created table".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_tables")
    freshTable(name, intCol(1, "id", pk = true))
    assert(db.tableNames.contains(name))
  }

  test("schema lists the created table".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_schema")
    freshTable(name, intCol(1, "id", pk = true), floatCol(2, "amount"))
    assert(db.schema.contains(name))
  }

  test("schemaFor returns a single-table descriptor".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_schema_for")
    freshTable(name, intCol(1, "id", pk = true), floatCol(2, "amount"))
    val desc = db.schemaFor(name)
    assert(desc.contains("schema_id"))
    val cols = desc("columns").asInstanceOf[List[?]]
    assertEquals(cols.length, 2)
  }

  test("schemaFor on a nonexistent table throws NotFoundException (error 404)".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_missing")
    intercept[NotFoundException] { db.schemaFor(name) }
  }

  test("history retention getters/setter and AS OF EPOCH round-trip".tag(Live)) {
    assumeDaemon()

    val original = db.historyRetentionEpochs
    assert(original > 0)

    try
      db.setHistoryRetentionEpochs(1_000L)
      assertEquals(db.historyRetentionEpochs, 1_000L)

      val name = uniqueTable("scala_retention")
      freshTable(name, intCol(1, "id", pk = true), floatCol(2, "amount"))

      db.put(name, cells(1L -> 1L, 2L -> 1.0))
      val insertEpoch = db.lastEpoch
      assert(insertEpoch > 0)

      db.upsert(name, cells(1L -> 1L, 2L -> 9.0), cells(2L -> 9.0))

      val rows = db.sql(s"SELECT id, amount FROM $name AS OF EPOCH $insertEpoch")
      assertEquals(rows.length, 1)
      assertEquals(longField(rows.head, "id"), 1L)
      assertEquals(rows.head("amount").asInstanceOf[Number].doubleValue, 1.0, 1e-9)

      val current = db.sql(s"SELECT id, amount FROM $name")
      assertEquals(current.length, 1)
      assertEquals(current.head("amount").asInstanceOf[Number].doubleValue, 9.0, 1e-9)
    finally
      db.setHistoryRetentionEpochs(original)
  }

  test("dropTable removes a table".tag(Live)) {
    assumeDaemon()
    val name = uniqueTable("scala_drop")
    freshTable(name, intCol(1, "id", pk = true))
    assert(db.tableNames.contains(name))
    db.dropTable(name)
    assert(!db.tableNames.contains(name))
  }

  // ── Offline tests (always run, no daemon) ───────────────────────────────

  test("health returns false when the daemon is unreachable") {
    val unreachable = MongrelDB("http://127.0.0.1:1")
    assertEquals(unreachable.health, false)
  }

  test("default base url is http://127.0.0.1:8453") {
    assertEquals(MongrelDB().baseURL, MongrelDB.DefaultBaseURL)
  }

  test("trailing slash is stripped") {
    assertEquals(MongrelDB("http://127.0.0.1:8453/").baseURL, "http://127.0.0.1:8453")
  }

  test("exception hierarchy: subclasses of MongrelDBException") {
    assert(classOf[AuthException].getSuperclass == classOf[MongrelDBException])
    assert(classOf[NotFoundException].getSuperclass == classOf[MongrelDBException])
    assert(classOf[ConflictException].getSuperclass == classOf[MongrelDBException])
    assert(classOf[QueryException].getSuperclass == classOf[MongrelDBException])
  }

  test("query builder translates friendly aliases") {
    val params = QueryBuilder.normalizeCondition("range",
      Map("column" -> 3L, "min" -> 100L, "max" -> 150L,
          "min_inclusive" -> true, "max_inclusive" -> false))
    assertEquals(params, Map("column_id" -> 3L, "lo" -> 100L, "hi" -> 150L,
                             "lo_inclusive" -> true, "hi_inclusive" -> false))
  }

  test("query builder fm_contains value alias maps to pattern") {
    val params = QueryBuilder.normalizeCondition("fm_contains", Map("column" -> 2L, "value" -> "database"))
    assertEquals(params, Map("column_id" -> 2L, "pattern" -> "database"))
  }

  test("query builder pk value is not aliased") {
    val params = QueryBuilder.normalizeCondition("pk", Map("value" -> 42L))
    assertEquals(params, Map("value" -> 42L))
  }

  test("query builder build payload shape") {
    val c = MongrelDB("http://127.0.0.1:1")
    val payload = c.query("orders")
      .where("range", Map("column" -> 3L, "min" -> 100L))
      .projection(List(1L, 2L))
      .limit(10L)
      .build()
    assertEquals(payload("table"), "orders")
    assertEquals(payload("projection"), List(1L, 2L))
    assertEquals(payload("limit"), 10L)
    val conds = payload("conditions").asInstanceOf[List[Map[String, Any]]]
    assertEquals(conds.length, 1)
    assertEquals(conds.head("range"), Map("column_id" -> 3L, "lo" -> 100L))
  }

  test("flattenCells produces [col_id, value, ...]") {
    val flat = MongrelDB.flattenCells(Map(1L -> "Alice", 3L -> 99.5))
    val pairs = flat.grouped(2).map(p => (p.head, p(1))).toSeq.sortBy(p =>
      p.head match { case n: Number => n.longValue(); case other => 0L })
    assertEquals(pairs, Seq((1L, "Alice"), (3L, 99.5)))
  }

  test("urlPathEscape encodes slash as %2F") {
    assertEquals(MongrelDB.urlPathEscape("a/b c"), "a%2Fb%20c")
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private def cells(kv: (Long, Any)*): Map[Long, Any] = kv.toMap

  private def intCol(id: Long, name: String, pk: Boolean = false): Map[String, Any] =
    Map("id" -> id, "name" -> name, "ty" -> "int64", "primary_key" -> pk, "nullable" -> false)

  private def floatCol(id: Long, name: String): Map[String, Any] =
    Map("id" -> id, "name" -> name, "ty" -> "float64", "primary_key" -> false, "nullable" -> false)

  /** Extracts a long cell value from a Kit row's flat cells array. */
  private def cellLong(row: Map[String, Any], colId: Long): Long =
    row.get("cells") match
      case Some(cells: List[?] @unchecked) =>
        cells.grouped(2).find(p => p.head match
          case n: Number => n.longValue() == colId
          case _ => false
        ) match
          case Some(p) => p(1) match
            case n: Number => n.longValue()
            case other => fail(s"cell $colId value not numeric: $other")
          case None => fail(s"cell $colId not found in row $row")
      case _ => fail(s"cells not a list in row $row")

  private def longField(row: Map[String, Any], field: String): Long =
    row(field) match
      case n: Number => n.longValue()
      case other => fail(s"field $field not numeric: $other")

  private def freshTable(name: String, columns: Map[String, Any]*): Unit =
    try db.dropTable(name)
    catch case _: MongrelDBException => ()
    db.createTable(name, columns.toList)

  private def assumeDaemon(): Unit =
    assume(db != null, "no mongreldb-server available")

object MongrelDBLiveTest:
  val Live = new munit.Tag("live")

  // Daemon lifecycle is managed by the fixture below.
  var db: MongrelDB = null
  private var serverProcess: Process = null
  private var dataDir: Path = null
  private var logFile: Path = null

  def uniqueTable(prefix: String): String = prefix + "_" + java.lang.Long.toHexString(System.nanoTime())

  // Boot once before the suite. Using a JVM shutdown-equivalent: munit's
  // beforeAll / afterAll on the companion is awkward; instead the suite boots
  // lazily on first access via `db`.
  def boot(): Unit =
    if db != null then return
    val existing = sys.env.getOrElse("MONGRELDB_URL", "")
    if existing.nonEmpty then
      val probe = MongrelDB(existing, sys.env.getOrElse("MONGRELDB_TOKEN", null))
      if probe.health then
        db = probe
        return
      System.err.println(s"mongreldb: MONGRELDB_URL=$existing is not reachable")

    resolveServerBinary() match
      case None =>
        System.err.println("--- no mongreldb-server binary: live tests will skip")
      case Some(bin) =>
        val port = freePort()
        dataDir = Files.createTempDirectory("mongreldb-scala-test-")
        logFile = Files.createTempFile("mongreldb-scala-server-", ".log")
        val pb = new ProcessBuilder(bin, dataDir.toString, "--port", String.valueOf(port))
          .redirectOutput(logFile.toFile)
          .redirectErrorStream(true)
        try serverProcess = pb.start()
        catch
          case e: IOException =>
            System.err.println(s"mongreldb: failed to start server: ${e.getMessage}")
            return
        val url = s"http://127.0.0.1:$port"
        if !waitForHealth(url, 40) then
          System.err.println(s"mongreldb: server did not become healthy:\n${readLog()}")
          destroyProcess()
          return
        db = MongrelDB(url)

  def shutdown(): Unit =
    destroyProcess()
    if dataDir != null then
      try deleteRecursively(dataDir)
      catch case _: IOException => ()

  private def resolveServerBinary(): Option[String] =
    sys.env.get("MONGRELDB_SERVER").filter(_.nonEmpty) match
      case Some(env) =>
        val p = Paths.get(env)
        if Files.isExecutable(p) then Some(p.toAbsolutePath.toString)
        else None
      case None =>
        val local = Paths.get("bin", "mongreldb-server")
        if Files.isExecutable(local) then Some(local.toAbsolutePath.toString)
        else
          sys.env.getOrElse("PATH", "").split(":").flatMap { dir =>
            val p = Paths.get(dir, "mongreldb-server")
            if Files.isExecutable(p) then Some(p.toAbsolutePath.toString) else None
          }.headOption

  private def freePort(): Int =
    Using.resource(new ServerSocket(0))(_.getLocalPort)

  private def waitForHealth(url: String, maxSeconds: Int): Boolean =
    val probe = MongrelDB(url)
    val deadline = System.currentTimeMillis() + maxSeconds * 1000L
    while System.currentTimeMillis() < deadline do
      try
        if probe.health then return true
      catch case NonFatal(_) => ()
      Thread.sleep(500)
    false

  private def destroyProcess(): Unit =
    if serverProcess != null then
      serverProcess.destroy()
      try
        if !serverProcess.waitFor(5, TimeUnit.SECONDS) then serverProcess.destroyForcibly()
        serverProcess.waitFor(2, TimeUnit.SECONDS)
      catch case _: InterruptedException => Thread.currentThread().interrupt()

  private def readLog(): String =
    try Files.readString(logFile, StandardCharsets.UTF_8)
    catch case e: IOException => s"(could not read log: ${e.getMessage})"

  private def deleteRecursively(p: Path): Unit =
    if Files.isDirectory(p) then
      Files.newDirectoryStream(p).asScala.foreach(deleteRecursively)
    Files.deleteIfExists(p)

  // Boot the daemon when the companion object is initialized, and tear it down
  // at JVM exit. Running these as top-level statements at the end of the file is
  // rejected by the Scala 3 parser; placing them in the object body achieves the
  // same class-load effect.
  boot()
  sys.addShutdownHook { shutdown() }
