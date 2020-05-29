package org.alephium.explorer

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.SocketUtil
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}

import org.alephium.explorer.api.ApiError
import org.alephium.explorer.api.model.BlockEntry
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp}

class ApplicationSpec()
    extends AlephiumSpec
    with ScalatestRouteTest
    with ScalaFutures
    with Generators
    with FailFastCirceSupport {
  override implicit val patienceConfig = PatienceConfig(timeout = Span(1, Minutes))

  val genesis: BlockEntry =
    blockEntryGen.sample.get.copy(deps = AVector.empty, timestamp = TimeStamp.unsafe(0))

  val block1 =
    blockEntryGen.sample.get.copy(deps = AVector(genesis.hash), timestamp = TimeStamp.unsafe(1))
  val block2 =
    blockEntryGen.sample.get.copy(deps = AVector(genesis.hash), timestamp = TimeStamp.unsafe(2))
  val block3 = blockEntryGen.sample.get
    .copy(deps = AVector(genesis.hash, block1.hash, block2.hash), timestamp = TimeStamp.unsafe(3))

  val blocks: Seq[BlockEntry] = Seq(genesis, block1, block2, block3)

  val blockFlowPort = SocketUtil.temporaryLocalPort(SocketUtil.Both)
  val blockFlowMock = new ApplicationSpec.BlockFlowServerMock(blockFlowPort, blocks)

  val blockflowBinding = blockFlowMock.server.futureValue

  val app: Application =
    new Application(SocketUtil.temporaryLocalPort(), Uri(s"http://localhost:$blockFlowPort"))

  val routes = app.route

  it should "get a block by its id" in {
    Get(s"/blocks/${genesis.hash}") ~> routes ~> check {
      responseAs[BlockEntry] is genesis
    }

    Get(s"/blocks/${block3.hash}") ~> routes ~> check {
      responseAs[BlockEntry] is block3
    }

    Get(s"/blocks/1afd32") ~> routes ~> check {
      status is StatusCodes.NotFound
      responseAs[ApiError] is ApiError.NotFound("1afd32")
    }
  }

  it should "list blocks" in {
    Get(s"/blocks?fromTs=0&toTs=3") ~> routes ~> check {
      responseAs[Seq[BlockEntry]] is blocks.reverse
    }

    Get(s"/blocks?fromTs=4&toTs=6") ~> routes ~> check {
      responseAs[Seq[BlockEntry]] is Seq.empty
    }

    Get(s"/blocks?fromTs=0&toTs=0") ~> routes ~> check {
      responseAs[Seq[BlockEntry]] is Seq(genesis)
    }

    Get(s"/blocks?fromTs=1&toTs=0") ~> routes ~> check {
      status is StatusCodes.BadRequest
      responseAs[ApiError] is ApiError.BadRequest(
        "Invalid value (expected value to pass custom validation: `fromTs` must be before `toTs`, "
          ++ "but was '(org.alephium.util.TimeStamp@1,org.alephium.util.TimeStamp@0)')")
    }
  }

  it should "generate the documentation" in {
    Get("openapi.yaml") ~> routes ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  app.stop
}

object ApplicationSpec {

  final case class FetchResponse(blocks: Seq[BlockEntry])
  final case class Result[A: Codec](result: A)

  implicit val fetchResponseCodec: Codec[FetchResponse] = deriveCodec[FetchResponse]
  implicit def resultCodec[A: Codec]: Codec[Result[A]]  = deriveCodec[Result[A]]

  class BlockFlowServerMock(port: Int, blocks: Seq[BlockEntry])(implicit system: ActorSystem)
      extends FailFastCirceSupport {
    val routes: Route =
      post {
        complete(Result(FetchResponse(blocks)))
      }
    val server = Http().bindAndHandle(routes, "localhost", port)
  }
}
