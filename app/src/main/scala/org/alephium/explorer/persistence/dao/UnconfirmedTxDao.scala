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

package org.alephium.explorer.persistence.dao

import scala.concurrent.{ExecutionContext, Future}

import slick.basic.DatabaseConfig
import slick.dbio.DBIOAction
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api._

import org.alephium.explorer.api.model._
import org.alephium.explorer.persistence.{DBActionW, DBRunner}
import org.alephium.explorer.persistence.model._
import org.alephium.explorer.persistence.schema._

trait UnconfirmedTxDao {
  def get(hash: Transaction.Hash): Future[Option[UnconfirmedTx]]
  def insertMany(utxs: Seq[UnconfirmedTx]): Future[Unit]
  def listHashes(): Future[Seq[Transaction.Hash]]
  def removeMany(txs: Seq[Transaction.Hash]): Future[Unit]
  def removeAndInsertMany(toRemove: Seq[Transaction.Hash],
                          toInsert: Seq[UnconfirmedTx]): Future[Unit]
}

object UnconfirmedTxDao {
  def apply(databaseConfig: DatabaseConfig[PostgresProfile])(
      implicit executionContext: ExecutionContext): UnconfirmedTxDao =
    new Impl(databaseConfig)

  private class Impl(val databaseConfig: DatabaseConfig[PostgresProfile])(
      implicit val executionContext: ExecutionContext)
      extends UnconfirmedTxDao
      with CustomTypes
      with DBRunner {

    def get(hash: Transaction.Hash): Future[Option[UnconfirmedTx]] = {
      run(for {
        maybeTx <- UnconfirmedTxSchema.unconfirmedTxsTable.filter(_.hash === hash).result.headOption
        inputs  <- UInputSchema.uinputsTable.filter(_.txHash === hash).result
        outputs <- UOutputSchema.uoutputsTable.filter(_.txHash === hash).result
      } yield {
        maybeTx.map { tx =>
          UnconfirmedTx(
            tx.hash,
            tx.chainFrom,
            tx.chainTo,
            inputs.map(_.toApi),
            outputs.map(_.toApi),
            tx.gasAmount,
            tx.gasPrice
          )
        }
      })
    }

    private def insertManyAction(utxs: Seq[UnconfirmedTx]): DBActionW[Unit] = {
      val entities = utxs.map(UnconfirmedTxEntity.from)
      val txs      = entities.map { case (tx, _, _) => tx }
      val inputs   = entities.flatMap { case (_, in, _) => in }
      val outputs  = entities.flatMap { case (_, _, out) => out }
      for {
        _ <- DBIOAction.sequence(txs.map(UnconfirmedTxSchema.unconfirmedTxsTable.insertOrUpdate))
        _ <- DBIOAction.sequence(inputs.map(UInputSchema.uinputsTable.insertOrUpdate))
        _ <- DBIOAction.sequence(outputs.map(UOutputSchema.uoutputsTable.insertOrUpdate))
      } yield ()
    }

    def insertMany(utxs: Seq[UnconfirmedTx]): Future[Unit] = {
      run(insertManyAction(utxs))
    }

    def listHashes(): Future[Seq[Transaction.Hash]] = {
      run(UnconfirmedTxSchema.unconfirmedTxsTable.map(_.hash).result)
    }

    private def removeManyAction(txs: Seq[Transaction.Hash]): DBActionW[Unit] = {
      for {
        _ <- UnconfirmedTxSchema.unconfirmedTxsTable.filter(_.hash inSet txs).delete
        _ <- UOutputSchema.uoutputsTable.filter(_.txHash inSet txs).delete
        _ <- UInputSchema.uinputsTable.filter(_.txHash inSet txs).delete
      } yield ()
    }

    def removeMany(txs: Seq[Transaction.Hash]): Future[Unit] = {
      run(removeManyAction(txs))
    }

    def removeAndInsertMany(toRemove: Seq[Transaction.Hash],
                            toInsert: Seq[UnconfirmedTx]): Future[Unit] = {
      run((for {
        _ <- removeManyAction(toRemove)
        _ <- insertManyAction(toInsert)
      } yield ()).transactionally)
    }
  }
}
