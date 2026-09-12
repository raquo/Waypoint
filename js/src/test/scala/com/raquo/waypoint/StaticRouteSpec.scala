package com.raquo.waypoint

import com.raquo.airstream.ownership.Owner
import com.raquo.waypoint.fixtures.{AppPage, PerSuiteOwner}
import com.raquo.waypoint.fixtures.AppPage._
import org.scalajs.dom
import upickle.default._

import scala.scalajs.js
import scala.util.Try

class StaticRouteSpec extends JsUnitSpec with PerSuiteOwner {


  it("matches simple url") {

    val webHomeRoute = Route.static(HomePage, root)
    val loginRoute = Route.static(LoginPage, root / "hello" / "login")
    val signupRoute = Route.static(SignupPage, root / "signup" / "test")

    // Set initial URL
    dom.window.history.pushState(new js.Object, "", "/")

    val codec = new HistoryCodec[AppPage](
      serializePage = page => write(page),
      deserializePage = pageStr => read[AppPage](pageStr),
    )

    val router = new Router[AppPage](
      routes = webHomeRoute :: loginRoute :: signupRoute :: Nil,
      codec = codec,
      getPageTitle = _.pageTitle,
      owner = testOwner,
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
