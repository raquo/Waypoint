package com.raquo.waypoint.navigation

/** Where an undo of a prevented browser navigation is meant to land.
  *
  * @see [[Router.pendingUndoOpt]]
  */
final class UndoExpectation(
  val index: Int,
  val relativeUrl: String
) {

  /** @param entryIndexOpt index on the entry the browser just moved to, if it has one */
  def matches(entryIndexOpt: Option[Int], entryRelativeUrl: String): Boolean = {
    entryIndexOpt match {
      case Some(entryIndex) =>
        entryIndex == index
      case None =>
        // An entry with no state of ours has no index, so its URL is all we can match on.
        // Happens when the app started on a URL matching no route (initial entry never
        // stamped), and for entries the browser created itself for a hash change.
        entryRelativeUrl == relativeUrl
    }
  }
}
