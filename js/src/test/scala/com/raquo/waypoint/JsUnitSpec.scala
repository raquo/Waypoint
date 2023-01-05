package com.raquo.waypoint

import com.raquo.waypoint.fixtures.UnitSpec
import org.scalactic
import org.scalatest.Assertion

import scala.util.{Success, Try}

class JsUnitSpec extends UnitSpec {

  def expectPageAbsolute[P](
    router: Router[P], url: String, expectedPage: Option[P]
  )(
    implicit pos: scalactic.source.Position
  ): Assertion = {
    Try(router.pageForAbsoluteUrl(url)) shouldBe Success(expectedPage)
  }

  def expectPageRelative[P](
    router: Router[P], url: String, expectedPage: Option[P]
  )(
    implicit pos: scalactic.source.Position
  ): Assertion = {
    Try(router.pageForRelativeUrl(url)) shouldBe Success(expectedPage)
  }

  def expectPageAbsoluteFailure[P](
    router: Router[P], url: String
  )(
    implicit pos: scalactic.source.Position
  ): Assertion = {
    Try(router.pageForAbsoluteUrl(url)).toOption shouldBe None
  }

  def expectPageRelativeFailure[P](
    router: Router[P], url: String
  )(
    implicit pos: scalactic.source.Position
  ): Assertion = {
    Try(router.pageForRelativeUrl(url)).toOption shouldBe None
  }
}
