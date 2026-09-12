package com.raquo.waypoint.navigation

import com.raquo.airstream.ownership.Subscription

/** A navigation request put to `preventNavigation` and awaiting its answer.
  *
  * It ties the answer to the request it is about. Once resolved — because the guard answered,
  * or a newer navigation superseded this one — nothing more can come of it, and the guard's
  * stream is unsubscribed from so anything it started (a confirmation dialog, a server request)
  * is torn down with it.
  */
final class PendingNavigation[Page](val request: NavigationRequest[Page]) {

  private var _isResolved: Boolean = false

  private var guardSubscriptionOpt: Option[Subscription] = None

  def isResolved: Boolean = _isResolved

  /** Remembers the guard subscription, so that [[resolve]] can kill it later. */
  def holdGuard(subscription: Subscription): Unit = {
    guardSubscriptionOpt = Some(subscription)
  }

  def resolve(): Unit = {
    _isResolved = true
    guardSubscriptionOpt.foreach(_.kill())
    guardSubscriptionOpt = None
  }
}
