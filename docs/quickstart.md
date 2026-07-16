# Quickstart

Column maps pass schema keys through unchanged. `default_value` accepts any
JSON scalar; supply the type expected by the column. Use `default_expr ->
"now"` or `"uuid"` for a dynamic default.

Zero to a running MongrelDB Scala program. This guide assumes a fresh machine
and walks through installing the prerequisites, starting the daemon, and writing
a complete program.

---

## 1. Prerequisites

You need the Scala/Java toolchain and a `mongreldb-server` daemon.

### Install Java 11+ and sbt

```sh
java -version    # 11 or newer
sbt sbtVersion   # sbt 1.10+
```

If you do not have them, install from your package manager or
<https://www.scala-lang.org/download/>.

### Install mongreldb-server

Fetch a prebuilt server binary from the
[MongrelDB releases](https://github.com/visorcraft/MongrelDB/releases):

```sh
mkdir -p bin
curl -fsSL -o bin/mongreldb-server \
  https://github.com/visorcraft/MongrelDB/releases/download/v0.57.0/mongreldb-server-linux-x64
chmod +x bin/mongreldb-server
```

## 2. Start the daemon

By default `mongreldb-server` listens on `http://127.0.0.1:8453`.

```sh
mkdir -p /tmp/mdb-data && cd /tmp/mdb-data
/path/to/mongreldb-server
```

Sanity-check it:

```sh
curl http://127.0.0.1:8453/health
# ok
```

## 3. Create a project and pull in the client

### sbt

```scala
// build.sbt
scalaVersion := "3.3.3"
libraryDependencies += "com.visorcraft" %% "mongreldb-scala" % "0.57.0"
```

### scala-cli

```scala
//> using scala 3.3.3
//> using dep com.visorcraft::mongreldb-scala:0.57.0
```

## 4. Write your first program

```scala
import com.visorcraft.mongreldb.MongrelDB

object Demo:
  def main(args: Array[String]): Unit =
    val db = MongrelDB("http://127.0.0.1:8453")

    if !db.health then
      System.err.println("daemon not reachable")
      sys.exit(1)

    val tid = db.createTable("orders", List(
      Map("id" -> 1, "name" -> "id",       "ty" -> "int64",   "primary_key" -> true,  "nullable" -> false),
      Map("id" -> 2, "name" -> "customer", "ty" -> "varchar", "primary_key" -> false, "nullable" -> false),
      Map("id" -> 3, "name" -> "amount",   "ty" -> "float64", "primary_key" -> false, "nullable" -> false),
    ))
    println(s"created table id: $tid")

    db.put("orders", Map(1L -> 1L, 2L -> "Alice", 3L -> 99.5))
    db.put("orders", Map(1L -> 2L, 2L -> "Bob",   3L -> 150.0))

    val rows = db.query("orders")
      .where("range", Map("column" -> 3L, "min" -> 100L))
      .projection(List(1L, 2L))
      .limit(100L)
      .execute()
    rows.foreach(println)

    println(s"total rows: ${db.count("orders")}")
```

Run it:

```sh
scala-cli run demo.scala
```

## 5. What each part does

| Code | What it does |
|------|--------------|
| `MongrelDB(url)` | Builds an HTTP client targeting one daemon. Safe to share across threads. |
| `db.health` | GET `/health`; returns `true` when the daemon answers. |
| `db.createTable(name, cols)` | POST `/kit/create_table`. Column `id`s are the on-wire identifiers. |
| `db.put(table, cells)` | Single-op transaction: POST `/kit/txn` with one `put` op. |
| `db.query(table).where(...)` | Builds a `/kit/query` body that pushes a condition down to a native index. |
| `.execute` | Sends the query and decodes the `rows` array. |
| `db.count(table)` | GET `/tables/{name}/count`. |

## 6. History retention and time travel

MongrelDB keeps a durable MVCC history window. You can inspect it, widen it,
and query older epochs with `AS OF EPOCH`.

```scala
println(db.historyRetentionEpochs)  // current window, e.g. 100
println(db.earliestRetainedEpoch)   // oldest readable epoch, e.g. 3

// Widen the window. The response contains the updated values.
val resp = db.setHistoryRetentionEpochs(1_000L)
println(resp("history_retention_epochs")) // 1000

// Read the table as it existed at epoch 5.
val rows = db.sql("SELECT id, amount FROM orders AS OF EPOCH 5")
```

Increasing retention cannot restore history that has already been pruned. The
window is a durable GC/time-travel policy, so it requires admin privileges when
the daemon is running with auth.

## 7. Common pitfalls

**Using the column name instead of the column id.** Every on-wire API uses the
numeric `id` from `createTable`, never the name. The query builder's `column`
alias maps to the server's `column_id` - pass the integer id:

```scala
// Wrong:
.where("range", Map("column" -> "amount", "min" -> 100L))
// Right:
.where("range", Map("column" -> 3L, "min" -> 100L))
```

**Treating a single `put` as non-transactional.** `put` is a one-op
transaction. A unique constraint violation surfaces as a `ConflictException`
(HTTP 409).

**Calling `commit` twice on the same `Transaction`.** The second call throws
`IllegalStateException`. Create a fresh `db.begin()` for each logical unit.

**Expecting `sql` to always return rows.** The `/sql` endpoint streams Arrow
IPC for `SELECT` in most builds, so `sql` returns an empty list. Use the native
query builder for typed row retrieval; use `sql` for DDL/DML.

## Next steps

- [transactions.md](transactions.md) - atomic batches, idempotency, retries
- [queries.md](queries.md) - every native index condition
- [sql.md](sql.md) - recursive CTEs, window functions
- [auth.md](auth.md) - bearer tokens, basic auth
- [errors.md](errors.md) - the full error hierarchy
