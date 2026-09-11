package com.raquo.waypoint

private[waypoint] class NavigationRequest[Page](
  val page: Page,
  val kind: NavigationKind,
  val relativeUrl: String,
  val pageTitle: String,
  val originIndex: Int,
  val destinationIndex: Int
)
