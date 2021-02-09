package com.raquo.waypoint

import com.raquo.airstream.ownership.Owner
import com.raquo.laminar.api.L
import com.raquo.waypoint.fixtures.TestPage._
import com.raquo.waypoint.fixtures.{TestPage, UnitSpec}
import org.scalatest.Assertion
import upickle.default._
import scala.util.{Success, Try}

class StaticRouteSpec extends UnitSpec {

  private val testOwner = new Owner {}

  it("matches simple url") {

    val origin = "http://localhost:8080"

    val webHomeRoute = Route.static(HomePage, root / endOfSegments)
    val loginRoute = Route.static(LoginPage, root / "hello" / "login" / endOfSegments)
    val signupRoute = Route.static(SignupPage, root / "signup" / "test" / endOfSegments)

    val router = new Router[TestPage](
      initialUrl = "http://localhost:8080/",
      origin = origin,
      routes = webHomeRoute :: loginRoute :: signupRoute :: Nil,
      owner = testOwner,
      $popStateEvent = L.windowEvents.onPopState,
      getPageTitle = _.pageTitle,
      serializePage = page => write(page)(TestPage.rw),
      deserializePage = pageStr => read(pageStr)(TestPage.rw)
    )

    expectPageRelative("/hello/login", Some(LoginPage))
    expectPageRelative("/hello/notlogin", None)
    expectPageRelative("/signup/test", Some(SignupPage))
    expectPageRelative("/", Some(HomePage))

    expectPageAbsolute("http://evil.com", None)
    expectPageAbsolute("http://evil.com/", None)
    expectPageAbsolute("http://evil.com/hello/login", None)
    expectPageAbsolute("//evil.com", None)
    expectPageAbsolute("//evil.com/", None)
    expectPageAbsolute("//evil.com/hello/login", None)
    expectPageAbsolute("//hello/login", None)
    expectPageAbsolute("//", None)
    expectPageAbsolute(".", None)
    expectPageAbsolute("./", None)
    expectPageAbsolute("./hello/login", None)

    expectPageRelativeFailure("http://evil.com")
    expectPageRelativeFailure("http://evil.com/")
    expectPageRelativeFailure("http://evil.com/hello/login")
    expectPageRelativeFailure("//evil.com")
    expectPageRelativeFailure("//evil.com/")
    expectPageRelativeFailure("//evil.com/hello/login")
    expectPageRelativeFailure("//hello/login")
    expectPageRelativeFailure("//")
    expectPageRelativeFailure(".")
    expectPageRelativeFailure("./")
    expectPageRelativeFailure("./hello/login")

    @inline def urlForPage(page: TestPage): Option[String] = Try(router.relativeUrlForPage(page)).toOption

    // Routable pages
    urlForPage(LoginPage) shouldBe Some("/hello/login")
    urlForPage(SignupPage) shouldBe Some("/signup/test")
    urlForPage(HomePage) shouldBe Some("/")

    // Non routable page
    urlForPage(WorkspacePage("123")) shouldBe None

    def expectPageAbsolute(url: String, expectedPage: Option[TestPage]): Assertion = {
      withClue("expectPageAbsolute: " + url + " ? " + expectedPage.toString + "\n") {
        Try(router.pageForAbsoluteUrl(url)) shouldBe Success(expectedPage)
      }
    }

    def expectPageRelative(url: String, expectedPage: Option[TestPage]): Assertion = {
      withClue("expectPageRelative: " + url + " ? " + expectedPage.toString + "\n") {
        Try(router.pageForRelativeUrl(url)) shouldBe Success(expectedPage)
      }
    }

    def expectPageAbsoluteFailure(url: String): Assertion = {
      withClue("expectPageAbsoluteFailure: " + url + " ? failure\n") {
        Try(router.pageForAbsoluteUrl(url)).toOption shouldBe None
      }
    }

    def expectPageRelativeFailure(url: String): Assertion = {
      withClue("expectPageRelativeFailure: " + url + " ? failure\n") {
        Try(router.pageForRelativeUrl(url)).toOption shouldBe None
      }
    }
  }
}
