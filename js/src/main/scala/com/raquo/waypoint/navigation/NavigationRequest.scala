package com.raquo.waypoint.navigation

private[waypoint] class NavigationRequest[Page](
  val page: Page,
  val kind: NavigationKind,
  val relativeUrl: String,
  val pageTitle: String,
  val destinationIndex: Int
)
