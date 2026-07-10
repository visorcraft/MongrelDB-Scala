# Authentication & Authorization

A `mongreldb-server` daemon runs in one of three modes:

1. **Open** (default) - no auth required.
2. **Bearer token** (`--auth-token <TOKEN>`) - every request must carry an
   `Authorization: Bearer <TOKEN>` header.
3. **HTTP Basic** (`--auth-users`) - every request must carry an
   `Authorization: Basic <base64(user:pass)>` header.

The Scala client supports all three through the `MongrelDB` companion `apply`
methods.

---

## Bearer token mode

```scala
val db = MongrelDB("http://127.0.0.1:8453", "s3cret-token")
try
  val ok = db.health
  println(s"healthy: $ok")
catch case _: AuthException =>
  System.err.println("bad or missing token")
```

A missing or wrong token surfaces as `AuthException` (HTTP 401/403). Read the
token from the environment rather than hard-coding it:

```scala
val token = sys.env.getOrElse("MONGRELDB_TOKEN", "")
  if token.isEmpty then sys.exit(1)
val db = MongrelDB(token)
```

## Basic auth mode

```scala
val db = MongrelDB("http://127.0.0.1:8453", null, "admin", "s3cret")
```

The client base64-encodes `username:password` and sets `Authorization: Basic ...`
on every request.

## Token takes precedence

If you supply both, `token` wins and Basic credentials are ignored:

```scala
val db = MongrelDB(url, "overrides-everything", "fallback", "user")
```

## CRLF validation

Credentials and request paths are validated to reject CR or LF bytes. This
prevents HTTP header injection through a malicious value - a value containing a
newline throws `QueryException` at request time.

## User and role management via SQL

When the daemon is in Basic auth mode, users and roles live in the catalog and
are managed with SQL. Run these through `db.sql`.

```scala
db.sql("CREATE USER alice WITH PASSWORD 'hunter2'")
db.sql("ALTER USER alice ADMIN")
db.sql("CREATE ROLE analyst")
db.sql("GRANT SELECT ON orders TO analyst")
db.sql("GRANT analyst TO alice")
db.sql("DROP USER alice")
```

## Common pitfalls

**Auth errors look like other errors without a specific catch.** A 401/403
raises `AuthException`; a 404 raises `NotFoundException`. Always discriminate by
type rather than string-matching `e.message`.

**Sharing one client across threads is fine; sharing credentials across users is
not.** A `MongrelDB` instance carries one identity. If you serve multiple
authenticated users, build a client per user with that user's token.

**Token in version control.** Put secrets in the environment, a secret manager,
or a file outside the repo. Never commit a real token.

## Next steps

- [errors.md](errors.md) - `AuthException` and the rest of the error hierarchy
- [quickstart.md](quickstart.md) - the full end-to-end walkthrough
