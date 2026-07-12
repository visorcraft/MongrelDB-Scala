package com.visorcraft.mongreldb

/** Raised for HTTP 404 responses - a missing table, schema, or other resource. */
class NotFoundException(message: String, status: Int, code: String, opIndex: Integer)
  extends MongrelDBException(message, status, code, opIndex)
