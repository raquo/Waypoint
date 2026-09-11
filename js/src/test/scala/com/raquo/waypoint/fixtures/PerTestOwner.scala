package com.raquo.waypoint.fixtures

import com.raquo.airstream.ownership.ManualOwner
import org.scalatest.{BeforeAndAfterEach, Suite}

/** Provides a [[testOwner]] whose subscriptions are killed at the end of every test, so that no
  * spec has to remember to do it.
  *
  * This is safe for async specs as well as synchronous ones: `BeforeAndAfterEach.runTest` attaches
  * `afterEach` to the test's `Status` via `withAfterEffect`, so for an async test it runs once the
  * test's Future has completed, not when the test body returns that Future.
  */
trait PerTestOwner
extends BeforeAndAfterEach { this: Suite =>

  protected lazy val testOwner = new ManualOwner

  override protected def afterEach(): Unit = {
    try super.afterEach()
    finally testOwner.killSubscriptions()
  }
}
