package com.raquo.waypoint.fixtures

import com.raquo.laminar.api.L._
import com.raquo.waypoint._
import com.raquo.waypoint.fixtures.AppPage._
import org.scalactic
import org.scalajs.dom
import org.scalatest.{Assertion, Suite}
import org.scalatest.matchers.should.Matchers
import upickle.default._

import scala.scalajs.js

/** Routes, a router factory, and whole-state assertions shared by the navigation specs.
  *
  * The entire test suite runs in a single browser page, and therefore shares a single session
  * history, so this fixture is built around keeping tests independent in spite of that.
  */
trait AppRouterFixture
extends Matchers
with PerTestOwner { this: Suite =>

  protected val libraryRoute: Route[LibraryPage, Int] = Route[LibraryPage, Int](
    encode = _.libraryId,
    decode = arg => LibraryPage(libraryId = arg),
    pattern = root / "app" / "library" / segment[Int]
  )

  protected val textRoute: Route[TextPage, String] = Route[TextPage, String](
    encode = _.text,
    decode = arg => TextPage(text = arg),
    pattern = root / "app" / "test" / segment[String]
  )

  protected val noteRoute: Route[NotePage, (Int, Int)] = Route[NotePage, (Int, Int)](
    encode = page => (page.libraryId, page.noteId),
    decode = args => NotePage(libraryId = args._1, noteId = args._2, scrollPosition = 0),
    pattern = root / "app" / "library" / segment[Int] / "note" / segment[Int]
  )

  protected val searchRoute: Route[SearchPage, String] = Route.onlyQuery[SearchPage, String](
    encode = page => page.query,
    decode = arg => SearchPage(arg),
    pattern = (root / "search") ? param[String]("query")
  )

  protected val loginRoute: Route[LoginPage.type, Unit] = Route.static(LoginPage, root / "hello" / "login")

  protected val signupRoute: Route[SignupPage.type, Unit] = Route.static(SignupPage, root / "signup" / "test")

  protected val routes: List[Route[_ <: AppPage, _]] =
    libraryRoute :: textRoute :: noteRoute :: searchRoute :: loginRoute :: signupRoute :: Nil

  /** Real origin of the test page. Under Selenium it includes the port of the server that serves
    * the test bundle, which is assigned at runtime, so tests must never hardcode it.
    */
  protected lazy val origin: String = dom.window.location.origin

  protected def serializePage(page: AppPage): String = write(page)(AppPage.rw)

  protected def makeRouter(
    preventNavigation: NavigationEvent[AppPage] => EventStream[Boolean] = _ => EventStream.fromValue(false),
    routeFallback: String => AppPage = url => throw new Exception(s"Test router has no route for URL: $url")
  ): Router[AppPage] = {
    // Establish a known starting URL. `pushState` also drops any forward entries left over from a
    // previous test, so every test starts at the end of the session history, where `history.back()`
    // is available and `history.forward()` is not.
    dom.window.history.pushState(new js.Object, "", "/app/library/700")

    val codec = new HistoryCodec[AppPage](
      serializePage = serializePage,
      deserializePage = pageStr => read(pageStr)(AppPage.rw),
    )

    new Router[AppPage](
      routes = routes,
      codec = codec,
      getPageTitle = _.toString,
      routeFallback = routeFallback,
      preventNavigation = preventNavigation,
      owner = testOwner
    )
  }

  /** Asserts the router's entire observable state at once: the page it renders, the URL the user
    * sees, and the navigation index and page that it stamped onto the current history entry.
    *
    * Tests assert all of it after every step, so that a navigation which only half happened —
    * page updated but URL not, or URL updated but the index left behind — can not pass.
    */
  protected def expectCurrent(
    router: Router[AppPage],
    page: AppPage,
    relativeUrl: String,
    navigationIndex: Int
  )(implicit
    pos: scalactic.source.Position
  ): Assertion = {
    router.currentPageSignal.now() shouldBe page
    dom.window.location.href shouldBe (origin + relativeUrl)
    HistoryProbe.navigationIndex shouldBe Some(navigationIndex)
    HistoryProbe.serializedPage shouldBe Some(serializePage(page))
  }
}
