package com.raquo.waypoint.navigation

sealed trait NavigationKind

object NavigationKind {

  /** This navigation request was initiated by Waypoint.
    * We do not update the URL or the browser history until we successfully process it.
    * If we want to prevent this navigation, we can just ignore it.
    */
  sealed trait RouterInitiated extends NavigationKind

  /** This navigation request was initiated from the browser
    * (e.g. suer clicking the browser "back" button).
    * The URL and browser history are already been updated by the time we receive this request.
    * If we want to "prevent" this navigation, we need to revert those changes.
    */
  sealed trait BrowserInitiated extends NavigationKind

  // --

  /** App-initiated navigation that will create a new history record ([[Router.pushState]]). */
  case object Push extends RouterInitiated

  /** App-initiated navigation that will replace the current history record ([[Router.replaceState]]). */
  case object Replace extends RouterInitiated

  /** Browser "back" navigation. */
  case object Back extends BrowserInitiated

  /** Browser "forward" navigation. */
  case object Forward extends BrowserInitiated

  /** Navigation caused by a change to the URL's hash / fragment. */
  case object HashChange extends BrowserInitiated
}
