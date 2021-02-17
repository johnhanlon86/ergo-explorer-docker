package org.ergoplatform.explorer.grabber

import cats.effect.{ExitCode, Resource}
import cats.syntax.functor._
import doobie.free.connection.ConnectionIO
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import monix.eval.{Task, TaskApp}
import org.ergoplatform.explorer.clients.ergo.ErgoNetworkClient
import org.ergoplatform.explorer.db.{DoobieTrans, Trans}
import org.ergoplatform.explorer.grabber.processes.ChainIndexer
import org.ergoplatform.explorer.settings.IndexerAppSettings
import org.http4s.client.blaze.BlazeClientBuilder
import tofu.concurrent.MakeRef
import tofu.logging.Logs

import scala.concurrent.ExecutionContext.global

/** A service dumping blocks from the Ergo network to local db.
  */
object Application extends TaskApp {

  implicit val logs: Logs[Task, Task]       = Logs.sync
  implicit val makeRef: MakeRef[Task, Task] = MakeRef.syncInstance

  def run(args: List[String]): Task[ExitCode] =
    resources(args.headOption).use { case (logger, settings, client, trans) =>
      logger.info("Starting Chain Indexer ..") >>
        ErgoNetworkClient[Task](client, settings.masterNodesAddresses)
          .flatMap { ns =>
            ChainIndexer[Task, ConnectionIO](settings, ns)(trans)
              .flatMap(_.run.compile.drain)
              .as(ExitCode.Success)
          }
          .guarantee(logger.info("Stopping Chain Indexer .."))
    }

  private def resources(configPathOpt: Option[String]) =
    for {
      logger   <- Resource.liftF(Slf4jLogger.create)
      settings <- Resource.liftF(IndexerAppSettings.load(configPathOpt))
      client   <- BlazeClientBuilder[Task](global).resource
      xa       <- DoobieTrans[Task]("IndexerPool", settings.db)
      trans = Trans.fromDoobie(xa)
    } yield (logger, settings, client, trans)
}
