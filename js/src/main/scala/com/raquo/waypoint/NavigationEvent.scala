package com.raquo.waypoint

/**
  * @param fromPage       the page the app is currently showing (the page we'd navigate away from)
  * @param toPage         the page the app would navigate to
  * @param kind           the kind of navigation
  * @param toRelativeUrl  the relative URL of the target (for [[NavigationKind.HashChange]] this includes the new fragment)
  */
final case class NavigationEvent[Page](
  fromPage: Page,
  toPage: Page,
  kind: NavigationKind,
  toRelativeUrl: String
)
