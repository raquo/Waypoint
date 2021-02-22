package com.raquo.waypoint.fixtures.context

import upickle.default._

case class SharedParams(version: Option[String] = None, lang: Option[String] = None)

object SharedParams {

  implicit val rw: ReadWriter[SharedParams] = macroRW[SharedParams]
}
