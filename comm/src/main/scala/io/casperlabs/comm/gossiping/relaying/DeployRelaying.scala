package io.casperlabs.comm.gossiping.relaying

import cats.effect._
import cats.implicits._
import cats.{Monad, Parallel}
import io.casperlabs.comm.NodeAsk
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping.{DeployGossipingMetricsSource, GossipService, NewDeploysRequest}
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import monix.execution.Scheduler
import simulacrum.typeclass

@typeclass
trait DeployRelaying[F[_]] extends Relaying[F]

object DeployRelayingImpl {
  implicit val metricsSource: Metrics.Source =
    Metrics.Source(DeployGossipingMetricsSource, "Relaying")

  /** Export base 0 values so we have non-empty series for charts. */
  def establishMetrics[F[_]: Monad: Metrics] =
    for {
      _ <- Metrics[F].incrementCounter("relay_accepted", 0)
      _ <- Metrics[F].incrementCounter("relay_rejected", 0)
      _ <- Metrics[F].incrementCounter("relay_failed", 0)
    } yield ()

  def apply[F[_]: ContextShift: Concurrent: Parallel: Log: Metrics: NodeAsk](
      egressScheduler: Scheduler,
      nodeDiscovery: NodeDiscovery[F],
      connectToGossip: GossipService.Connector[F],
      relayFactor: Int,
      relaySaturation: Int,
      isSynchronous: Boolean = false
  ): DeployRelaying[F] = {
    val maxToTry = if (relaySaturation == 100) {
      Int.MaxValue
    } else {
      (relayFactor * 100) / (100 - relaySaturation)
    }
    new DeployRelayingImpl[F](
      egressScheduler,
      nodeDiscovery,
      connectToGossip,
      relayFactor,
      maxToTry,
      isSynchronous
    )
  }
}

/**
  * https://techspec.casperlabs.io/technical-details/global-state/communications#picking-nodes-for-gossip
  */
class DeployRelayingImpl[F[_]](
    val egressScheduler: Scheduler,
    val nodeDiscovery: NodeDiscovery[F],
    val connectToGossip: Node => F[GossipService[F]],
    val relayFactor: Int,
    val maxToTry: Int,
    val isSynchronous: Boolean
)(
    implicit
    override val CS: ContextShift[F],
    override val C: Concurrent[F],
    override val P: Parallel[F],
    override val L: Log[F],
    override val M: Metrics[F],
    override val N: NodeAsk[F],
    override val S: Metrics.Source
) extends DeployRelaying[F]
    with RelayingImpl[F] {
  override val request = (service, local, blockHashes) =>
    service.newDeploys(NewDeploysRequest(local.some, blockHashes)).map(_.isNew)
  override val requestName = "NewDeploys"
}
