package com.raquo.waypoint

import com.raquo.waypoint.fixtures.{TestPage, UnitSpec}
import com.raquo.waypoint.fixtures.TestPage.{HomePage, TextPage}
import upickle.default._

class PageSpec extends UnitSpec {

  it("read write") {

    write(HomePage) shouldBe "{\"$type\":\"com.raquo.waypoint.fixtures.TestPage.HomePage\"}"

    write[TestPage](TextPage("abc123")) shouldBe "{\"$type\":\"com.raquo.waypoint.fixtures.TestPage.TextPage\",\"text\":\"abc123\"}"

    write[TestPage](HomePage)(TestPage.rw) shouldBe "{\"$type\":\"com.raquo.waypoint.fixtures.TestPage.HomePage\"}"

  }
}
