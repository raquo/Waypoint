package com.raquo.waypoint

import com.raquo.waypoint.fixtures.{AppPage, UnitSpec}
import com.raquo.waypoint.fixtures.AppPage.{HomePage, TextPage}
import upickle.default._

class PageSpec extends UnitSpec {

  it("read write") {

    write(HomePage) shouldBe "{\"$type\":\"com.raquo.waypoint.fixtures.AppPage.HomePage\"}"

    write[AppPage](TextPage("abc123")) shouldBe "{\"$type\":\"com.raquo.waypoint.fixtures.AppPage.TextPage\",\"text\":\"abc123\"}"

    write[AppPage](HomePage)(AppPage.rw) shouldBe "{\"$type\":\"com.raquo.waypoint.fixtures.AppPage.HomePage\"}"

  }
}
