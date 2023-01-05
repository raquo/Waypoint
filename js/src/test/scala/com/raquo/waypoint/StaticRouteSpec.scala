package com.raquo.waypoint

import com.raquo.airstream.ownership.Owner
import com.raquo.laminar.api._
import com.raquo.waypoint.fixtures.AppPage._
import com.raquo.waypoint.fixtures.{AppPage, UnitSpec}
import org.scalatest.Assertion
import upickle.default._
import scala.util.{Success, Try}

class StaticRouteSpec extends JsUnitSpec {

  private val testOwner = new Owner {}

  it("matches simple url") {

    val webHomeRoute = Route.static(HomePage, root / endOfSegments)
    val loginRoute = Route.static(LoginPage, root / "hello" / "login" / endOfSegments)
    val signupRoute = Route.static(SignupPage, root / "signup" / "test" / endOfSegments)

    val router = new Router[AppPage](
      routes = webHomeRoute :: loginRoute :: signupRoute :: Nil,
      getPageTitle = _.pageTitle,
      serializePage = page => write(page)(AppPage.rw),
      deserializePage = pageStr => read(pageStr)(AppPage.rw)
    )(
      popStateEvents = L.windowEvents(_.onPopState),
      owner = testOwner,
      origin = "http://localhost:8080",
      initialUrl = "http://localhost:8080/"
    )

    expectPageRelative(router, "/hello/login", Some(LoginPage))
    expectPageRelative(router, "/hello/notlogin", None)
    expectPageRelative(router, "/signup/test", Some(SignupPage))
    expectPageRelative(router, "/", Some(HomePage))

    expectPageAbsolute(router, "http://evil.com", None)
    expectPageAbsolute(router, "http://evil.com/", None)
    expectPageAbsolute(router, "http://evil.com/hello/login", None)
    expectPageAbsolute(router, "//evil.com", None)
    expectPageAbsolute(router, "//evil.com/", None)
    expectPageAbsolute(router, "//evil.com/hello/login", None)
    expectPageAbsolute(router, "//hello/login", None)
    expectPageAbsolute(router, "//", None)
    expectPageAbsolute(router, ".", None)
    expectPageAbsolute(router, "./", None)
    expectPageAbsolute(router, "./hello/login", None)

    expectPageRelativeFailure(router, "http://evil.com")
    expectPageRelativeFailure(router, "http://evil.com/")
    expectPageRelativeFailure(router, "http://evil.com/hello/login")
    expectPageRelativeFailure(router, "//evil.com")
    expectPageRelativeFailure(router, "//evil.com/")
    expectPageRelativeFailure(router, "//evil.com/hello/login")
    expectPageRelativeFailure(router, "//hello/login")
    expectPageRelativeFailure(router, "//")
    expectPageRelativeFailure(router, ".")
    expectPageRelativeFailure(router, "./")
    expectPageRelativeFailure(router, "./hello/login")

    @inline def urlForPage(page: AppPage): Option[String] = Try(router.relativeUrlForPage(page)).toOption

    // Routable pages
    urlForPage(LoginPage) shouldBe Some("/hello/login")
    urlForPage(SignupPage) shouldBe Some("/signup/test")
    urlForPage(HomePage) shouldBe Some("/")

    // Non routable page
    urlForPage(WorkspacePage("123")) shouldBe None
  }
}
