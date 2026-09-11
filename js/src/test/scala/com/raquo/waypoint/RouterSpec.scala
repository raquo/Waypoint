package com.raquo.waypoint

import com.raquo.laminar.api.L._
import com.raquo.waypoint.fixtures.AppPage._
import com.raquo.waypoint.fixtures.{AppPage, AppRouterFixture, HistoryProbe, UnitSpec}

/** Tests of router-initiated navigation (`pushState` / `replaceState`), and of the
  * `preventNavigation` guard as it applies to them.
  *
  * These run against the real History API of a real browser (see `jsEnv` in build.sbt). They can
  * stay synchronous because a router-initiated navigation is applied synchronously, as long as
  * the guard resolves synchronously too.
  *
  * Browser-initiated navigation (Back / Forward / hash changes) is inherently asynchronous — the
  * browser delivers the `popstate` event on a later task — and is covered by
  * [[BrowserNavigationSpec]].
  */
class RouterSpec extends UnitSpec with AppRouterFixture {

  it("pushState and replaceState update the page, the URL, and the stamped history entry") {

    // The Selenium jsEnv serves the test bundle over http:// on a port assigned at runtime.
    // On a file:// origin every History API call in this suite would throw instead.
    origin should startWith("http://localhost:")

    val router = makeRouter()
    val entriesBefore = HistoryProbe.length

    expectCurrent(router, LibraryPage(700), "/app/library/700", navigationIndex = 0)

    router.pushState(LoginPage)

    expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
    HistoryProbe.length shouldBe (entriesBefore + 1) // pushState creates a history entry

    router.replaceState(LibraryPage(100))

    expectCurrent(router, LibraryPage(100), "/app/library/100", navigationIndex = 1)
    HistoryProbe.length shouldBe (entriesBefore + 1) // replaceState does not, and keeps the index
  }

  it("an async guard defers a navigation until it resolves, and then commits it") {

    val guardBus = new EventBus[Boolean]
    val router = makeRouter(preventNavigation = _ => guardBus.events)
    val entriesBefore = HistoryProbe.length

    router.pushState(LoginPage)

    // Nothing has happened yet: not the page, not the URL, and — importantly — not the history
    // entry. A router-initiated navigation must not touch history until the guard allows it.
    expectCurrent(router, LibraryPage(700), "/app/library/700", navigationIndex = 0)
    HistoryProbe.length shouldBe entriesBefore

    guardBus.emit(false) // allow

    expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
    HistoryProbe.length shouldBe (entriesBefore + 1)

    // The router is not left in a pending state: the next navigation is deferred to the guard
    // in exactly the same way.
    router.pushState(SignupPage)

    expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)

    guardBus.emit(false)

    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)
  }

  it("an async guard that prevents a navigation does not block the navigations that follow it") {

    val guardBus = new EventBus[Boolean]
    val router = makeRouter(preventNavigation = _ => guardBus.events)
    val entriesBefore = HistoryProbe.length

    router.pushState(LoginPage)
    guardBus.emit(true) // prevent

    expectCurrent(router, LibraryPage(700), "/app/library/700", navigationIndex = 0)
    HistoryProbe.length shouldBe entriesBefore

    // The pending flag was released, so the next navigation is processed normally...
    router.pushState(SignupPage)
    guardBus.emit(false) // allow

    // ...and it takes index 1, i.e. the prevented navigation did not consume an index.
    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 1)
    HistoryProbe.length shouldBe (entriesBefore + 1)
  }

  it("a guard can prevent pushState conditionally, letting other navigations through") {

    val router = makeRouter(preventNavigation = ev => EventStream.fromValue(ev.toPage == LoginPage))
    val entriesBefore = HistoryProbe.length

    router.pushState(LoginPage) // prevented

    expectCurrent(router, LibraryPage(700), "/app/library/700", navigationIndex = 0)
    HistoryProbe.length shouldBe entriesBefore

    router.pushState(SignupPage) // allowed

    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 1)
    HistoryProbe.length shouldBe (entriesBefore + 1)

    router.pushState(LoginPage) // prevented again, this time from a different page

    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 1)
    HistoryProbe.length shouldBe (entriesBefore + 1)

    router.pushState(LibraryPage(100)) // still allowed

    expectCurrent(router, LibraryPage(100), "/app/library/100", navigationIndex = 2)
  }

  it("a guard can prevent replaceState conditionally, leaving the current history entry intact") {

    val router = makeRouter(preventNavigation = ev => EventStream.fromValue(ev.toPage == LoginPage))
    val entriesBefore = HistoryProbe.length

    router.replaceState(LoginPage) // prevented

    // A prevented replaceState must not have overwritten the entry we are standing on.
    expectCurrent(router, LibraryPage(700), "/app/library/700", navigationIndex = 0)
    HistoryProbe.length shouldBe entriesBefore

    router.replaceState(SignupPage) // allowed

    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 0) // replace keeps the index
    HistoryProbe.length shouldBe entriesBefore

    router.pushState(LoginPage) // prevented

    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 0)
    HistoryProbe.length shouldBe entriesBefore
  }

  it("rejects navigations requested while a guard is pending, without leaving a trace of them") {

    val guardBus = new EventBus[Boolean]
    var guardedPages = List.empty[AppPage]
    val router = makeRouter(preventNavigation = { ev =>
      guardedPages = guardedPages :+ ev.toPage
      guardBus.events
    })

    router.pushState(LoginPage) // pending
    router.pushState(SignupPage) // rejected outright while the first one is pending

    guardedPages shouldBe List(LoginPage) // the rejected navigation never reached the guard

    guardBus.emit(false) // allow the first navigation

    expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)

    // The rejected navigation is gone for good: it was dropped, not queued up behind the first.
    guardedPages shouldBe List(LoginPage)

    router.pushState(SignupPage)
    guardBus.emit(false)

    guardedPages shouldBe List(LoginPage, SignupPage)
    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)
  }
}
