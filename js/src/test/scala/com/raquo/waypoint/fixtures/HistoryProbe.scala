package com.raquo.waypoint.fixtures

import org.scalajs.dom

import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.timers

// #Note[AI-impl]
/** Read-only view of the browser state that the router maintains, plus a way to wait for the
  * browser to finish processing a real history navigation.
  */
object HistoryProbe {

  /** Default time budget for a real history navigation to produce its `popstate` event(s). */
  val defaultTimeoutMs: Double = 2000

  /** Default quiet period observed after the expected `popstate` events, to catch unexpected
    * extra ones. Costs this much per [[afterPopStates]] call, which is worth it: the events it
    * catches are ones the end-state assertions can not see.
    */
  val defaultSettleMs: Double = 20

  /** Absolute URL of the current history entry, e.g. "http://localhost:52341/signup/test". */
  def url: String = dom.window.location.href

  /** Number of entries in the session history. Shared by the whole suite (one browser page),
    * so only ever assert on the *difference* across a navigation, never on the value itself.
    */
  def length: Int = dom.window.history.length

  /** Raw History API state of the current entry. The router always writes a String. */
  def rawState: Option[String] = {
    (dom.window.history.state: Any) match {
      case str: String => Some(str)
      case _ => None
    }
  }

  /** The navigation index that the router stamped onto the current history entry.
    *
    * It is the `index` half of the `"index|serializedPage"` state string, and it is what lets
    * the router tell a Back from a Forward — including between two entries with identical URLs,
    * which nothing else can distinguish.
    */
  def navigationIndex: Option[Int] = rawState.flatMap(splitState(_)._1)

  /** The serialized page that the router stamped onto the current history entry. */
  def serializedPage: Option[String] = rawState.flatMap(splitState(_)._2)

  /** Runs `action`, then resolves once the window has fired exactly `count` `popstate` events.
    *
    * The count is the point of the helper: an allowed browser navigation produces exactly one
    * popstate, whereas a *prevented* one produces two — the navigation itself, and the
    * `history.back()` / `.forward()` the router issues to undo it (which the router then swallows
    * via `suppressNextPopState`).
    *
    * "Exactly" is enforced in both directions. Too few events, and the call times out. Too many,
    * and it fails as soon as the extra one arrives — which is why it keeps listening for
    * `settleMs` after the expected events instead of resolving immediately. An unexpected extra
    * navigation is easy for the final-state assertions to miss: an extra Back followed by an extra
    * Forward leaves the page, the URL and the index exactly where the test expects them.
    *
    * The listener is registered here, i.e. after the router registered its own in its constructor,
    * so it runs after the router has already handled the event and the router's observable state
    * is settled by the time the Future resolves.
    */
  def afterPopStates(
    count: Int,
    timeoutMs: Double = defaultTimeoutMs,
    settleMs: Double = defaultSettleMs
  )(
    action: => Unit
  ): Future[Unit] = {
    require(count > 0, s"afterPopStates needs a positive count, got $count")

    val promise = Promise[Unit]()
    var seen = 0
    var timeoutHandle: Option[timers.SetTimeoutHandle] = None
    var settleHandle: Option[timers.SetTimeoutHandle] = None

    def stopListening(handler: js.Function1[dom.PopStateEvent, Unit]): Unit = {
      dom.window.removeEventListener("popstate", handler)
      timeoutHandle.foreach(timers.clearTimeout)
      settleHandle.foreach(timers.clearTimeout)
    }

    lazy val handler: js.Function1[dom.PopStateEvent, Unit] = { _ =>
      seen += 1
      if (seen == count) {
        settleHandle = Some(
          timers.setTimeout(settleMs) {
            stopListening(handler)
            promise.trySuccess(())
            ()
          }
        )
      } else if (seen > count) {
        stopListening(handler)
        promise.tryFailure(
          new AssertionError(
            s"Expected exactly $count popstate event(s), but a further one arrived (seen: $seen). Current URL: $url"
          )
        )
        ()
      }
    }

    dom.window.addEventListener("popstate", handler)

    timeoutHandle = Some(
      timers.setTimeout(timeoutMs) {
        stopListening(handler)
        promise.tryFailure(
          new AssertionError(
            s"Timed out after ${timeoutMs.toInt}ms waiting for $count popstate event(s), saw $seen. Current URL: $url"
          )
        )
        ()
      }
    )

    action

    promise.future
  }

  private def splitState(state: String): (Option[Int], Option[String]) = {
    val separatorIndex = state.indexOf('|')
    if (separatorIndex > 0) {
      (state.substring(0, separatorIndex).toIntOption, Some(state.substring(separatorIndex + 1)))
    } else {
      (None, None)
    }
  }
}
