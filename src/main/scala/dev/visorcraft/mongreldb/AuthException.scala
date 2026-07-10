package dev.visorcraft.mongreldb

/** Raised for HTTP 401 or 403 responses - bad or missing credentials.
  *
  * The daemon returns these when started in `--auth-token` or `--auth-users` mode
  * and the request lacks valid credentials.
  */
class AuthException(message: String, status: Int, code: String, opIndex: Integer)
  extends MongrelDBException(message, status, code, opIndex)
