package com.raquo.waypoint.fixtures

import org.scalajs.dom

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

// #Note[AI-impl]
/** Records the `history.back()` / `history.forward()` calls that the router makes.
  *
  * When a browser-initiated navigation is prevented, `Router.revertNavigation` undoes it by
  * calling `history.forward()` (to undo a Back) or `history.back()` (to undo a Forward or a
  * hash change). Choosing the right one of the two is the entire point of the Back / Forward
  * / HashChange classification, so tests assert on which one was called.
  *
  * The spy is transparent: it records the call and then lets the real navigation happen, so
  * the browser still performs the genuine history round trip — the undo really takes effect,
  * a real `popstate` really follows it, and the router's `suppressNextPopState` handshake is
  * really exercised. Tests drive their own "user pressed Back" navigations through
  * [[browserBack]] / [[browserForward]], which are deliberately NOT recorded, so that the
  * counters only ever reflect what the router itself did.
  *
  * Install via [[HistorySpy.use]] / [[HistorySpy.useAsync]], which guarantee that the
  * browser's own methods are put back afterwards — leaving a spy installed would corrupt
  * every later test in the suite, since they all share one browser page.
  */
final class HistorySpy private () {

  import HistorySpy.PatchableHistory

  private val history: PatchableHistory = dom.window.history.asInstanceOf[PatchableHistory]

  private val realBack = history.back

  private val realForward = history.forward

  private var _backCalls = 0

  private var _forwardCalls = 0

  /** How many times the router called `history.back()` since installation / the last [[reset]]. */
  def backCalls: Int = _backCalls

  /** How many times the router called `history.forward()` since installation / the last [[reset]]. */
  def forwardCalls: Int = _forwardCalls

  /** Zeroes the counters, to scope assertions to one phase of a multi-step test. */
  def reset(): Unit = {
    _backCalls = 0
    _forwardCalls = 0
  }

  /** Navigates back for real, as if the user had pressed the browser's Back button.
    * Not recorded — see the class docs.
    */
  def browserBack(): Unit = realBack(history)

  /** Navigates forward for real, as if the user had pressed the browser's Forward button.
    * Not recorded — see the class docs.
    */
  def browserForward(): Unit = realForward(history)

  private def install(): Unit = {
    history.back = { (_: PatchableHistory) =>
      _backCalls += 1
      browserBack()
    }
    history.forward = { (_: PatchableHistory) =>
      _forwardCalls += 1
      browserForward()
    }
  }

  /** Puts the browser's own `back` / `forward` implementations back in place. */
  def restore(): Unit = {
    history.back = realBack
    history.forward = realForward
  }
}

object HistorySpy {

  /** A view of `window.history` in which the two methods this fixture replaces are assignable.
    *
    * `dom.History` declares `back()` and `forward()` as methods, which Scala.js can call but not
    * reassign, so installing a spy needs them typed as mutable, function-valued properties
    * instead. Nothing about this is Selenium-specific: `scala-js-env-selenium` is entirely
    * JVM-side and ships no DOM facades of its own, and the same override would be needed under
    * any other JS environment.
    *
    * They are `js.ThisFunction0` rather than `js.Function0` because a history method read off the
    * object loses its receiver: Chrome throws `TypeError: Illegal invocation` for a detached
    * `history.back()`. A `ThisFunction` call passes the receiver explicitly, so `realBack(history)`
    * compiles to `realBack.call(history)`.
    */
  @js.native
  private trait PatchableHistory extends js.Object {

    var back: js.ThisFunction0[PatchableHistory, Unit] = js.native

    var forward: js.ThisFunction0[PatchableHistory, Unit] = js.native
  }

  /** Installs a spy for the duration of `f`, restoring the real methods afterwards. */
  def use[A](f: HistorySpy => A): A = {
    val spy = install()
    try f(spy)
    finally spy.restore()
  }

  /** Async counterpart of [[use]]: restores the real methods once `f`'s Future completes. */
  def useAsync[A](f: HistorySpy => Future[A])(implicit ec: ExecutionContext): Future[A] = {
    val spy = install()
    val result =
      try f(spy)
      catch { case err: Throwable => spy.restore(); throw err }
    result.transform { res => spy.restore(); res }
  }

  private def install(): HistorySpy = {
    val spy = new HistorySpy()
    spy.install()
    spy
  }
}
