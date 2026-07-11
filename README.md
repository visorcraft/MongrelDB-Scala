<p align="center">
  <img src="assets/mongrel.png" alt="MongrelDB logo" width="250" />
</p>

<h1 align="center">MongrelDB Scala Client</h1>

<p align="center">
  <b>Pure Scala 3 client for MongrelDB - embedded+server database with SQL, vector search, full-text search, and AI-native retrieval.</b>
  <br />
  No external runtime dependencies - built on the standard-library <code>java.net.http.HttpClient</code>. The API mirrors the MongrelDB Java, Kotlin, and Go clients.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/dev.visorcraft/mongreldb-scala"><img src="https://img.shields.io/maven-central/v/dev.visorcraft/mongreldb-scala.svg" alt="Maven Central" /></a>
  <a href="https://www.scala-lang.org/"><img src="https://img.shields.io/badge/Scala-3.3-DC322F.svg" alt="Scala" /></a>
  <a href="https://github.com/visorcraft/MongrelDB-Scala/actions/workflows/ci.yml"><img src="https://github.com/visorcraft/MongrelDB-Scala/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="#license"><img src="https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg" alt="License" /></a>
</p>

## Package

| Surface | Package | Coordinates |
|---|---|---|
| Scala client | `dev.visorcraft.mongreldb` | `dev.visorcraft %% mongreldb-scala` |

## Requirements

- **Scala 3.3+** on **Java 11+**
- A running [`mongreldb-server`](https://github.com/visorcraft/MongrelDB) daemon

## What It Provides

- **Typed CRUD** over the Kit transaction endpoint: `put`, `upsert` (insert-or-update on PK conflict), `delete` by row id or primary key, all with optional idempotency keys for safe retries.
- **Fluent query builder** that pushes conditions down to the engine's specialized indexes for sub-millisecond lookups: bitmap equality/IN, learned-range, null checks, FM-index full-text search, HNSW vector similarity (`ann`), and sparse vector match. Friendly aliases (`column` -> `column_id`, `min`/`max` -> `lo`/`hi`) are translated to the server's on-wire keys.
- **Idempotent batch transactions** - operations staged locally and committed atomically, with the engine enforcing unique, foreign-key, and check constraints at commit time. Idempotency keys return the original response on duplicate commits, even after a crash.
- **Full SQL access** through the DataFusion-backed `/sql` endpoint: recursive CTEs, window functions, `CREATE TABLE AS SELECT`, materialized views, and multi-statement execution. JSON mode decodes rows; `sqlArrow` requests raw Arrow IPC bytes.
- **Schema management**: typed table creation, full schema catalog, and per-table descriptors.
- **User/role/credentials management** via SQL: Argon2id-hashed catalog users, roles, and `GRANT`/`REVOKE` table-level permissions, all executed through `sql`.
- **Maintenance**: compaction (all tables or per-table).
- **Auth**: Bearer token (`--auth-token` mode) and HTTP Basic (`--auth-users` mode), with the bearer token taking precedence. Credentials are CRLF-validated to prevent header injection.
- **Typed exception hierarchy**: `MongrelDBException` (base), `AuthException` (401/403), `NotFoundException` (404), `ConflictException` (409, with code + op index), and `QueryException` (everything else, including network failures).
- **Robust JSON handling**: NaN and Infinity emit `null` instead of corrupting data; the `/sql` endpoint's Arrow IPC bodies are tolerated gracefully.
- **Response size limit** (256 MB) to guard client memory against a malicious or buggy server.

## Install

### sbt

```scala
libraryDependencies += "dev.visorcraft" %% "mongreldb-scala" % "0.1.0"
```

### scala-cli

```scala
//> using dep dev.visorcraft::mongreldb-scala:0.1.0
```

## Examples

Task-focused, commented guides live in [`docs/`](docs):

- [Quickstart](docs/quickstart.md) - install, start the daemon, write and run a complete program.
- [Transactions](docs/transactions.md) - batch commits, idempotency keys, constraint handling.
- [Queries](docs/queries.md) - every native condition type and the index it pushes down to.
- [SQL](docs/sql.md) - recursive CTEs, window functions, advanced SQL.
- [Authentication](docs/auth.md) - Bearer token, HTTP Basic, and open modes.
- [Errors](docs/errors.md) - the exception hierarchy and recovery patterns.

## Quick Example

```scala
import dev.visorcraft.mongreldb.MongrelDB

// Connect to a running mongreldb-server daemon.
val db = MongrelDB("http://127.0.0.1:8453")

// Create a table. Column ids are stable on-wire identifiers. Column maps also
// pass typed default_value scalars and dynamic default_expr ("now"/"uuid").
db.createTable("orders", List(
  Map("id" -> 1, "name" -> "id",       "ty" -> "int64",   "primary_key" -> true,  "nullable" -> false),
  Map("id" -> 2, "name" -> "customer", "ty" -> "varchar", "primary_key" -> false, "nullable" -> false),
  Map("id" -> 3, "name" -> "amount",   "ty" -> "float64", "primary_key" -> false, "nullable" -> false),
), constraints = Map(
  "checks" -> List(Map(
    "id" -> 1,
    "name" -> "ck_customer",
    "expr" -> Map("IsNotNull" -> 2),
  )),
))

// Insert rows (cells map column id -> value).
db.put("orders", Map(1L -> 1L, 2L -> "Alice", 3L -> 99.50))
db.put("orders", Map(1L -> 2L, 2L -> "Bob",   3L -> 150.00))

// Upsert (insert or update on PK conflict).
db.upsert("orders", Map(1L -> 1L, 2L -> "Alice", 3L -> 120.00), updateCells = Map(3L -> 120.00))

// Query with a native index condition.
val rows = db.query("orders")
  .where("range_f64", Map("column" -> 3L, "min" -> 100.0))
  .projection(List(1L, 2L))
  .limit(100L)
  .execute()

println(db.count("orders")) // 2

// Run SQL.
db.sql("UPDATE orders SET amount = 200.0 WHERE customer = 'Bob'")
```

## Authentication

```scala
// Bearer token (--auth-token mode)
val db1 = MongrelDB("http://127.0.0.1:8453", "my-secret-token")

// HTTP Basic (--auth-users mode)
val db2 = MongrelDB("http://127.0.0.1:8453", null, "admin", "s3cret")

// No args: daemon address defaults to 127.0.0.1:8453, no auth.
val db3 = MongrelDB()
```

## Batch transactions

Operations are staged locally and committed atomically. The engine enforces
unique, foreign-key, and check constraints at commit time.

```scala
val txn = db.begin()
txn.put("orders", Map(1L -> 10L, 2L -> "Dave", 3L -> 50.00))
txn.put("orders", Map(1L -> 11L, 2L -> "Eve",  3L -> 75.00))
txn.deleteByPk("orders", 2L)

try {
  val results = txn.commit(null) // atomic - all or nothing
  println(s"Staged ${txn.count} operations")
} catch {
  case e: ConflictException =>
    println(s"Constraint violated: ${e.code} - ${e.message}")
}
```

## SQL

```scala
db.sql("INSERT INTO orders (id, customer, amount) VALUES (99, 'Zoe', 999.0)")
db.sql("CREATE TABLE archive AS SELECT * FROM orders WHERE amount > 500")
```

## Error handling

Every non-2xx response is mapped to a typed exception. Catch the specific class
for the category, or `MongrelDBException` for any client failure.

```scala
try {
  db.put("orders", Map(1L -> 1L))
} catch {
  case e: ConflictException => println(s"Constraint: ${e.code}, op ${e.opIndex}")
  case e: AuthException     => println(s"Not authorized: ${e.message}")
  case e: NotFoundException => println(s"Not found: ${e.message}")
  case e: QueryException    => println(s"Query/server error: ${e.message}")
  case e: MongrelDBException => println(s"Error: ${e.message}")
}
```

## API reference

### `MongrelDB`

| Method | Description |
|--------|-------------|
| `MongrelDB(url)` / `MongrelDB(url, token)` / `MongrelDB(url, token, user, pass)` | Construct a client (`url` defaults to `http://127.0.0.1:8453`) |
| `health: Boolean` | Check daemon health |
| `tableNames: List[String]` | List table names |
| `createTable(name, columns, constraints = Map.empty): Long` | Create a table; returns the table id |
| `dropTable(name): Unit` | Drop a table |
| `count(table): Long` | Row count |
| `put(table, cells, idempotencyKey): Map` | Insert a row |
| `upsert(table, cells, updateCells, idempotencyKey): Map` | Upsert a row |
| `delete(table, rowId): Unit` | Delete by row id |
| `deleteByPk(table, pk): Unit` | Delete by primary key |
| `query(table): QueryBuilder` | Start a native query |
| `sql(sql): List[Map]` | Execute SQL (JSON mode) |
| `sqlArrow(sql): Array[Byte]` | Execute SQL requesting raw Arrow IPC |
| `schema: Map[String,Map]` | Full schema catalog |
| `schemaFor(table): Map` | Single-table descriptor |
| `compact(): Map` | Compact all tables |
| `compactTable(table): Map` | Compact one table |
| `begin(): Transaction` | Start a batch |

### `QueryBuilder`

| Method | Description |
|--------|-------------|
| `where(condType, params): this.type` | Add a native condition (AND-ed) |
| `projection(columnIds): this.type` | Set column projection |
| `limit(limit): this.type` | Set row limit |
| `build(): Map` | Build the request payload |
| `execute(): List[Map]` | Run the query |
| `truncated: Boolean` | Whether the last `execute` result hit the limit |

### `Transaction`

| Method | Description |
|--------|-------------|
| `put(table, cells, returning): this.type` | Stage an insert |
| `upsert(table, cells, updateCells, returning): this.type` | Stage an upsert |
| `delete(table, rowId): this.type` | Stage a delete by row id |
| `deleteByPk(table, pk): this.type` | Stage a delete by primary key |
| `count: Int` | Number of staged operations |
| `commit(idempotencyKey): List[Map]` | Commit atomically |
| `rollback(): Unit` | Discard all operations |

### Exceptions

| Class | HTTP status | Notes |
|-------|-------------|-------|
| `MongrelDBException` | - | Base class for all client errors |
| `AuthException` | 401, 403 | Bad or missing credentials |
| `NotFoundException` | 404 | Missing table, schema, or resource |
| `ConflictException` | 409 | Constraint violation; carries `code` and `opIndex` |
| `QueryException` | 400, 5xx, network | Everything else |

## Building and testing

The test suite uses munit. It is split into two layers:

- **Offline unit tests** - exception hierarchy, query-builder alias translation,
  cells flattening, and URL escaping. No daemon needed.
- **Live integration tests** - boots a real `mongreldb-server` daemon and
  exercises the full client surface (the 14-operation conformance matrix). Live
  tests are tagged and skip cleanly when no binary is available.

```sh
sbt compile
sbt test            # runs the whole suite (live tests skip without a daemon)
```

Fetch a prebuilt server binary from the [MongrelDB releases](https://github.com/visorcraft/MongrelDB/releases)
and place it at `./bin/mongreldb-server`, set `MONGRELDB_SERVER`, or install it
on `PATH`:

```sh
mkdir -p bin
curl -fsSL -o bin/mongreldb-server \
  https://github.com/visorcraft/MongrelDB/releases/download/v0.46.2/mongreldb-server-linux-x64
chmod +x bin/mongreldb-server
```

The live harness resolves the binary in this order: the `MONGRELDB_SERVER` env
var, `./bin/mongreldb-server`, `mongreldb-server` on `PATH`. Or point it at an
already-running daemon with `MONGRELDB_URL`.

## Contributing

Contributions are welcome. Please:

1. Open an issue first for non-trivial changes.
2. Add focused tests near your change - the suite must stay green.
3. Run `sbt test` before submitting.
4. Keep the client dependency-free (Java/Scala standard library only at runtime).

## History retention

Use `historyRetentionEpochs`, `setHistoryRetentionEpochs`, and `earliestRetainedEpoch` with MongrelDB 0.47.1+.

## License

Dual-licensed under the **MIT License** or the **Apache License, Version 2.0**,
at your option. See [MIT](LICENSE-MIT) OR [Apache-2.0](LICENSE-APACHE) for the full text.

`SPDX-License-Identifier: MIT OR Apache-2.0`
