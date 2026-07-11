package dev.visorcraft.mongreldb

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import scala.jdk.CollectionConverters.*
import scala.util.boundary
import scala.util.boundary.break

/** The MongrelDB HTTP client.
  *
  * A pure-Scala 3 client for a running `mongreldb-server` daemon, built on the
  * standard-library `java.net.http.HttpClient` (Java 11+). No external
  * dependencies. The API mirrors the MongrelDB Java, Go, and PHP clients: typed
  * CRUD over the Kit transaction endpoint, a fluent query builder that pushes
  * conditions down to the engine's native indexes, idempotent batch
  * transactions, full SQL access, and schema introspection.
  *
  * Connect with a base URL:
  * {{{
  * val db = MongrelDB("http://127.0.0.1:8453")
  * val ok = db.health
  * }}}
  *
  * A `MongrelDB` instance is safe for concurrent use by multiple threads once
  * constructed: the underlying `HttpClient` is thread-safe and the instance is
  * immutable after configuration.
  */
final class MongrelDB private (
    val baseURL: String,
    token: String,
    username: String,
    password: String,
    http: HttpClient
):

  import MongrelDB.{MaxResponseBytes, urlPathEscape, stripLeadingSlash, decodeResults, firstResult,
                    flattenCells, trim, toException}

  // ── Health & tables ─────────────────────────────────────────────────────

  /** Reports whether the daemon is reachable and healthy. Returns `true` if the
    * daemon answered `/health` with a 2xx, `false` on any error.
    */
  def health: Boolean =
    try
      get("/health")
      true
    catch case _: MongrelDBException => false

  def historyRetentionEpochs: Long =
    historyRetention("GET", null)("history_retention_epochs").asInstanceOf[Number].longValue

  def earliestRetainedEpoch: Long =
    historyRetention("GET", null)("earliest_retained_epoch").asInstanceOf[Number].longValue

  def setHistoryRetentionEpochs(epochs: Long): Map[String, Any] =
    historyRetention("PUT", Map("history_retention_epochs" -> epochs))

  private def historyRetention(method: String, body: Any): Map[String, Any] =
    Json.parse(doRequest(method, "/history/retention", body)).asInstanceOf[Map[String, Any]]

  /** Lists all table names in the database. */
  def tableNames: List[String] =
    val body = get("/tables")
    if body.isEmpty then Nil
    else Json.parse(body) match
      case xs: List[?] @unchecked => xs.map(v => if v == null then null else String.valueOf(v))
      case other =>
        throw QueryException(s"mongreldb: unexpected table-list response: ${Json.preview(body)}")

  /** Creates a table named `name` with the given columns and returns the assigned
    * table id. Each column is a `Map[String, Any]` sent verbatim to the daemon.
    */
  def createTable(
      name: String,
      columns: List[Map[String, Any]],
      constraints: Map[String, Any] = Map.empty
  ): Long =
    require(name != null, "name")
    require(columns != null, "columns")
    require(constraints != null, "constraints")
    val payload = MongrelDB.createTablePayload(name, columns, constraints)
    val body = post("/kit/create_table", payload)
    val parsed = if body.isEmpty then null else Json.parse(body)
    parsed match
      case m: Map[String, Any] @unchecked =>
        m.get("table_id") match
          case Some(n: Number) => n.longValue()
          case _ => 0L
      case _ => 0L

  /** Drops a table by name. */
  def dropTable(name: String): Unit =
    require(name != null, "name")
    delete("/tables/" + urlPathEscape(name))

  /** Returns the row count for a table. */
  def count(table: String): Long =
    require(table != null, "table")
    val body = get("/tables/" + urlPathEscape(table) + "/count")
    val parsed = if body.isEmpty then null else Json.parse(body)
    parsed match
      case m: Map[String, Any] @unchecked =>
        m.get("count") match
          case Some(n: Number) => n.longValue()
          case _ => throw QueryException("mongreldb: malformed count response")
      case _ => throw QueryException("mongreldb: malformed count response")

  // ── CRUD (via the Kit typed transaction endpoint) ───────────────────────

  /** Inserts a row. `idempotencyKey`, when non-null and non-empty, makes the
    * commit safe to retry. `cells` is a column-id-to-value map, flattened to the
    * server's `[col_id, value, ...]` array before sending.
    */
  def put(table: String, cells: Map[Long, Any], idempotencyKey: String = null): Map[String, Any] =
    require(table != null, "table")
    require(cells != null, "cells")
    val op: Map[String, Any] = Map("put" -> Map("table" -> table, "cells" -> flattenCells(cells)))
    firstResult(commitOne(List(op), idempotencyKey))

  /** Inserts a row, or updates it on a primary-key conflict. `updateCells`, when
    * non-null, supplies the values written on conflict; `null` means DO NOTHING.
    */
  def upsert(table: String, cells: Map[Long, Any], updateCells: Map[Long, Any] = null,
             idempotencyKey: String = null): Map[String, Any] =
    require(table != null, "table")
    require(cells != null, "cells")
    var upsertOp: Map[String, Any] = Map("table" -> table, "cells" -> flattenCells(cells))
    if updateCells != null then upsertOp = upsertOp.updated("update_cells", flattenCells(updateCells))
    val op: Map[String, Any] = Map("upsert" -> upsertOp)
    firstResult(commitOne(List(op), idempotencyKey))

  /** Removes a row by its internal row id. */
  def delete(table: String, rowId: Long): Unit =
    require(table != null, "table")
    val op: Map[String, Any] = Map("delete" -> Map("table" -> table, "row_id" -> rowId))
    commitOne(List(op), null)

  /** Removes a row by its primary-key value. */
  def deleteByPk(table: String, pk: Any): Unit =
    require(table != null, "table")
    require(pk != null, "pk")
    val op: Map[String, Any] = Map("delete_by_pk" -> Map("table" -> table, "pk" -> pk))
    commitOne(List(op), null)

  /** Starts a fluent [[QueryBuilder]] against `table`. */
  def query(table: String): QueryBuilder =
    require(table != null, "table")
    new QueryBuilder(this, table)

  // ── SQL ─────────────────────────────────────────────────────────────────

  /** Executes a SQL statement via the `/sql` endpoint, requesting JSON output.
    * The server returns a JSON array of row objects keyed by column name. For
    * statements that yield no rows (DDL/DML), it returns an empty list. An old
    * server may ignore the requested JSON format and answer with Arrow IPC
    * binary bytes; that is treated as "no rows" rather than an error.
    */
  def sql(sql: String): List[Map[String, Any]] =
    require(sql != null, "sql")
    val payload: Map[String, Any] = Map("sql" -> sql, "format" -> "json")
    val body = post("/sql", payload)
    if trim(body).isEmpty then Nil
    else
      val parsed =
        try Json.parse(body)
        catch case _: QueryException => return Nil
      parsed match
        case rows: List[?] @unchecked =>
          rows.map {
            case m: Map[String, Any] @unchecked => m
            case _ => Map.empty[String, Any]
          }
        case _ => Nil

  /** Sends a SQL statement requesting raw Arrow IPC bytes, returning the body as
    * a byte array (if the server supports `format: "arrow"`).
    */
  def sqlArrow(sql: String): Array[Byte] =
    require(sql != null, "sql")
    val payload: Map[String, Any] = Map("sql" -> sql, "format" -> "arrow")
    post("/sql", payload)

  // ── Schema ──────────────────────────────────────────────────────────────

  /** Returns the full schema catalog: a table-name-to-descriptor map. */
  def schema: Map[String, Map[String, Any]] =
    val body = get("/kit/schema")
    val parsed = if body.isEmpty then null else Json.parse(body)
    parsed match
      case m: Map[String, Any] @unchecked =>
        m.get("tables") match
          case Some(t: Map[String, Any] @unchecked) =>
            t.map { case (k, v) =>
              k -> (v match { case vm: Map[String, Any] @unchecked => vm; case _ => Map.empty[String, Any] })
            }.toMap
          case _ => Map.empty[String, Map[String, Any]]
      case _ => Map.empty[String, Map[String, Any]]

  /** Returns the descriptor for a single table. */
  def schemaFor(table: String): Map[String, Any] =
    require(table != null, "table")
    val body = get("/kit/schema/" + urlPathEscape(table))
    val parsed = if body.isEmpty then null else Json.parse(body)
    parsed match
      case m: Map[String, Any] @unchecked => m
      case _ => Map.empty[String, Any]

  // ── Maintenance ─────────────────────────────────────────────────────────

  /** Merges sorted runs across all tables. */
  def compact(): Map[String, Any] = postDecode("/compact")

  /** Merges sorted runs for a single table. */
  def compactTable(table: String): Map[String, Any] =
    require(table != null, "table")
    postDecode("/tables/" + urlPathEscape(table) + "/compact")

  private def postDecode(path: String): Map[String, Any] =
    val body = post(path, null)
    val parsed = if body.isEmpty then null else Json.parse(body)
    parsed match
      case m: Map[String, Any] @unchecked => m
      case _ => Map.empty[String, Any]

  // ── Transactions ────────────────────────────────────────────────────────

  /** Starts a new batch transaction. Operations staged on the returned
    * [[Transaction]] are committed atomically in a single `/kit/txn` request.
    */
  def begin(): Transaction = new Transaction(this)

  /** Sends a batch of staged operations atomically. Exposed for the
    * `Transaction` type; returns the per-operation results array.
    */
  private[mongreldb] def commitTxn(ops: List[Map[String, Any]], idempotencyKey: String): List[Map[String, Any]] =
    if ops.isEmpty then Nil
    else
      var payload: Map[String, Any] = Map("ops" -> ops)
      if idempotencyKey != null && idempotencyKey.nonEmpty then
        payload = payload.updated("idempotency_key", idempotencyKey)
      decodeResults(post("/kit/txn", payload))

  private def commitOne(ops: List[Map[String, Any]], idempotencyKey: String): List[Map[String, Any]] =
    var payload: Map[String, Any] = Map("ops" -> ops)
    if idempotencyKey != null && idempotencyKey.nonEmpty then
      payload = payload.updated("idempotency_key", idempotencyKey)
    decodeResults(post("/kit/txn", payload))

  // ── HTTP plumbing ───────────────────────────────────────────────────────

  private[mongreldb] def get(path: String): Array[Byte] = doRequest("GET", path, null)
  private[mongreldb] def post(path: String, body: Any): Array[Byte] = doRequest("POST", path, body)
  private def delete(path: String): Unit = doRequest("DELETE", path, null)

  private def doRequest(method: String, path: String, body: Any): Array[Byte] =
    validateNoCRLF(path)
    var publisher = HttpRequest.BodyPublishers.noBody()
    var payloadBytes: Array[Byte] = null
    if body != null then
      payloadBytes = Json.toBytes(body)
      publisher = HttpRequest.BodyPublishers.ofByteArray(payloadBytes)

    val reqBuilder = HttpRequest.newBuilder()
      .uri(URI.create(baseURL + "/" + stripLeadingSlash(path)))
      .header("Accept", "application/json")
      .timeout(Duration.ofSeconds(30))
      .method(method, publisher)
    if payloadBytes != null then reqBuilder.header("Content-Type", "application/json")
    applyAuth(reqBuilder)

    val resp =
      try http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
      catch
        case e: java.io.IOException =>
          throw QueryException(s"mongreldb: request $method $path failed: ${e.getMessage}", e)
        case e: InterruptedException =>
          Thread.currentThread().interrupt()
          throw QueryException(s"mongreldb: request $method $path interrupted", e)

    val data = if resp.body() == null then Array.emptyByteArray else resp.body()
    if data.length > MaxResponseBytes then
      throw QueryException(s"mongreldb: response body exceeds maximum size of $MaxResponseBytes bytes")
    val status = resp.statusCode()
    if status < 200 || status >= 300 then throw toException(status, data)
    data

  // Reject CR or LF in the request path so a malicious table name cannot inject
  // header smuggling. (Values in the JSON body are safe; this only guards the URL.)
  private def validateNoCRLF(path: String): Unit =
    if path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0 then
      throw QueryException(s"mongreldb: illegal CR/LF in request path: $path")

  // Apply the Authorization header. A bearer token takes precedence over basic.
  private def applyAuth(req: HttpRequest.Builder): Unit =
    if token != null && token.nonEmpty then
      validateNoCRLF(token)
      req.header("Authorization", "Bearer " + token)
    else if username != null && username.nonEmpty then
      validateNoCRLF(username)
      validateNoCRLF(password)
      val creds = username + ":" + (if password == null then "" else password)
      val encoded = Base64.getEncoder.encodeToString(creds.getBytes(StandardCharsets.UTF_8))
      req.header("Authorization", "Basic " + encoded)

  // ── HTTP helpers ────────────────────────────────────────────────────────

object MongrelDB:
  /** The daemon address used when none is supplied. */
  final val DefaultBaseURL = "http://127.0.0.1:8453"

  /** Maximum response body size (256 MB). Bodies larger than this are aborted
    * with a [[QueryException]] to guard client memory.
    */
  final val MaxResponseBytes = 268435456

  /** Constructs a client for the daemon at `url` with no authentication. */
  def apply(url: String = DefaultBaseURL): MongrelDB =
    new MongrelDB(normalizeBase(url), null, null, null, defaultHttpClient())

  /** Constructs a client with a bearer token (`--auth-token` mode). */
  def apply(url: String, token: String): MongrelDB =
    new MongrelDB(normalizeBase(url), token, null, null, defaultHttpClient())

  /** Constructs a client with optional auth (token takes precedence over basic). */
  def apply(url: String, token: String, username: String, password: String): MongrelDB =
    new MongrelDB(normalizeBase(url), token, username, password, defaultHttpClient())

  /** Constructs a client with full control over the transport. */
  def apply(url: String, token: String, username: String, password: String, http: HttpClient): MongrelDB =
    new MongrelDB(normalizeBase(url), token, username, password, http)

  private def normalizeBase(url: String): String =
    val base = if url == null || url.isEmpty then DefaultBaseURL else url
    var b = base
    while b.endsWith("/") do b = b.substring(0, b.length - 1)
    b

  private def defaultHttpClient(): HttpClient =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

  private[mongreldb] def createTablePayload(
      name: String,
      columns: List[Map[String, Any]],
      constraints: Map[String, Any]
  ): Map[String, Any] =
    val payload = Map[String, Any]("name" -> name, "columns" -> columns)
    if constraints.isEmpty then payload else payload.updated("constraints", constraints)

  // ── Shared decode helpers (used by Transaction/QueryBuilder too) ─────────

  /** Flattens a column-id-to-value map to the server's flat
    * `[col_id, value, ...]` array. Pair order is not significant.
    */
  private[mongreldb] def flattenCells(cells: Map[Long, Any]): List[Any] =
    cells.flatMap { case (k, v) => List(k, v) }.toList

  private[mongreldb] def decodeResults(body: Array[Byte]): List[Map[String, Any]] =
    if trim(body).isEmpty then Nil
    else
      Json.parse(body) match
        case m: Map[String, Any] @unchecked =>
          m.get("results") match
            case Some(rs: List[?] @unchecked) =>
              rs.map {
                case r: Map[String, Any] @unchecked => r
                case _ => Map.empty[String, Any]
              }
            case _ => Nil
        case other =>
          throw QueryException("mongreldb: decode txn response: unexpected JSON")

  private[mongreldb] def firstResult(results: List[Map[String, Any]]): Map[String, Any] =
    results.headOption.getOrElse(Map.empty[String, Any])

  // Percent-encode a path segment so table names containing '/', '?', '#', or
  // spaces cannot inject extra segments. Only RFC 3986 unreserved characters pass
  // through unescaped; '/' is encoded as %2F.
  private[mongreldb] def urlPathEscape(seg: String): String =
    val sb = new StringBuilder(seg.length)
    var i = 0
    while i < seg.length do
      val c = seg.charAt(i)
      if c == '-' || c == '_' || c == '.' || c == '~'
        || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') then
        sb.append(c)
      else
        for b <- String.valueOf(c).getBytes(StandardCharsets.UTF_8) do
          sb.append('%')
          sb.append(hexChar((b >> 4) & 0x0f))
          sb.append(hexChar(b & 0x0f))
      i += 1
    sb.toString

  private def hexChar(n: Int): Char =
    (if n < 10 then '0' + n else 'A' + (n - 10)).toChar

  private def stripLeadingSlash(s: String): String =
    var r = s
    while r.startsWith("/") do r = r.substring(1)
    r

  private def trim(b: Array[Byte]): Array[Byte] =
    var s = 0
    var e = b.length
    while s < e && isSpace(b(s)) do s += 1
    while e > s && isSpace(b(e - 1)) do e -= 1
    if s == 0 && e == b.length then b
    else
      val out = new Array[Byte](e - s)
      System.arraycopy(b, s, out, 0, e - s)
      out

  private def isSpace(b: Byte): Boolean = b == ' ' || b == '\t' || b == '\n' || b == '\r'

  // Map an HTTP status code + body to a typed exception. Best-effort decodes the
  // server's JSON error envelope ({error:{message,code,op_index}}) and falls back
  // to the raw body.
  private def toException(status: Int, body: Array[Byte]): MongrelDBException =
    var message: String = null
    var code: String = null
    var opIndex: Integer = null
    val trimmed = trim(body)
    if trimmed.nonEmpty && trimmed(0) == '{' then
      try
        Json.parse(body) match
          case obj: Map[String, Any] @unchecked =>
            obj.get("error") match
              case Some(errMap: Map[String, Any] @unchecked) =>
                message = strOrNull(errMap.get("message"))
                code = strOrNull(errMap.get("code"))
                errMap.get("op_index") match
                  case Some(n: Number) => opIndex = n.intValue()
                  case _ =>
              case _ =>
            if message == null && code == null && opIndex == null then
              message = strOrNull(obj.get("message"))
              code = strOrNull(obj.get("code"))
          case _ =>
      catch case _: Exception => ()
    if message == null && body.nonEmpty then message = new String(body, StandardCharsets.UTF_8)

    if message == null || message.isEmpty then
      message = status match
        case 401 | 403 => s"authentication failed ($status)"
        case 404 => "resource not found"
        case 409 => "constraint violation"
        case _ => s"server error ($status)"

    if message.toLowerCase(java.util.Locale.ROOT).startsWith("not found:") then
      return new NotFoundException(message, 404, code, opIndex)

    status match
      case 401 | 403 => new AuthException(message, status, code, opIndex)
      case 404 => new NotFoundException(message, status, code, opIndex)
      case 409 => new ConflictException(message, status, code, opIndex)
      case _ => new QueryException(message, status, code, opIndex, null)

  private def strOrNull(o: Option[Any]): String = o match
    case Some(v) if v != null => String.valueOf(v)
    case _ => null
end MongrelDB
