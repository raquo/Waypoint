package com.raquo.waypoint

/** This private event is fired when a route is changed. */
class RouteEvent[BasePage](
  val page: BasePage,
  val stateData: String,
  val pageTitle: String,
  val url: String,
  val replace: Boolean,
  val fromPopState: Boolean
)
