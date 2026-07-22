package com.visorcraft.mongreldb

class DurableRetrieveTest extends munit.FunSuite:
  test("query status parses structural HLC without string parsing") {
    val fixture: Map[String, Any] = Map(
      "query_id" -> "abcdefabcdefabcdefabcdefabcdefab",
      "status" -> "committed",
      "state" -> "completed",
      "server_state" -> "completed",
      "terminal_state" -> "committed",
      "committed" -> true,
      "last_commit_epoch" -> 17,
      "last_commit_hlc" -> Map(
        "physical_micros" -> 1700000000000000L,
        "logical" -> 3,
        "node_tiebreaker" -> 7
      ),
      "outcome" -> Map(
        "committed" -> true,
        "last_commit_epoch" -> 17,
        "last_commit_hlc" -> Map(
          "physical_micros" -> 1700000000000000L,
          "logical" -> 3,
          "node_tiebreaker" -> 7
        ),
        "serialization" -> "succeeded",
        "serialization_state" -> "succeeded",
        "terminal_state" -> "committed"
      ),
      "durable" -> Map(
        "committed" -> true,
        "last_commit_epoch" -> 17,
        "last_commit_hlc" -> Map(
          "physical_micros" -> 1700000000000000L,
          "logical" -> 3,
          "node_tiebreaker" -> 7
        ),
        "serialization" -> "succeeded",
        "serialization_state" -> "succeeded",
        "terminal_state" -> "committed"
      )
    )
    val status = QueryStatus.fromMap(fixture)
    assert(status.committed.contains(true))
    val hlc = status.commitHlc.get
    assert(hlc.physicalMicros == 1700000000000000L)
    assert(hlc.logical == 3)
    assert(hlc.nodeTiebreaker == 7)
    assert(status.serializationState == "succeeded")
    assert(status.outcome.lastCommitEpoch.contains(17L))
    assert(CommitHlc.fromMap(null).isEmpty)
    assert(CommitHlc.fromMap(Map.empty).isEmpty)
    assert(CommitHlc.fromMap(Map("logical" -> 1)).isEmpty)
  }
