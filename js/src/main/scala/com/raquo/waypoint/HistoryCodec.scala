package com.raquo.waypoint

import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.util.Try

/**
  * @param serializePage       - encode page into a History API state record. Return any string here (JSON string is ok)
  *                              This is called any time you trigger navigation to a page.
  *
  * @param deserializePage     - parse the result of [[serializePage]] back into a page.
  *                              This is called when user navigates using browser back / forward buttons and we load
  *                              history state
  *                              If you throw here, [[deserializeFallback]] will be called instead.
  *
  * @param deserializeFallback - receives raw state from History API as input and returns a page to render when
  *                              the entry in the history can not be deserialized using [[deserializePage]].
  *                              If you want to perform any logic (such as redirects) in case of this fallback,
  *                              put it in the renderer (whatever you have responding to router.\$currentPage).
  *
  */
class HistoryCodec[BasePage](
  serializePage: BasePage => String,
  deserializePage: String => BasePage,
  val deserializeFallback: Any => BasePage = HistoryCodec.throwOnInvalidState, // ()
) {

  def encodeState(
    navigationIndex: Int,
    page: BasePage,
  ): String = {
    s"$navigationIndex|${serializePage(page)}"
  }

  /** (rawHistoryApiState -> Option(navigationIndex, page) */
  def decodeState(state: Any): Option[(Int, BasePage)] = {
    (state: Any) match {
      case stateStr: String =>
        decodeIndex(stateStr).map { idx =>
          val separatorIndex = stateStr.indexOf('|')
          val decodedPageTry =
            Try(deserializePage(stateStr.substring(separatorIndex + 1)))
          idx -> decodedPageTry.getOrElse(deserializeFallback(state))
        }
      case _ =>
        None
    }
  }

  /** (rawHistoryApiState -> Option(navigationIndex)
    *
    * Reads only the index, never the page. Lets the router recognize the entry an undo
    * lands on, without running the app's deserialization — which must not throw here, as
    * the default [[HistoryCodec.throwOnInvalidState]] fallback otherwise would.
    */
  def decodeIndex(state: Any): Option[Int] = {
    (state: Any) match {
      case stateStr: String =>
        val separatorIndex = stateStr.indexOf('|')
        if (separatorIndex > 0) {
          stateStr.substring(0, separatorIndex).toIntOption
        } else {
          None
        }
      case _ =>
        None
    }
  }
}

object HistoryCodec {

  /** History State can be any serializable JS value. Could be a string, a plain object, etc.
    * Waypoint itself uses a "`index|data`" string, where `data` is the result of `serializePage`.
    */
  private val throwOnInvalidState = (state: Any) => {
    // @TODO `null` is needed to work around https://github.com/lampepfl/dotty/issues/11943, drop it later
    throw new WaypointException("Unable to deserialize history state: " + JSON.stringify(state.asInstanceOf[js.Any], null))
  }
}
