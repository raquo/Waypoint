package com.raquo.waypoint.fixtures

import com.raquo.airstream.ownership.ManualOwner
import org.scalatest.{BeforeAndAfterAll, Suite}

/** Provides a [[testOwner]] whose subscriptions are killed once the whole suite has finished.
  *
  * For specs that build their routers at suite level rather than inside a test, which
  * [[PerTestOwner]] would leave dead from the second test onwards.
  *
  * Killing it at *some* point is not optional. A router listens for `popstate` while its owner
  * lives, and the whole suite shares one browser page and one session history, so a router that
  * outlives its spec keeps reacting to — and, if its guard prevents navigation, actively undoing
  * — the history navigations of every spec that runs after it.
  */
trait PerSuiteOwner
extends BeforeAndAfterAll { this: Suite =>

  protected lazy val testOwner = new ManualOwner

  override protected def afterAll(): Unit = {
    try super.afterAll()
    finally testOwner.killSubscriptions()
  }
}
