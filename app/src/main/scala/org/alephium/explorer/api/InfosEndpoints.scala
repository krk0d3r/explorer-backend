// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.explorer.api

import java.math.BigDecimal

import sttp.tapir._
import sttp.tapir.generic.auto._

import org.alephium.api.{alphJsonBody => jsonBody}
import org.alephium.api.UtilJson._
import org.alephium.explorer.api.BaseEndpoint
import org.alephium.explorer.api.model._
import org.alephium.util.AVector

// scalastyle:off magic.number
trait InfosEndpoints extends BaseEndpoint with QueryParams {

  private val infosEndpoint =
    baseEndpoint
      .tag("Infos")
      .in("infos")

  private val supplyEndpoint =
    infosEndpoint
      .in("supply")

  val getInfos: BaseEndpoint[Unit, ExplorerInfo] =
    infosEndpoint.get
      .out(jsonBody[ExplorerInfo])
      .description("Get explorer informations")

  val listTokenSupply: BaseEndpoint[Pagination, AVector[TokenSupply]] =
    supplyEndpoint.get
      .in(pagination)
      .out(jsonBody[AVector[TokenSupply]])
      .description("Get token supply list")

  val getCirculatingSupply: BaseEndpoint[Unit, BigDecimal] =
    supplyEndpoint.get
      .in("circulating-alph")
      .out(plainBody[BigDecimal])
      .description("Get the ALPH circulating supply")

  val getTotalSupply: BaseEndpoint[Unit, BigDecimal] =
    supplyEndpoint.get
      .in("total-alph")
      .out(plainBody[BigDecimal])
      .description("Get the ALPH total supply")

  val getReservedSupply: BaseEndpoint[Unit, BigDecimal] =
    supplyEndpoint.get
      .in("reserved-alph")
      .out(plainBody[BigDecimal])
      .description("Get the ALPH reserved supply")

  val getLockedSupply: BaseEndpoint[Unit, BigDecimal] =
    supplyEndpoint.get
      .in("locked-alph")
      .out(plainBody[BigDecimal])
      .description("Get the ALPH locked supply")

  val getHeights: BaseEndpoint[Unit, AVector[PerChainHeight]] =
    infosEndpoint.get
      .in("heights")
      .out(jsonBody[AVector[PerChainHeight]])
      .description("List latest height for each chain")

  val getTotalTransactions: BaseEndpoint[Unit, Int] =
    infosEndpoint.get
      .in("total-transactions")
      .out(plainBody[Int])
      .description("Get the total number of transactions")

  val getAverageBlockTime: BaseEndpoint[Unit, AVector[PerChainDuration]] =
    infosEndpoint.get
      .in("average-block-times")
      .out(jsonBody[AVector[PerChainDuration]])
      .description("Get the average block time for each chain")
}
