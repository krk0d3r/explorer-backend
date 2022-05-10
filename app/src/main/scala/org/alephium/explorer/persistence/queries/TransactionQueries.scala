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

import scala.concurrent.ExecutionContext

import com.typesafe.scalalogging.StrictLogging
import slick.dbio.DBIOAction
import slick.jdbc.{PositionedParameters, SetParameter, SQLActionBuilder}
import slick.jdbc.PostgresProfile.api._

import org.alephium.explorer.Hash
import org.alephium.explorer.api.model._
import org.alephium.explorer.persistence._
import org.alephium.explorer.persistence.model._
import org.alephium.explorer.persistence.queries.InputQueries._
import org.alephium.explorer.persistence.queries.OutputQueries._
import org.alephium.explorer.persistence.schema._
import org.alephium.explorer.persistence.schema.CustomGetResult._
import org.alephium.explorer.persistence.schema.CustomJdbcTypes._
import org.alephium.explorer.persistence.schema.CustomSetParameter._
import org.alephium.explorer.util.SlickSugar._
import org.alephium.util.{TimeStamp, U256}

object TransactionQueries extends StrictLogging {

  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  val mainTransactions    = TransactionSchema.table.filter(_.mainChain)
  private val mainInputs  = InputSchema.table.filter(_.mainChain)
  private val mainOutputs = OutputSchema.table.filter(_.mainChain)

  def insertAll(transactions: Seq[TransactionEntity],
                outputs: Seq[OutputEntity],
                inputs: Seq[InputEntity]): DBActionW[Int] = {
    insertTransactions(transactions) andThen
      insertInputs(inputs) andThen
      insertOutputs(outputs)
  }

  /** Inserts transactions or ignore rows with primary key conflict */
  def insertTransactions(transactions: Iterable[TransactionEntity]): DBActionW[Int] =
    QuerySplitter.splitUpdates(rows = transactions, columnsPerRow = 9) {
      (transactions, placeholder) =>
        val query =
          s"""
           |insert into transactions (hash,
           |                          block_hash,
           |                          block_timestamp,
           |                          chain_from,
           |                          chain_to,
           |                          gas_amount,
           |                          gas_price,
           |                          tx_order,
           |                          main_chain)
           |values $placeholder
           |ON CONFLICT ON CONSTRAINT txs_pk
           |    DO NOTHING
           |""".stripMargin

        val parameters: SetParameter[Unit] =
          (_: Unit, params: PositionedParameters) =>
            transactions foreach { transaction =>
              params >> transaction.hash
              params >> transaction.blockHash
              params >> transaction.timestamp
              params >> transaction.chainFrom
              params >> transaction.chainTo
              params >> transaction.gasAmount
              params >> transaction.gasPrice
              params >> transaction.order
              params >> transaction.mainChain
          }

        SQLActionBuilder(
          queryParts = query,
          unitPConv  = parameters
        ).asUpdate
    }

  def updateTransactionPerAddressAction(outputs: Seq[OutputEntity], inputs: Seq[InputEntity])(
      implicit ec: ExecutionContext): DBActionRW[Seq[InputEntity]] = {
    for {
      _              <- insertTxPerAddressFromOutputs(outputs)
      inputsToUpdate <- insertTxPerAddressFromInputs(inputs, outputs)
    } yield inputsToUpdate
  }
  private val countBlockHashTransactionsQuery = Compiled { blockHash: Rep[BlockEntry.Hash] =>
    TransactionSchema.table.filter(_.blockHash === blockHash).length
  }

  def countBlockHashTransactions(blockHash: BlockEntry.Hash): DBActionR[Int] =
    countBlockHashTransactionsQuery(blockHash).result

  def countAddressTransactions(address: Address): DBActionR[Int] =
    getTxNumberByAddressQuery(address).result

  private val getTransactionQuery = Compiled { txHash: Rep[Transaction.Hash] =>
    mainTransactions
      .filter(_.hash === txHash)
      .map(tx => (tx.blockHash, tx.timestamp, tx.gasAmount, tx.gasPrice))
  }

  def getTransactionAction(txHash: Transaction.Hash)(
      implicit ec: ExecutionContext): DBActionR[Option[Transaction]] =
    getTransactionQuery(txHash).result.headOption.flatMap {
      case None => DBIOAction.successful(None)
      case Some((blockHash, timestamp, gasAmount, gasPrice)) =>
        getKnownTransactionAction(txHash, blockHash, timestamp, gasAmount, gasPrice).map(Some.apply)
    }

  private val getTxHashesByBlockHashQuery = Compiled { (blockHash: Rep[BlockEntry.Hash]) =>
    TransactionSchema.table
      .filter(_.blockHash === blockHash)
      .sortBy(_.txOrder)
      .map(tx => (tx.hash, tx.blockHash, tx.timestamp, tx.txOrder))
  }

  private val getTxHashesByBlockHashWithPaginationQuery = Compiled {
    (blockHash: Rep[BlockEntry.Hash], toDrop: ConstColumn[Long], limit: ConstColumn[Long]) =>
      TransactionSchema.table
        .filter(_.blockHash === blockHash)
        .sortBy(_.txOrder)
        .map(tx => (tx.hash, tx.blockHash, tx.timestamp, tx.txOrder))
        .drop(toDrop)
        .take(limit)
  }

  private val getTxNumberByAddressQuery = Compiled { (address: Rep[Address]) =>
    mainInputs
      .join(mainOutputs)
      .on(_.outputRefKey === _.key)
      .filter(_._2.address === address)
      .map { case (input, _) => input.txHash }
      .union(
        mainOutputs
          .filter(_.address === address)
          .map(out => out.txHash)
      )
      .length
  }

  def countAddressTransactionsSQL(address: Address): DBActionSR[Int] = {
    sql"""
    SELECT COUNT(*)
    FROM (
      (SELECT inputs.tx_hash
        FROM inputs
        JOIN outputs ON  outputs.main_chain = true AND inputs.output_ref_key = outputs.key AND outputs.address = $address
        WHERE inputs.main_chain = true)
    UNION
    SELECT tx_hash from outputs WHERE main_chain = true AND address = $address
    ) tx_hashes
    """.as[Int]
  }

  def countAddressTransactionsSQLNoJoin(address: Address): DBActionSR[Int] = {
    sql"""
    SELECT COUNT(*)
    FROM transaction_per_addresses
    WHERE main_chain = true AND address = $address
    """.as[Int]
  }

  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  val getTxHashesByAddressQuery = Compiled {
    (address: Rep[Address], toDrop: ConstColumn[Long], limit: ConstColumn[Long]) =>
      mainInputs
        .join(mainOutputs)
        .on(_.outputRefKey === _.key)
        .filter(_._2.address === address)
        .map { case (input, _) => (input.txHash, input.blockHash, input.timestamp, input.txOrder) }
        .union(
          mainOutputs
            .filter(_.address === address)
            .map(out => (out.txHash, out.blockHash, out.timestamp, out.txOrder))
        )
        .sortBy { case (_, _, timestamp, txOrder) => (timestamp.desc, txOrder) }
        .drop(toDrop)
        .take(limit)
  }

  def getTxHashesByAddressQuerySQL(
      address: Address,
      offset: Int,
      limit: Int): DBActionSR[(Transaction.Hash, BlockEntry.Hash, TimeStamp, Int)] = {
    sql"""
    (
      SELECT inputs.tx_hash, inputs.block_hash, inputs.block_timestamp, inputs.tx_order
      FROM inputs
      JOIN outputs ON outputs.main_chain = true AND inputs.output_ref_key = outputs.key AND outputs.address = $address
      WHERE inputs.main_chain = true
      UNION
      SELECT tx_hash, block_hash, block_timestamp, tx_order from outputs
      WHERE main_chain = true AND address = $address
    )
    ORDER BY block_timestamp DESC, tx_order
    LIMIT $limit
    OFFSET $offset
    """.as
  }

  def getTxHashesByAddressQuerySQLNoJoin(
      address: Address,
      offset: Int,
      limit: Int): DBActionSR[(Transaction.Hash, BlockEntry.Hash, TimeStamp, Int)] = {
    sql"""
      SELECT hash, block_hash, block_timestamp, tx_order
      FROM transaction_per_addresses
      WHERE main_chain = true AND address = $address
      ORDER BY block_timestamp DESC, tx_order
      LIMIT $limit
      OFFSET $offset
    """.as
  }

  def getTransactionsByBlockHash(blockHash: BlockEntry.Hash)(
      implicit ec: ExecutionContext): DBActionR[Seq[Transaction]] = {
    for {
      txHashesTs <- getTxHashesByBlockHashQuery(blockHash).result
      txs        <- getTransactions(txHashesTs)
    } yield txs
  }

  def getTransactionsByBlockHashWithPagination(blockHash: BlockEntry.Hash, pagination: Pagination)(
      implicit ec: ExecutionContext): DBActionR[Seq[Transaction]] = {
    val offset = pagination.offset.toLong
    val limit  = pagination.limit.toLong
    val toDrop = offset * limit
    for {
      txHashesTs <- getTxHashesByBlockHashWithPaginationQuery((blockHash, toDrop, limit)).result
      txs        <- getTransactions(txHashesTs)
    } yield txs
  }

  def getTransactionsByAddress(address: Address, pagination: Pagination)(
      implicit ec: ExecutionContext): DBActionR[Seq[Transaction]] = {
    val offset = pagination.offset.toLong
    val limit  = pagination.limit.toLong
    val toDrop = offset * limit
    for {
      txHashesTs <- getTxHashesByAddressQuery((address, toDrop, limit)).result
      txs        <- getTransactions(txHashesTs)
    } yield txs
  }

  def getTransactionsByAddressSQL(address: Address, pagination: Pagination)(
      implicit ec: ExecutionContext): DBActionR[Seq[Transaction]] = {
    val offset = pagination.offset
    val limit  = pagination.limit
    val toDrop = offset * limit
    for {
      txHashesTs <- getTxHashesByAddressQuerySQLNoJoin(address, toDrop, limit)
      txs        <- getTransactionsSQL(txHashesTs)
    } yield txs
  }

  def getTransactions(txHashesTs: Seq[(Transaction.Hash, BlockEntry.Hash, TimeStamp, Int)])(
      implicit ec: ExecutionContext): DBActionR[Seq[Transaction]] = {
    val txHashes = txHashesTs.map(_._1)
    for {
      inputs  <- inputsFromTxs(txHashes).result
      outputs <- outputsFromTxs(txHashes).result
      gases   <- gasFromTxs(txHashes).result
    } yield {
      buildTransaction(txHashesTs, inputs, outputs, gases)
    }
  }

  def getTransactionsSQL(txHashesTs: Seq[(Transaction.Hash, BlockEntry.Hash, TimeStamp, Int)])(
      implicit ec: ExecutionContext): DBActionR[Seq[Transaction]] = {
    if (txHashesTs.nonEmpty) {
      val txHashes = txHashesTs.map(_._1)
      for {
        inputs  <- inputsFromTxsSQL(txHashes)
        outputs <- outputsFromTxsSQL(txHashes)
        gases   <- gasFromTxsSQL(txHashes)
      } yield {
        buildTransaction(txHashesTs, inputs, outputs, gases)
      }
    } else {
      DBIOAction.successful(Seq.empty)
    }
  }

  // format: off
  private def buildTransaction(
      txHashesTs: Seq[(Transaction.Hash, BlockEntry.Hash, TimeStamp, Int)],
      inputs: Seq[(Transaction.Hash, Int, Int, Hash, Option[String], Transaction.Hash, Address, U256)],
      outputs: Seq[(Transaction.Hash, Int, Int, Hash, U256, Address, Option[TimeStamp], Option[Transaction.Hash])],
      gases: Seq[(Transaction.Hash, Int, U256)]) = {
  // format: on
    val insByTx = inputs.groupBy(_._1).view.mapValues { values =>
      values
        .sortBy(_._2)
        .map {
          case (_, _, hint, key, unlockScript, txHashRef, address, amount) =>
            toApiInput((hint, key, unlockScript, txHashRef, address, amount))
        }
    }
    val ousByTx = outputs.groupBy(_._1).view.mapValues { values =>
      values
        .sortBy(_._2)
        .map {
          case (_, _, hint, key, amount, address, lockTime, spent) =>
            toApiOutput((hint, key, amount, address, lockTime, spent))
        }
    }
    val gasByTx = gases.groupBy(_._1).view.mapValues(_.map { case (_, s, g) => (s, g) })
    txHashesTs.map {
      case (tx, bh, ts, _) =>
        val ins                   = insByTx.getOrElse(tx, Seq.empty)
        val ous                   = ousByTx.getOrElse(tx, Seq.empty)
        val gas                   = gasByTx.getOrElse(tx, Seq.empty)
        val (gasAmount, gasPrice) = gas.headOption.getOrElse((0, U256.Zero))
        Transaction(tx, bh, ts, ins, ous, gasAmount, gasPrice)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.PublicInference"))
  def gasFromTxs(txHashes: Seq[Transaction.Hash]) = {
    mainTransactions.filter(_.hash inSet txHashes).map(tx => (tx.hash, tx.gasAmount, tx.gasPrice))
  }

  def gasFromTxsSQL(
      txHashes: Seq[Transaction.Hash]): DBActionR[Seq[(Transaction.Hash, Int, U256)]] = {
    if (txHashes.nonEmpty) {
      val values = txHashes.map(hash => s"'\\x$hash'").mkString(",")
      sql"""
    SELECT hash, gas_amount, gas_price
    FROM transactions
    WHERE main_chain = true AND hash IN (#$values)
    """.as
    } else {
      DBIOAction.successful(Seq.empty)
    }
  }

  private def getKnownTransactionAction(
      txHash: Transaction.Hash,
      blockHash: BlockEntry.Hash,
      timestamp: TimeStamp,
      gasAmount: Int,
      gasPrice: U256)(implicit ec: ExecutionContext): DBActionR[Transaction] =
    for {
      ins  <- getInputsQuery(txHash).result
      outs <- getOutputsQuery(txHash).result
    } yield {
      Transaction(txHash,
                  blockHash,
                  timestamp,
                  ins.map(toApiInput),
                  outs.map(toApiOutput),
                  gasAmount,
                  gasPrice)
    }

  def getBalanceQueryDEPRECATED(address: Address): DBActionSR[(U256, Option[TimeStamp])] = {
    sql"""
        SELECT outputs.amount, outputs.lock_time
        FROM outputs
        LEFT JOIN inputs
        ON outputs.key = inputs.output_ref_key
        WHERE outputs.main_chain = true
        AND outputs.address = $address
        AND inputs.block_hash IS NULL
      """.as[(U256, Option[TimeStamp])]
  }

  private def sumBalance(outputs: Seq[(U256, Option[TimeStamp])]): (U256, U256) = {
    val now = TimeStamp.now()
    outputs.foldLeft((U256.Zero, U256.Zero)) {
      case ((total, locked), (amount, lockTime)) =>
        val newTotal = total.addUnsafe(amount)
        val newLocked = if (lockTime.forall(_.isBefore(now))) {
          locked
        } else {
          locked.addUnsafe(amount)
        }
        (newTotal, newLocked)
    }
  }

  def getBalanceActionDEPRECATED(address: Address)(
      implicit ec: ExecutionContext): DBActionR[(U256, U256)] = {
    getBalanceQueryDEPRECATED(address).map(sumBalance)
  }

  def getBalanceAction(address: Address)(implicit ec: ExecutionContext): DBActionR[(U256, U256)] =
    getBalanceUntilLockTime(
      address  = address,
      lockTime = TimeStamp.now()
    ) map {
      case (total, locked) =>
        (total.getOrElse(U256.Zero), locked.getOrElse(U256.Zero))
    }

  def getBalanceActionOption(address: Address)(
      implicit ec: ExecutionContext): DBActionR[(Option[U256], Option[U256])] =
    getBalanceUntilLockTime(
      address  = address,
      lockTime = TimeStamp.now()
    )

  def getBalanceUntilLockTime(address: Address, lockTime: TimeStamp)(
      implicit ec: ExecutionContext): DBActionR[(Option[U256], Option[U256])] =
    sql"""
      SELECT sum(outputs.amount),
             sum(CASE
                     WHEN outputs.lock_time is NULL or outputs.lock_time < ${lockTime.millis} THEN 0
                     ELSE outputs.amount
                 END)
      FROM outputs
               LEFT JOIN inputs
                         ON outputs.key = inputs.output_ref_key
                             AND inputs.main_chain = true
      WHERE outputs.spent_finalized IS NULL
        AND outputs.address = $address
        AND outputs.main_chain = true
        AND inputs.block_hash IS NULL;
    """.as[(Option[U256], Option[U256])].exactlyOne

  // switch logger.trace when we can disable debugging mode
  protected def debugShow(query: slickProfile.ProfileAction[_, _, _]) = {
    print(s"${query.statements.mkString}\n")
  }
}
