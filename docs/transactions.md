# Transactions

MongrelDB commits every write through a single atomic transaction endpoint
(`POST /kit/txn`). This guide covers the two ways to use it - a one-shot single
op, and a staged batch - plus idempotency keys for safe retries, typed
constraint-violation handling, and rollback.

The engine enforces `UNIQUE`, foreign-key, check, and trigger constraints at
**commit time**. A violation aborts the entire batch: no op becomes visible.

---

## Single puts vs. batch transactions

### Single op: `db.put`

`put` is a convenience wrapper that sends a one-op transaction.

```scala
val res = db.put("orders", Map(1L -> 1L, 2L -> "Alice", 3L -> 99.5))
```

`upsert`, `delete`, and `deleteByPk` are the same shape.

### Batch: `db.begin()` + `Transaction`

```scala
val txn = db.begin()
txn.put("orders", Map(1L -> 10L, 2L -> "Dave", 3L -> 50.0))
txn.put("orders", Map(1L -> 11L, 2L -> "Eve",  3L -> 75.0))
txn.deleteByPk("orders", 2L)

val results = txn.commit(null)
println(s"committed ${results.length} ops")
```

## Idempotency keys for safe retries

Networks drop requests and daemons crash after committing but before replying.
An idempotency key makes a commit safe to retry: the daemon replays the
**original** result on a duplicate commit, even across restarts.

```scala
def charge(db: MongrelDB, orderId: Long): List[Map[String, Any]] =
  val txn = db.begin()
  txn.put("charges", Map(1L -> orderId, 2L -> 199.0))
  // Use a stable, business-meaningful key derived from the request.
  txn.commit(s"charge:$orderId")
```

Rules for keys:

- Any non-empty string works. Prefer content-derived, globally-unique values.
- `null` disables idempotency - a retry will commit again.
- The key scopes the **entire batch**, not individual ops.

## Handling constraint violations

Constraint violations arrive as HTTP 409, mapped to `ConflictException`. It
carries the structured `code` and the offending `opIndex`:

```scala
try
  val txn = db.begin()
  txn.put("orders", Map(1L -> 1L))
  txn.commit(null)
catch
  case e: ConflictException => e.code match
    case "UNIQUE_VIOLATION" => println(s"duplicate at op ${e.opIndex}: ${e.message}")
    case "FK_VIOLATION"     => println(s"missing parent at op ${e.opIndex}: ${e.message}")
    case _                  => println(s"other conflict: ${e.message}")
```

The error envelope from the daemon:

```json
{"status": "aborted", "error": {"code": "UNIQUE_VIOLATION", "message": "...", "op_index": 0}}
```

## Rollback after failure

1. **Server-side.** When `commit` throws `ConflictException`, the engine has
   already discarded the entire batch. Nothing was written.
2. **Client-side.** `txn.rollback()` clears the locally staged ops. Call it to
   release the `Transaction` when you decide not to commit (before ever sending).

```scala
val txn = db.begin()
txn.put("orders", Map(1L -> 1L, 2L -> "Iris", 3L -> 5.0))

if !businessRuleOk then
  txn.rollback() // throw the staged ops away locally; nothing sent
else
  try txn.commit(null)
  catch case _: ConflictException => () // server already rolled back
```

`rollback` and `commit` both throw `IllegalStateException` if the transaction
was already committed.

## Summary

| Goal | Use |
|------|-----|
| One independent write | `put` / `upsert` / `delete` / `deleteByPk` |
| Several writes that must commit together | `begin()` + `commit(idempotencyKey)` |
| Retry safely after a network blip | `commit(idempotencyKey)` with a stable key |
| Distinguish constraint classes | catch `ConflictException`, read `.code` and `.opIndex` |
| Abort before sending | `rollback()` |

See [errors.md](errors.md) for the full error hierarchy and [queries.md](queries.md)
for read patterns.
