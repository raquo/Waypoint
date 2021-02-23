package com.raquo.waypoint

import com.raquo.airstream.ownership.Owner
import com.raquo.laminar.api.L
import com.raquo.waypoint.fixtures.AppPage._
import com.raquo.waypoint.fixtures.{AppPage, UnitSpec}
import org.scalajs.dom
import upickle.default._

class RouterSpec extends UnitSpec {

  private val testOwner = new Owner {}

  private val libraryRoute = Route[LibraryPage, Int](
    encode = _.libraryId,
    decode = arg => LibraryPage(libraryId = arg),
    pattern = root / "app" / "library" / segment[Int] / endOfSegments
  )

  private val textRoute = Route[TextPage, String](
    encode = _.text,
    decode = arg => TextPage(text = arg),
    pattern = root / "app" / "test" / segment[String] / endOfSegments
  )

  private val noteRoute = Route[NotePage, (Int, Int)](
    encode = page => (page.libraryId, page.noteId),
    decode = args => NotePage(libraryId = args._1, noteId = args._2, scrollPosition = 0),
    pattern = root / "app" / "library" / segment[Int] / "note" / segment[Int] / endOfSegments
  )

  private val searchRoute = Route.onlyQuery[SearchPage, String](
    encode = page => page.query,
    decode = arg => SearchPage(arg),
    pattern = (root / "search" / endOfSegments) ? param[String]("query")
  )

  private val loginRoute = Route.static(LoginPage, root / "hello" / "login" / endOfSegments)

  private val signupRoute = Route.static(SignupPage, root / "signup" / "test" / endOfSegments)

  def makeRouter = new Router[AppPage](
    routes = libraryRoute :: textRoute :: noteRoute :: searchRoute :: loginRoute :: signupRoute :: Nil,
    getPageTitle = _.toString,
    serializePage = page => write(page)(AppPage.rw),
    deserializePage = pageStr => read(pageStr)(AppPage.rw)
  )(
    $popStateEvent = L.windowEvents.onPopState,
    owner = testOwner,
    initialUrl = "http://localhost/app/library/700",
    origin = "http://localhost"
  )

  it("basic history operation") {

    // @Note jsdom is kinda limited

    val router = makeRouter

    router.$currentPage.now() shouldBe LibraryPage(700)

    // --

    router.pushState(LoginPage)

    dom.document.location.href shouldBe "http://localhost/hello/login"
    router.$currentPage.now() shouldBe LoginPage

    // --

    router.replaceState(LibraryPage(100))

    dom.document.location.href shouldBe "http://localhost/app/library/100"
    router.$currentPage.now() shouldBe LibraryPage(100)
  }

  // @TODO[Test]
  //it ("SplitRender") {
  //
  //}
}
