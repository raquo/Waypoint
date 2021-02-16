package com.raquo.waypoint

import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.eventbus.EventBus
import com.raquo.laminar.api.L
import com.raquo.waypoint.fixtures.TestPage._
import com.raquo.waypoint.fixtures.{TestPage, UnitSpec}
import org.scalajs.dom
import upickle.default._

object Context {
  implicit val rw = macroRW[Context]
}

case class Context(version: Option[String] = None, arch: Option[String] = None)

object TestPageCtx {
  implicit val rw = macroRW[TestPageCtx]

  implicit object PageWithCtx extends TPageWithContext[TestPageCtx, TestPage, Context] {
      def build(page: TestPage, context: Context): TestPageCtx = TestPageCtx(page, context)

      def context(p: TestPageCtx): Context = p.target

      def page(p: TestPageCtx): TestPage = p.page
  }
}

case class TestPageCtx(page: TestPage, target: Context)


object TestPageCtxRoutes extends ContextRoute[TestPageCtx, TestPage, Context, (Option[String], Option[String])](
    encodeContext = c => (c.version, c.arch),
    decodeContext = c => Context(c._1, c._2),
    param[String]("version").? & param[String]("arch").?
  ) {
    implicit val rw: ReadWriter[TestPageCtx] = macroRW[TestPageCtx]
}

class ContextRouterSpec extends UnitSpec {

  private val testOwner = new Owner {}

  private val libraryRoute = TestPageCtxRoutes[LibraryPage, Int](
    encode = _.libraryId,
    decode = arg => LibraryPage(libraryId = arg),
    pattern = root / "app" / "library" / segment[Int] / endOfSegments
  )

  private val textRoute = TestPageCtxRoutes[TextPage, String](
    encode = _.text,
    decode = arg => TextPage(text = arg),
    pattern = root / "app" / "test" / segment[String] / endOfSegments
  )

  private val noteRoute = TestPageCtxRoutes[NotePage, (Int, Int)](
    encode = page => (page.libraryId, page.noteId),
    decode = args => NotePage(libraryId = args._1, noteId = args._2, scrollPosition = 0),
    pattern = root / "app" / "library" / segment[Int] / "note" / segment[Int] / endOfSegments
  )

  private val searchRoute = TestPageCtxRoutes.onlyQuery[SearchPage, String](
    encode = page => page.query,
    decode = arg => SearchPage(arg),
    patternPath = (root / "search" / endOfSegments), 
    patternQuery = param[String]("query")
  )

  private val loginRoute = TestPageCtxRoutes.static(LoginPage, root / "hello" / "login" / endOfSegments)

  private val signupRoute = TestPageCtxRoutes.static(SignupPage, root / "signup" / "test" / endOfSegments)

  def makeRouter = new Router[TestPageCtx](
    initialUrl = "http://localhost/app/library/700",
    origin = "http://localhost", // dom.window.location.origin.get
    routes = libraryRoute :: textRoute :: noteRoute :: searchRoute :: loginRoute :: signupRoute :: Nil,
    owner = testOwner,
    $popStateEvent = L.windowEvents.onPopState,
    getPageTitle = _.toString,
    serializePage = page => write(page)(TestPageCtx.rw),
    deserializePage = pageStr => read(pageStr)(TestPageCtx.rw)
  )

  def makePushPage(router: Router[TestPageCtx]) = {
    val bus = new EventBus[TestPage]

    val _ = bus.events.withCurrentValueOf(router.$currentPage).foreach{ case (page, cPage) =>
    router.pushState(cPage.copy(page = page))
    }(testOwner)
    bus.writer
  }

  def makeChangeArch(router: Router[TestPageCtx]) = {
    val bus = new EventBus[Option[String]]

    val _ = bus.events.withCurrentValueOf(router.$currentPage).foreach{ case (arch, cPage) =>
    router.pushState(cPage.copy(target = cPage.target.copy(arch = arch)))
    }(testOwner)
    bus.writer
  }

  def makeChangeVersion(router: Router[TestPageCtx]) = {
    val bus = new EventBus[Option[String]]

    val _ = bus.events.withCurrentValueOf(router.$currentPage).foreach{ case (version, cPage) =>
    router.pushState(cPage.copy(target = cPage.target.copy(version = version)))
    }(testOwner)
    bus.writer
  }

  it("basic history operation") {

    // @Note jsdom is kinda limited

    val router = makeRouter

    val pushPage = makePushPage(router)

    val changeArch = makeChangeArch(router)

    val changeVersion = makeChangeVersion(router)

    router.$currentPage.now() shouldBe TestPageCtx(LibraryPage(700), Context())

    changeVersion.onNext(Some("v1"))

    router.$currentPage.now() shouldBe TestPageCtx(LibraryPage(700), Context(version = Some("v1")))

    pushPage.onNext(LoginPage)

    router.$currentPage.now() shouldBe TestPageCtx(LoginPage, Context(version = Some("v1")))

    changeArch.onNext(Some("x64"))

    router.$currentPage.now() shouldBe TestPageCtx(LoginPage, Context(version = Some("v1"), arch = Some("x64")))

    pushPage.onNext(LibraryPage(200))

    router.$currentPage.now() shouldBe TestPageCtx(LibraryPage(200), Context(version = Some("v1"), arch = Some("x64")))

    pushPage.onNext(SearchPage("is there anybody out there ?"))

    changeArch.onNext(None)

    router.$currentPage.now() shouldBe TestPageCtx(SearchPage("is there anybody out there ?"), Context(version = Some("v1"), arch = None))

    pushPage.onNext(LibraryPage(300))

    router.$currentPage.now() shouldBe TestPageCtx(LibraryPage(300), Context(version = Some("v1"), arch = None))

  }

  // @TODO[Test]
  //it ("SplitRender") {
  //
  //}
}
