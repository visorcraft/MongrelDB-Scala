package dev.visorcraft.mongreldb

import scala.collection.mutable.ListBuffer

/** Builds a request for the daemon's `/kit/query` endpoint, where conditions
  * push down to the engine's specialized indexes for sub-millisecond lookups.
  *
  * Condition parameters accept friendly aliases that are translated to the
  * server's exact on-wire keys before sending (see [[where]]):
  *
  *   - `column`        -> `column_id`
  *   - `min` / `max`   -> `lo` / `hi`
  *   - `min_inclusive` -> `lo_inclusive`
  *   - `max_inclusive` -> `hi_inclusive`
  *
  * The server's canonical keys are accepted directly too.
  *
  * Usage:
  * {{{
  * val rows = db.query("orders")
  *   .where("range", Map("column" -> 3L, "min" -> 100.0, "max" -> 150.0))
  *   .projection(List(1L, 2L))
  *   .limit(100)
  *   .execute
  * if builder.truncated then
  *   // result set hit the limit; more matches exist on the server
  * }}}
  */
final class QueryBuilder private[mongreldb] (client: MongrelDB, table: String):
  private val conditions: ListBuffer[Map[String, Any]] = ListBuffer.empty
  private var projectionList: List[Long] = null
  private var limitValue: java.lang.Long = null
  private var lastTruncated: Boolean = false

  /** Adds a native condition. Conditions are AND-ed together.
    *
    * Available condition types include `pk`, `bitmap_eq`, `bitmap_in`, `range`,
    * `range_f64`, `is_null`, `is_not_null`, `fm_contains`, `fm_contains_all`,
    * `ann`, `sparse_match`, and `min_hash_similar`.
    */
  def where(condType: String, params: Map[String, Any]): this.type =
    require(condType != null, "condType")
    require(params != null, "params")
    val entry: Map[String, Any] = Map(condType -> QueryBuilder.normalizeCondition(condType, params))
    conditions += entry
    this

  /** Sets the column ids to return. `null` (the default) means all columns. */
  def projection(columnIds: List[Long]): this.type =
    this.projectionList = columnIds
    this

  /** Caps the number of rows returned. */
  def limit(limit: Long): this.type =
    this.limitValue = limit
    this

  /** Builds the request payload that will be sent to `/kit/query`. */
  def build(): Map[String, Any] =
    var payload: Map[String, Any] = Map("table" -> table)
    if conditions.nonEmpty then payload = payload.updated("conditions", conditions.toList)
    if projectionList != null then payload = payload.updated("projection", projectionList)
    if limitValue != null then payload = payload.updated("limit", limitValue)
    payload

  /** Runs the query and returns the matching rows. Also records whether the
    * result was truncated by [[limit]]; check it with [[truncated]].
    */
  def execute(): List[Map[String, Any]] =
    val body = client.post("/kit/query", build())
    val parsed = if body.isEmpty then null else Json.parse(body)
    var rows: List[Map[String, Any]] = Nil
    var truncated = false
    parsed match
      case m: Map[String, Any] @unchecked =>
        m.get("rows") match
          case Some(rs: List[?] @unchecked) =>
            rows = rs.map {
              case r: Map[String, Any] @unchecked => r
              case _ => Map.empty[String, Any]
            }
          case _ =>
        m.get("truncated") match
          case Some(b: Boolean) => truncated = b
          case _ =>
      case _ =>
    this.lastTruncated = truncated
    rows

  /** Reports whether the most recent [[execute]] result was capped by the query
    * limit. Returns `false` until [[execute]] has been called.
    */
  def truncated: Boolean = lastTruncated

object QueryBuilder:
  /** Translates friendly parameter aliases to the server's canonical on-wire
    * keys. Both spellings are accepted. The `value` -> `pattern` alias applies
    * only to FTS conditions (`fm_contains`/`fm_contains_all`).
    */
  private[mongreldb] def normalizeCondition(condType: String, params: Map[String, Any]): Map[String, Any] =
    val normalized = Map.newBuilder[String, Any]
    params.foreach { (key, value) =>
      val canonical = key match
        case "column" => "column_id"
        case "min" => "lo"
        case "max" => "hi"
        case "min_inclusive" => "lo_inclusive"
        case "max_inclusive" => "hi_inclusive"
        case "value" =>
          if condType == "fm_contains" || condType == "fm_contains_all" then "pattern" else "value"
        case other => other
      normalized += (canonical -> value)
    }
    normalized.result()
