package com.raquo.waypoint.fixtures

import org.scalajs.dom

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

// #Note[AI-impl]
/** Records every history traversal the router asks the browser to make.
  *
  * When a browser-initiated navigation is prevented, `Router.restoreCommittedEntry` undoes it by
  * moving the browser back to the entry the app is actually on. Getting the direction *and the
  * distance* of that move right is the whole point of the Back / Forward / HashChange
  * classification and the stamped navigation index, so tests assert on the exact moves requested.
  *
  * Moves are recorded as signed deltas, so `back()`, `forward()` and `go(delta)` are directly
  * comparable: `back()` is `-1`, `forward()` is `+1`, `go(n)` is `n`. An allowed navigation
  * should record no moves at all — the browser already did the work.
  *
  * The spy is transparent: it records the call, then lets the real navigation happen, so the
  * browser still performs the genuine round trip — the undo really takes effect, a real
  * `popstate` really follows, and the router's undo-recognition really gets exercised. Tests
  * drive their own "user pressed Back" navigations through [[browserBack]] / [[browserForward]],
  * which are deliberately NOT recorded, so the log only reflects what the router itself did.
  *
  * Install via [[HistorySpy.use]] / [[HistorySpy.useAsync]], which put the browser's own methods
  * back afterwards — leaving a spy installed would corrupt every later test in the suite, since
  * they all share one browser page.
  */
final class HistorySpy private () {

  import HistorySpy.PatchableHistory

  private val history: PatchableHistory = dom.window.history.asInstanceOf[PatchableHistory]

  private val realBack = history.back

  private val realForward = history.forward

  private val realGo = history.go

  private var reverseMoves: List[Int] = Nil

  /** Every traversal the router requested since installation / the last [[reset]], in order, as
    * a signed delta. `List(1)` means it undid a Back by going one forward; `Nil` means it left
    * the browser alone.
    */
  def moves: List[Int] = reverseMoves.reverse

  /** Zeroes the log, to scope assertions to one phase of a multi-step test. */
  def reset(): Unit = {
    reverseMoves = Nil
  }

  /** Navigates back for real, as if the user had pressed the browser's Back button.
    * Not recorded — see the class docs.
    */
  def browserBack(): Unit = realBack(history)

  /** Navigates forward for real, as if the user had pressed the browser's Forward button.
    * Not recorded — see the class docs.
    */
  def browserForward(): Unit = realForward(history)

  /** Jumps `delta` entries for real, as a user does from the browser's history dropdown.
    * Not recorded — see the class docs.
    */
  def browserGo(delta: Int): Unit = realGo(history, delta)

  private def record(delta: Int): Unit = {
    reverseMoves = delta :: reverseMoves
  }

  private def install(): Unit = {
    history.back = { (_: PatchableHistory) =>
      record(-1)
      browserBack()
    }
    history.forward = { (_: PatchableHistory) =>
      record(1)
      browserForward()
    }
    history.go = { (_: PatchableHistory, delta: Int) =>
      record(delta)
      realGo(history, delta)
    }
  }

  /** Puts the browser's own `back` / `forward` / `go` implementations back in place. */
  def restore(): Unit = {
    history.back = realBack
    history.forward = realForward
    history.go = realGo
  }
}

object HistorySpy {

  /** A view of `window.history` whose replaced methods are assignable.
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

    var go: js.ThisFunction1[PatchableHistory, Int, Unit] = js.native
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
