package com.visorcraft.mongreldb

import scala.collection.mutable.ListBuffer

/** Stages operations locally and commits them atomically in a single
  * `/kit/txn` request. The engine enforces unique, foreign-key, check, and
  * trigger constraints at commit time; on any violation all operations roll
  * back and [[commit]] throws a [[ConflictException]] carrying the server's
  * structured code and offending op index.
  *
  * A `Transaction` is single-use: after [[commit]] or [[rollback]] it must not
  * be reused. Calling either a second time throws `IllegalStateException`.
  *
  * Start one with [[MongrelDB.begin]]:
  * {{{
  * val txn = db.begin()
  * txn.put("orders", Map(1L -> 10L, 2L -> "Dave"), returning = false)
  * txn.put("orders", Map(1L -> 11L, 2L -> "Eve"), returning = false)
  * txn.deleteByPk("orders", 2L)
  * val results = txn.commit(null) // atomic - all or nothing
  * }}}
  */
final class Transaction private[mongreldb] (client: MongrelDB):
  import Transaction.AlreadyCommitted

  private val ops: ListBuffer[Map[String, Any]] = ListBuffer.empty
  private var committed: Boolean = false

  private def ensureOpen(): Unit =
    if committed then throw new IllegalStateException(AlreadyCommitted)

  /** Stages an insert. `returning`, when `true`, asks the daemon to echo the row
    * in the per-operation result. */
  def put(table: String, cells: Map[Long, Any], returning: Boolean = false): this.type =
    ensureOpen()
    require(table != null, "table")
    require(cells != null, "cells")
    val putOp: Map[String, Any] = Map("table" -> table, "cells" -> MongrelDB.flattenCells(cells),
                                      "returning" -> returning)
    ops += Map("put" -> putOp)
    this

  /** Stages an insert-or-update. `updateCells`, when non-null, supplies the
    * values written on a primary-key conflict; `null` means DO NOTHING. */
  def upsert(table: String, cells: Map[Long, Any], updateCells: Map[Long, Any] = null,
             returning: Boolean = false): this.type =
    ensureOpen()
    require(table != null, "table")
    require(cells != null, "cells")
    var upsertOp: Map[String, Any] = Map("table" -> table, "cells" -> MongrelDB.flattenCells(cells),
                                         "returning" -> returning)
    if updateCells != null then upsertOp = upsertOp.updated("update_cells", MongrelDB.flattenCells(updateCells))
    ops += Map("upsert" -> upsertOp)
    this

  /** Stages a delete by the internal row id. */
  def delete(table: String, rowId: Long): this.type =
    ensureOpen()
    require(table != null, "table")
    ops += Map("delete" -> Map("table" -> table, "row_id" -> rowId))
    this

  /** Stages a delete by primary-key value. */
  def deleteByPk(table: String, pk: Any): this.type =
    ensureOpen()
    require(table != null, "table")
    require(pk != null, "pk")
    ops += Map("delete_by_pk" -> Map("table" -> table, "pk" -> pk))
    this

  /** The number of staged operations. */
  def count: Int = ops.size

  /** Sends all staged operations atomically and returns the per-operation
    * results. `idempotencyKey`, when non-null and non-empty, makes the commit
    * safe to retry.
    *
    * @throws IllegalStateException on a second commit/rollback on the same txn
    * @throws ConflictException if a constraint violation rolled back the batch
    */
  def commit(idempotencyKey: String): List[Map[String, Any]] =
    if committed then throw new IllegalStateException(AlreadyCommitted)
    committed = true
    if ops.isEmpty then Nil else client.commitTxn(ops.toList, idempotencyKey)

  /** Discards all staged operations.
    * @throws IllegalStateException if the transaction was already committed
    */
  def rollback(): Unit =
    if committed then throw new IllegalStateException(AlreadyCommitted)
    ops.clear()
    committed = true

object Transaction:
  final val AlreadyCommitted = "mongreldb: transaction already committed"
