# Error handling

Every non-2xx response from the daemon is mapped to a typed Scala exception. This
is the complete reference: the exception hierarchy, the HTTP-status mapping, the
daemon's error envelope, and recovery patterns.

---

## The error model

All client errors descend from `MongrelDBException`. The client raises a
specific subclass for each failure category:

| Class | Meaning | Typical cause |
|-------|---------|---------------|
| `MongrelDBException` | Base class for all client errors | (catch this to handle any failure) |
| `AuthException` | HTTP 401 or 403 | Missing/bad credentials against an auth-enabled daemon |
| `NotFoundException` | HTTP 404 | Missing table, schema, or resource |
| `ConflictException` | HTTP 409 | Unique, foreign-key, check, or trigger violation at commit |
| `QueryException` | HTTP 400 or 5xx, plus network | Malformed request, server failure, transport error |

Each typed exception carries `status` (the HTTP code), `code` (the server's
structured code, e.g. `UNIQUE_VIOLATION`), and `opIndex` (the offending op index
within a batch, when reported).

## The daemon's error envelope

```json
{
  "status": "aborted",
  "error": {
    "code": "UNIQUE_VIOLATION",
    "message": "duplicate key in column 1",
    "op_index": 0
  }
}
```

Common `code` values:

| `code` | Meaning |
|--------------|---------|
| `UNIQUE_VIOLATION` | A unique/PK constraint rejected the commit |
| `FK_VIOLATION` | A foreign-key reference was missing |
| `CHECK_VIOLATION` | A check constraint or trigger rejected the commit |
| `NOT_FOUND` | A named resource does not exist |

## HTTP status -> exception mapping

| HTTP status | Exception | Notes |
|-------------|-----------|-------|
| 401, 403 | `AuthException` | Bad/missing credentials |
| 404 | `NotFoundException` | Resource not found |
| 409 | `ConflictException` | Constraint violation at commit |
| 400 | `QueryException` | Malformed request / bad query |
| 5xx | `QueryException` | Daemon-side failure |
| 2xx | (no error) | Success |

Network and encoding problems are also mapped to `QueryException`.

## Discriminating errors

```scala
try
  db.schemaFor("missing_table")
catch
  case _: NotFoundException => println("table does not exist")
  case _: ConflictException => println("unexpected conflict on a read")
  case _: AuthException     => println("bad credentials")
  case _: QueryException    => println("server error or malformed request")
  case e: MongrelDBException => println(s"other error: ${e.message}")
```

### By details - read `ConflictException` fields

```scala
try txn.commit(null)
catch case e: ConflictException =>
  println(s"status=409 code=${e.code} op=${e.opIndex} msg=${e.message}")
```

## Recovery patterns

### Auth failure - do not retry blindly

```scala
case e: AuthException =>
  throw new RuntimeException(s"credentials rejected; refresh token: ${e.message}")
```

### Not found - fall back, do not crash

```scala
try db.schemaFor(tableName)
catch case _: NotFoundException => Map.empty[String, Any]
```

### Transient failure - retry with an idempotency key

`QueryException` covers transport and 5xx failures. With an idempotency key,
retrying a transaction is safe (see [transactions.md](transactions.md)).

## Quick reference

```scala
// Category checks (most specific first):
catch
  case _: AuthException      // 401/403
  case _: NotFoundException  // 404
  case _: ConflictException  // 409
  case _: QueryException     // 400/5xx/network
  case e: MongrelDBException // base
```

## Next steps

- [transactions.md](transactions.md) - constraint handling and retries in context
- [auth.md](auth.md) - credential management
