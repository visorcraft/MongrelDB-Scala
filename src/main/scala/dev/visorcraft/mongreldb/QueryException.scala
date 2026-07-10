package dev.visorcraft.mongreldb

/** Raised for HTTP 400 or 5xx responses, and for any other request-level failure
  * not covered by [[AuthException]], [[NotFoundException]], or
  * [[ConflictException]].
  *
  * This is the catch-all for malformed queries, server-side errors, and transport
  * failures (the latter carries the underlying cause and an HTTP status of `-1`).
  */
class QueryException(message: String, status: Int, code: String, opIndex: Integer, cause: Throwable)
  extends MongrelDBException(message, status, code, opIndex, cause)

object QueryException:
  /** Builds a transport/query error with no HTTP detail. */
  def apply(message: String): QueryException = new QueryException(message, -1, null, null, null)

  /** Builds a transport error wrapping a cause. */
  def apply(message: String, cause: Throwable): QueryException =
    new QueryException(message, -1, null, null, cause)
