package com.raquo.waypoint.fixtures

import com.raquo.waypoint.Route
import org.scalactic
import org.scalatest.Assertion
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{Success, Try}

trait UnitSpec extends AnyFunSpec with Matchers {

  def expectPageAbsolute[P](
    route: Route[_, _], origin: String, url: String, expectedPage: Option[P]
  )(
    implicit pos: scalactic.source.Position
  ): Assertion = {
    Try(route.pageForAbsoluteUrl(origin, url)) shouldBe Success(expectedPage)
  }

  def expectPageRelative[P](
    route: Route[_, _], origin: String, url: String, expectedPage: Option[P]
  )(
    implicit pos: scalactic.source.Position
  ): Assertion = {
    Try(route.pageForRelativeUrl(origin, url)) shouldBe Success(expectedPage)
  }

  def expectPageAbsoluteFailure(
    route: Route[_, _], origin: String, url: String
  )(
    implicit pos: scalactic.source.Position
  ): Assertion = {
    Try(route.pageForAbsoluteUrl(origin, url)).toOption shouldBe None
  }

  def expectPageRelativeFailure(
    route: Route[_, _], origin: String, url: String
  )(
    implicit pos: scalactic.source.Position
  ): Assertion = {
    Try(route.pageForRelativeUrl(origin, url)).toOption shouldBe None
  }

}
