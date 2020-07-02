package org.alephium.explorer.api

import sttp.tapir._
import sttp.tapir.json.circe.jsonBody

import org.alephium.explorer.api.Codecs._
import org.alephium.explorer.api.Schemas._
import org.alephium.explorer.api.model.{BlockEntry, TimeInterval}
import org.alephium.util.TimeStamp

trait BlockEndpoints extends BaseEndpoint {

  private val blocksEndpoint =
    baseEndpoint
      .tag("Blocks")
      .in("blocks")

  private val timeIntervalQuery: EndpointInput[TimeInterval] =
    query[TimeStamp]("fromTs")
      .and(query[TimeStamp]("toTs"))
      .validate(
        Validator.custom({ case (from, to) => from <= to }, "`fromTs` must be before `toTs`"))
      .map({ case (from, to) => TimeInterval(from, to) })(timeInterval =>
        (timeInterval.from, timeInterval.to))

  val getBlockByHash: Endpoint[BlockEntry.Hash, ApiError, BlockEntry, Nothing] =
    blocksEndpoint.get
      .in(path[BlockEntry.Hash]("block_hash"))
      .out(jsonBody[BlockEntry])
      .description("Get a block with hash")

  val listBlocks: Endpoint[TimeInterval, ApiError, Seq[BlockEntry], Nothing] =
    blocksEndpoint.get
      .in(timeIntervalQuery)
      .out(jsonBody[Seq[BlockEntry]])
      .description("List blocks within time interval")
}
