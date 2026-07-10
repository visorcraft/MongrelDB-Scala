package dev.visorcraft.mongreldb

/** Base class for all errors raised by the MongrelDB client.
  *
  * Every non-2xx response from the daemon is mapped to a typed subclass of this
  * exception. Catch `MongrelDBException` to handle any client-side failure, or
  * catch one of the specific subclasses:
  *
  *   - [[AuthException]] - HTTP 401/403 (bad or missing credentials)
  *   - [[NotFoundException]] - HTTP 404 (missing table, schema, etc.)
  *   - [[ConflictException]] - HTTP 409 (unique, foreign-key, check, or trigger
  *     constraint violations)
  *   - [[QueryException]] - HTTP 400 or 5xx, and any other request-level failure
  *     not covered by the more specific subclasses
  *
  * Each typed exception also carries the HTTP status code and the daemon's
  * decoded error envelope (structured code and offending op index), so callers
  * can both branch on type and inspect the response detail.
  *
  * @param message human-readable detail message
  * @param status HTTP status code returned by the daemon, or `-1` when unknown
  * @param code the server's structured error code, or `null`
  * @param opIndex the offending op index within a transaction, or `null`
  * @param cause the underlying cause, or `null`
  */
class MongrelDBException(
    val message: String,
    val status: Int = -1,
    val code: String = null,
    val opIndex: Integer = null,
    cause: Throwable = null
) extends RuntimeException(message, cause, true, true)
