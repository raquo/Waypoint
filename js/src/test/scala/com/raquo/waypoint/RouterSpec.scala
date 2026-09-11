package com.raquo.waypoint

import com.raquo.airstream.ownership.Owner
import com.raquo.laminar.api.L._
import com.raquo.waypoint.fixtures.{AppPage, UnitSpec}
import com.raquo.waypoint.fixtures.AppPage._
import org.scalajs.dom
import upickle.default._

import scala.scalajs.js

class RouterSpec extends UnitSpec {

  private val testOwner = new Owner {}

  private val libraryRoute = Route[LibraryPage, Int](
    encode = _.libraryId,
    decode = arg => LibraryPage(libraryId = arg),
    pattern = root / "app" / "library" / segment[Int]
  )

  private val textRoute = Route[TextPage, String](
    encode = _.text,
    decode = arg => TextPage(text = arg),
    pattern = root / "app" / "test" / segment[String]
  )

  private val noteRoute = Route[NotePage, (Int, Int)](
    encode = page => (page.libraryId, page.noteId),
    decode = args => NotePage(libraryId = args._1, noteId = args._2, scrollPosition = 0),
    pattern = root / "app" / "library" / segment[Int] / "note" / segment[Int]
  )

  private val searchRoute = Route.onlyQuery[SearchPage, String](
    encode = page => page.query,
    decode = arg => SearchPage(arg),
    pattern = (root / "search") ? param[String]("query")
  )

  private val loginRoute = Route.static(LoginPage, root / "hello" / "login")

  private val signupRoute = Route.static(SignupPage, root / "signup" / "test")

  // Real origin of the test page (e.g. under Selenium this includes the
  // materialization server's port, whereas jsdom defaults to no port).
  private val origin = dom.window.location.origin

  def makeRouter(
    preventNavigation: NavigationEvent[AppPage] => EventStream[Boolean] = (_: NavigationEvent[AppPage]) => EventStream.fromValue(false),
    popStateEvents: EventStream[dom.PopStateEvent] = windowEvents(_.onPopState)
  ): Router[AppPage] = {
    // Set initial URL
    dom.window.history.pushState(new js.Object, "", "/app/library/700")

    val codec = new HistoryCodec[AppPage](
      serializePage = page => write(page)(AppPage.rw),
      deserializePage = pageStr => read(pageStr)(AppPage.rw),
    )

    new Router[AppPage](
      routes = libraryRoute :: textRoute :: noteRoute :: searchRoute :: loginRoute :: signupRoute :: Nil,
      codec = codec,
      getPageTitle = _.toString,
      preventNavigation = preventNavigation,
      popStateEvents = popStateEvents,
      owner = testOwner
    )
  }

  private def popState(state: js.Any): dom.PopStateEvent = {
    js.Dynamic.literal("type" -> "popstate", "state" -> state).asInstanceOf[dom.PopStateEvent]
  }

  private def stampedState(idx: Int, page: AppPage): String = s"$idx|${write(page)(AppPage.rw)}"

  /** Overrides `history.back` / `history.forward` recording call counts, returns a way to read them. */
  private def spyOnHistory(): (() => Int, () => Int, () => Unit) = {
    val history = dom.window.history.asInstanceOf[js.Dynamic]
    val origForward = history.selectDynamic("forward")
    val origBack = history.selectDynamic("back")

    var forwardCalls = 0
    var backCalls = 0

    history.updateDynamic("forward")((() => { forwardCalls += 1 }): js.Function0[Unit])
    history.updateDynamic("back")((() => { backCalls += 1 }): js.Function0[Unit])

    (
      () => forwardCalls,
      () => backCalls,
      () => {
        history.updateDynamic("forward")(origForward)
        history.updateDynamic("back")(origBack)
      }
    )
  }

  it("basic history operation") {

    assert(origin == "http://localhost:8873") // selenium

    // @Note jsdom is kinda limited

    val router = makeRouter()

    router.currentPageSignal.now() shouldBe LibraryPage(700)

    // --

    router.pushState(LoginPage)

    dom.document.location.href shouldBe s"$origin/hello/login"
    router.currentPageSignal.now() shouldBe LoginPage

    // --

    router.replaceState(LibraryPage(100))

    dom.document.location.href shouldBe s"$origin/app/library/100"
    router.currentPageSignal.now() shouldBe LibraryPage(100)
  }

  it("async guard defers navigation until it resolves (allow)") {

    val guardBus = new EventBus[Boolean]
    val router = makeRouter(preventNavigation = _ => guardBus.events)

    router.currentPageSignal.now() shouldBe LibraryPage(700)

    router.pushState(LoginPage)

    // Guard has not resolved yet, so navigation is still pending
    dom.document.location.href shouldBe s"$origin/app/library/700"
    router.currentPageSignal.now() shouldBe LibraryPage(700)

    guardBus.emit(false) // allow

    dom.document.location.href shouldBe s"$origin/hello/login"
    router.currentPageSignal.now() shouldBe LoginPage
  }

  it("guard can prevent pushState") {

    val router = makeRouter(preventNavigation = _ => EventStream.fromValue(true))

    router.currentPageSignal.now() shouldBe LibraryPage(700)

    router.pushState(LoginPage)

    dom.document.location.href shouldBe s"$origin/app/library/700"
    router.currentPageSignal.now() shouldBe LibraryPage(700)
  }

  it("guard can prevent replaceState") {

    val router = makeRouter(preventNavigation = _ => EventStream.fromValue(true))

    router.currentPageSignal.now() shouldBe LibraryPage(700)

    router.replaceState(LoginPage)

    dom.document.location.href shouldBe s"$origin/app/library/700"
    router.currentPageSignal.now() shouldBe LibraryPage(700)
  }

  it("rejects other navigations while a guard is pending") {

    val guardBus = new EventBus[Boolean]
    val router = makeRouter(preventNavigation = _ => guardBus.events)

    router.pushState(LoginPage) // pending
    router.pushState(SignupPage) // rejected while pending

    guardBus.emit(false) // allow first navigation

    dom.document.location.href shouldBe s"$origin/hello/login"
    router.currentPageSignal.now() shouldBe LoginPage
  }

  it("prevents a back navigation and undoes it with history.forward") {

    val popStateBus = new EventBus[dom.PopStateEvent]
    var prevent = false
    val router = makeRouter(
      preventNavigation = _ => EventStream.fromValue(prevent),
      popStateEvents = popStateBus.events
    )

    router.pushState(LoginPage) // idx 1
    router.pushState(SignupPage) // idx 2, current: SignupPage

    prevent = true
    val (forwardCalls, backCalls, restore) = spyOnHistory()

    try {
      popStateBus.emit(popState(stampedState(idx = 1, page = LoginPage))) // back

      forwardCalls() shouldBe 1
      backCalls() shouldBe 0
      router.currentPageSignal.now() shouldBe SignupPage
      dom.document.location.href shouldBe s"$origin/signup/test"
    } finally {
      restore()
    }
  }

  it("prevents a forward navigation and undoes it with history.back") {

    val popStateBus = new EventBus[dom.PopStateEvent]
    var prevent = false
    val router = makeRouter(
      preventNavigation = _ => EventStream.fromValue(prevent),
      popStateEvents = popStateBus.events
    )

    router.pushState(LoginPage) // idx 1
    router.pushState(SignupPage) // idx 2

    // Simulate back navigation (allowed), landing on LoginPage
    popStateBus.emit(popState(stampedState(idx = 1, page = LoginPage)))
    router.currentPageSignal.now() shouldBe LoginPage

    prevent = true
    val (forwardCalls, backCalls, restore) = spyOnHistory()

    try {
      popStateBus.emit(popState(stampedState(idx = 2, page = SignupPage))) // forward

      forwardCalls() shouldBe 0
      backCalls() shouldBe 1
      router.currentPageSignal.now() shouldBe LoginPage
    } finally {
      restore()
    }
  }

  it("prevents a hash change and undoes it with history.back") {

    val popStateBus = new EventBus[dom.PopStateEvent]
    val router = makeRouter(
      preventNavigation = _ => EventStream.fromValue(true),
      popStateEvents = popStateBus.events
    )

    router.currentPageSignal.now() shouldBe LibraryPage(700)

    val (forwardCalls, backCalls, restore) = spyOnHistory()

    try {
      popStateBus.emit(popState(null)) // hash change (null state)

      forwardCalls() shouldBe 0
      backCalls() shouldBe 1
      router.currentPageSignal.now() shouldBe LibraryPage(700)
    } finally {
      restore()
    }
  }

}
