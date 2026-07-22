package com.visorcraft.mongreldb

/** Structural HLC from durable recovery (0.64+). */
final case class CommitHlc(
    physicalMicros: Long,
    logical: Int,
    nodeTiebreaker: Int
)

object CommitHlc:
  def fromMap(raw: Any): Option[CommitHlc] =
    raw match
      case m: Map[String, Any] @unchecked =>
        m.get("physical_micros") match
          case Some(p: Number) =>
            Some(
              CommitHlc(
                p.longValue,
                m.get("logical") match
                  case Some(n: Number) => n.intValue
                  case _               => 0
                ,
                m.get("node_tiebreaker") match
                  case Some(n: Number) => n.intValue
                  case _               => 0
              )
            )
          case _ => None
      case _ => None

/** Nested durable recovery payload. */
final case class DurableOutcome(
    committed: Option[Boolean],
    committedStatements: Option[Int],
    lastCommitEpoch: Option[Long],
    lastCommitEpochText: Option[String],
    lastCommitHlc: Option[CommitHlc],
    serialization: String,
    serializationState: Option[String],
    terminalState: Option[String]
)

object DurableOutcome:
  def fromMap(raw: Any): DurableOutcome =
    val m = raw match
      case x: Map[String, Any] @unchecked => x
      case _                              => Map.empty[String, Any]
    DurableOutcome(
      committed = m.get("committed").collect { case b: Boolean => b },
      committedStatements = m.get("committed_statements").collect { case n: Number => n.intValue },
      lastCommitEpoch = m.get("last_commit_epoch").collect { case n: Number => n.longValue },
      lastCommitEpochText = m.get("last_commit_epoch_text").map(_.toString),
      lastCommitHlc = CommitHlc.fromMap(m.get("last_commit_hlc").orNull),
      serialization = m.get("serialization").map(_.toString).getOrElse(""),
      serializationState = m.get("serialization_state").map(_.toString),
      terminalState = m.get("terminal_state").map(_.toString)
    )

/** GET /queries/{query_id} decoded status. */
final case class QueryStatus(
    queryId: String,
    status: String,
    state: String,
    serverState: String,
    terminalState: Option[String],
    committed: Option[Boolean],
    lastCommitEpoch: Option[Long],
    lastCommitHlc: Option[CommitHlc],
    outcome: DurableOutcome,
    durable: Option[DurableOutcome],
    raw: Map[String, Any]
):
  /** Authoritative HLC: durable → outcome → top-level. */
  def commitHlc: Option[CommitHlc] =
    durable.flatMap(_.lastCommitHlc).orElse(outcome.lastCommitHlc).orElse(lastCommitHlc)

  def serializationState: String =
    durable
      .flatMap(d => d.serializationState.filter(_.nonEmpty).orElse(Option(d.serialization).filter(_.nonEmpty)))
      .orElse(outcome.serializationState.filter(_.nonEmpty))
      .getOrElse(outcome.serialization)

object QueryStatus:
  def fromMap(raw: Map[String, Any]): QueryStatus =
    val durable = raw.get("durable") match
      case Some(m: Map[?, ?] @unchecked) => Some(DurableOutcome.fromMap(m))
      case _                             => None
    QueryStatus(
      queryId = raw.get("query_id").map(_.toString).getOrElse(""),
      status = raw.get("status").map(_.toString).getOrElse(""),
      state = raw.get("state").map(_.toString).getOrElse(""),
      serverState = raw
        .get("server_state")
        .orElse(raw.get("state"))
        .map(_.toString)
        .getOrElse(""),
      terminalState = raw.get("terminal_state").map(_.toString),
      committed = raw.get("committed").collect { case b: Boolean => b },
      lastCommitEpoch = raw.get("last_commit_epoch").collect { case n: Number => n.longValue },
      lastCommitHlc = CommitHlc.fromMap(raw.get("last_commit_hlc").orNull),
      outcome = DurableOutcome.fromMap(raw.get("outcome").orNull),
      durable = durable,
      raw = raw
    )
