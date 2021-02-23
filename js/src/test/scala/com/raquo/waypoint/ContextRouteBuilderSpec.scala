package com.raquo.waypoint

import com.raquo.airstream.core.Observer
import com.raquo.airstream.ownership.Owner
import com.raquo.laminar.api.L
import com.raquo.waypoint.fixtures.AppPage._
import com.raquo.waypoint.fixtures.context.{PageBundle, SharedParams}
import com.raquo.waypoint.fixtures.{AppPage, UnitSpec}
import upickle.default._

class ContextRouteBuilderSpec extends UnitSpec {

  private val testOwner = new Owner {}

  val RouteWithContext = new ContextRouteBuilder[PageBundle, AppPage, SharedParams, (Option[String], Option[String])](
    encodeContext = c => (c.version, c.lang),
    decodeContext = c => SharedParams(c._1, c._2),
    contextPattern = param[String]("version").? & param[String]("lang").?,
    pageFromBundle = _.page,
    contextFromBundle = _.context,
    bundleFromPageWithContext = PageBundle(_, _)
  )

  private val libraryRoute = RouteWithContext[LibraryPage, Int](
    encode = _.libraryId,
    decode = arg => LibraryPage(libraryId = arg),
    pattern = root / "app" / "library" / segment[Int] / endOfSegments
  )

  private val textRoute = RouteWithContext[TextPage, String](
    encode = _.text,
    decode = arg => TextPage(text = arg),
    pattern = root / "app" / "test" / segment[String] / endOfSegments
  )

  private val noteRoute = RouteWithContext[NotePage, (Int, Int)](
    encode = page => (page.libraryId, page.noteId),
    decode = args => NotePage(libraryId = args._1, noteId = args._2, scrollPosition = 0),
    pattern = root / "app" / "library" / segment[Int] / "note" / segment[Int] / endOfSegments
  )

  private val searchRoute = RouteWithContext.onlyQuery[SearchPage, String](
    encode = page => page.query,
    decode = arg => SearchPage(arg),
    pattern = (root / "search" / endOfSegments) ? param[String]("query")
  )

  private val loginRoute = RouteWithContext.static(LoginPage, root / "hello" / "login" / endOfSegments)

  private val signupRoute = RouteWithContext.static(SignupPage, root / "signup" / "test" / endOfSegments)

  def makeRouter = new Router[PageBundle](
    routes = libraryRoute :: textRoute :: noteRoute :: searchRoute :: loginRoute :: signupRoute :: Nil,
    getPageTitle = _.toString,
    serializePage = page => write(page)(PageBundle.rw),
    deserializePage = pageStr => read(pageStr)(PageBundle.rw)
  )(
    $popStateEvent = L.windowEvents.onPopState,
    owner = testOwner,
    origin = "http://localhost", // dom.window.location.origin.get
    initialUrl = "http://localhost/app/library/700"
  )

  private def makePageUpdater[A](
    router: Router[PageBundle],
    mod: (PageBundle, A) => PageBundle
  ): Observer[A] = {
    Observer { v =>
      val currPage = router.$currentPage.now()
      router.pushState(mod(currPage, v))
    }
  }

  def makePushPage(router: Router[PageBundle]): Observer[AppPage] = {
    makePageUpdater(router, (p: PageBundle, page: AppPage) => {
      p.copy(page = page)
    })
  }

  def makeChangeLang(router: Router[PageBundle]): Observer[Option[String]] = {
    makePageUpdater(router, (p: PageBundle, lang: Option[String]) => {
      p.copy(context = p.context.copy(lang = lang))
    })
  }

  def makeChangeVersion(router: Router[PageBundle]): Observer[Option[String]] = {
    makePageUpdater(router, (p: PageBundle, version: Option[String]) => {
      p.copy(context = p.context.copy(version = version))
    })
  }

  it("basic history operation") {

    // @Note jsdom is kinda limited

    val router = makeRouter

    val pushPage = makePushPage(router)

    val changeLang = makeChangeLang(router)

    val changeVersion = makeChangeVersion(router)

    router.$currentPage.now() shouldBe PageBundle(LibraryPage(700), SharedParams())

    // @TODO[API] What should we do about trailing question marks when the query is empty? Probably a URL-DSL question.
    router.relativeUrlForPage(router.$currentPage.now()) shouldBe "/app/library/700?"

    // --

    changeVersion.onNext(Some("v1"))

    router.$currentPage.now() shouldBe PageBundle(LibraryPage(700), SharedParams(version = Some("v1")))

    router.relativeUrlForPage(router.$currentPage.now()) shouldBe "/app/library/700?version=v1"

    // --

    pushPage.onNext(LoginPage)

    router.$currentPage.now() shouldBe PageBundle(LoginPage, SharedParams(version = Some("v1")))

    router.relativeUrlForPage(router.$currentPage.now()) shouldBe "/hello/login?version=v1"

    // --

    changeLang.onNext(Some("ru"))

    router.$currentPage.now() shouldBe PageBundle(LoginPage, SharedParams(version = Some("v1"), lang = Some("ru")))

    router.relativeUrlForPage(router.$currentPage.now()) shouldBe "/hello/login?version=v1&lang=ru"

    // --

    pushPage.onNext(LibraryPage(200))

    router.$currentPage.now() shouldBe PageBundle(LibraryPage(200), SharedParams(version = Some("v1"), lang = Some("ru")))

    router.relativeUrlForPage(router.$currentPage.now()) shouldBe "/app/library/200?version=v1&lang=ru"

    // --

    pushPage.onNext(SearchPage("is there anybody out there ?"))

    changeLang.onNext(None)

    router.$currentPage.now() shouldBe PageBundle(SearchPage("is there anybody out there ?"), SharedParams(version = Some("v1"), lang = None))

    router.relativeUrlForPage(router.$currentPage.now()) shouldBe "/search?query=is%20there%20anybody%20out%20there%20%3F&version=v1"

    // --

    pushPage.onNext(LibraryPage(300))

    router.$currentPage.now() shouldBe PageBundle(LibraryPage(300), SharedParams(version = Some("v1"), lang = None))

    router.relativeUrlForPage(router.$currentPage.now()) shouldBe "/app/library/300?version=v1"

  }

  // @TODO[Test]
  //it ("SplitRender") {
  //
  //}
}
