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

package org.alephium.explorer.persistence.queries

import slick.dbio.DBIOAction
import slick.jdbc.{PositionedParameters, SetParameter, SQLActionBuilder}
import slick.jdbc.PostgresProfile.api._

import org.alephium.explorer.api.model._
import org.alephium.explorer.persistence._
import org.alephium.explorer.persistence.model._
import org.alephium.explorer.persistence.schema.CustomGetResult._
import org.alephium.explorer.persistence.schema.CustomSetParameter._
import org.alephium.explorer.util.SlickUtil._
import org.alephium.util.AVector

object UnconfirmedTransactionQueries {

  val listHashesQuery: DBActionSR[Transaction.Hash] = {
    sql"""
      SELECT hash
      FROM utransactions
    """.asAV[Transaction.Hash]
  }

  def listPaginatedUnconfirmedTransactionsQuery(
      pagination: Pagination): DBActionSR[UnconfirmedTxEntity] = {
    val offset = pagination.offset.toLong
    val limit  = pagination.limit.toLong
    val toDrop = offset * limit
    sql"""
      SELECT hash,
             chain_from,
             chain_to,
             gas_amount,
             gas_price,
             last_seen
      FROM utransactions
      ORDER BY last_seen DESC
      LIMIT $limit
      OFFSET $toDrop
    """.asAV[UnconfirmedTxEntity]
  }

  def listUTXHashesByAddress(address: Address): DBActionSR[Transaction.Hash] = {
    sql"""
      SELECT DISTINCT tx_hash
      FROM uinputs
      WHERE address = $address
    """.asAV[Transaction.Hash]
  }

  def utxsFromTxs(hashes: AVector[Transaction.Hash]): DBActionSR[UnconfirmedTxEntity] = {
    if (hashes.nonEmpty) {
      val params = paramPlaceholder(1, hashes.length)

      val query =
        s"""
           |SELECT hash,
           |       chain_from,
           |       chain_to,
           |       gas_amount,
           |       gas_price,
           |       last_seen
           |FROM utransactions
           |WHERE hash IN $params
           |ORDER BY last_seen DESC
           |""".stripMargin

      val parameters: SetParameter[Unit] =
        (_: Unit, params: PositionedParameters) =>
          hashes foreach { txnHash =>
            params >> txnHash
        }

      SQLActionBuilder(
        queryParts = query,
        unitPConv  = parameters
      ).asAV[UnconfirmedTxEntity]
    } else {
      DBIOAction.successful(AVector.empty)
    }
  }

  def uoutputsFromTxs(hashes: AVector[Transaction.Hash]): DBActionSR[UOutputEntity] = {
    if (hashes.nonEmpty) {
      val params = paramPlaceholder(1, hashes.length)

      val query =
        s"""
           |SELECT tx_hash,
           |       hint,
           |       key,
           |       amount,
           |       address,
           |       tokens,
           |       lock_time,
           |       message,
           |       uoutput_order
           |FROM uoutputs
           |WHERE tx_hash IN $params
           |""".stripMargin

      val parameters: SetParameter[Unit] =
        (_: Unit, params: PositionedParameters) =>
          hashes foreach { txnHash =>
            params >> txnHash
        }

      SQLActionBuilder(
        queryParts = query,
        unitPConv  = parameters
      ).asAV[UOutputEntity]
    } else {
      DBIOAction.successful(AVector.empty)
    }
  }

  def uinputsFromTxs(hashes: AVector[Transaction.Hash]): DBActionSR[UInputEntity] = {
    if (hashes.nonEmpty) {
      val params = paramPlaceholder(1, hashes.length)

      val query =
        s"""
           |SELECT tx_hash,
           |       hint,
           |       output_ref_key,
           |       unlock_script,
           |       address,
           |       uinput_order
           |FROM uinputs
           |WHERE tx_hash IN $params
           |""".stripMargin

      val parameters: SetParameter[Unit] =
        (_: Unit, params: PositionedParameters) =>
          hashes foreach { txnHash =>
            params >> txnHash
        }

      SQLActionBuilder(
        queryParts = query,
        unitPConv  = parameters
      ).asAV[UInputEntity]
    } else {
      DBIOAction.successful(AVector.empty)
    }
  }

  def utxFromTxHash(hash: Transaction.Hash): DBActionSR[UnconfirmedTxEntity] = {
    sql"""
           |SELECT hash,
           |       chain_from,
           |       chain_to,
           |       gas_amount,
           |       gas_price,
           |       last_seen
           |FROM utransactions
           |WHERE hash = $hash
           |""".stripMargin.asAV[UnconfirmedTxEntity]
  }

  def uoutputsFromTx(hash: Transaction.Hash): DBActionSR[UOutputEntity] = {
    sql"""
           |SELECT tx_hash,
           |       hint,
           |       key,
           |       amount,
           |       address,
           |       tokens,
           |       lock_time,
           |       message,
           |       uoutput_order
           |FROM uoutputs
           |WHERE tx_hash = $hash
           |ORDER BY uoutput_order
           |""".stripMargin.asAV[UOutputEntity]
  }

  def uinputsFromTx(hash: Transaction.Hash): DBActionSR[UInputEntity] = {
    sql"""
           |SELECT tx_hash,
           |       hint,
           |       output_ref_key,
           |       unlock_script,
           |       address,
           |       uinput_order
           |FROM uinputs
           |WHERE tx_hash = $hash
           |ORDER BY uinput_order
           |""".stripMargin.asAV[UInputEntity]
  }
}
