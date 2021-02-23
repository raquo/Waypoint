package com.raquo.waypoint.fixtures.context

import com.raquo.waypoint.fixtures.AppPage

import upickle.default._

case class PageBundle(page: AppPage, context: SharedParams)

object PageBundle {

  implicit val rw: ReadWriter[PageBundle] = macroRW[PageBundle]
}
