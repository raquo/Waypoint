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

  it("lets a navigation requested while a guard is pending supersede the pending one") {

    val guardBus = new EventBus[Boolean]
    var guardedPages = List.empty[AppPage]
    val router = makeRouter(preventNavigation = { ev =>
      guardedPages = guardedPages :+ ev.toPage
      guardBus.events
    })

    val entriesBefore = HistoryProbe.length

    router.pushState(LoginPage) // pending

    // Each new request takes over from the one before it, and is put to the guard in its own
    // right — the app is asked about the navigation that is actually being attempted now.
    router.pushState(SignupPage)
    router.replaceState(TextPage("superseded"))
    router.pushState(LibraryPage(100))

    guardedPages shouldBe List(LoginPage, SignupPage, TextPage("superseded"), LibraryPage(100))

    // Superseding a navigation is not the same as performing it: nothing has been committed.
    expectCurrent(router, LibraryPage(700), "/app/library/700", navigationIndex = 0)
    HistoryProbe.length shouldBe entriesBefore

    guardBus.emit(false) // allow

    // Only the last request survives to be acted on. The three it superseded leave no trace at
    // all — not one extra history entry between them, not a consumed index, and not the
    // `replaceState` among them overwriting the entry we were standing on.
    expectCurrent(router, LibraryPage(100), "/app/library/100", navigationIndex = 1)
    HistoryProbe.length shouldBe (entriesBefore + 1)

    // The emit above reached only the surviving navigation's guard, so no new question was asked
    // and, more to the point, the three abandoned ones did not answer late.
    guardedPages should have size 4

    // And the router is not left pending: the next navigation goes through the guard as usual.
    router.pushState(SignupPage)
    guardedPages should have size 5
    expectCurrent(router, LibraryPage(100), "/app/library/100", navigationIndex = 1)

    guardBus.emit(false)
    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)
  }

  it("ignores an answer that arrives from a superseded navigation's guard") {

    val firstGuard = new EventBus[Boolean]
    val secondGuard = new EventBus[Boolean]
    var guardCalls = 0
    val router = makeRouter(preventNavigation = { _ =>
      guardCalls += 1
      if (guardCalls == 1) firstGuard.events else secondGuard.events
    })

    val entriesBefore = HistoryProbe.length

    router.pushState(LoginPage) // pending on the first guard
    router.pushState(SignupPage) // supersedes it; pending on the second

    // The app answers the question it was asked first — a modal it had already taken off screen,
    // a server response that arrived too late. The navigation it refers to no longer exists, so
    // the answer can not be acted on, in either direction.
    firstGuard.emit(false)

    expectCurrent(router, LibraryPage(700), "/app/library/700", navigationIndex = 0)
    HistoryProbe.length shouldBe entriesBefore

    firstGuard.emit(true)

    expectCurrent(router, LibraryPage(700), "/app/library/700", navigationIndex = 0)

    // The surviving navigation is still pending, and still answerable.
    secondGuard.emit(false)

    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 1)
    HistoryProbe.length shouldBe (entriesBefore + 1)
  }

  it("does not act on a navigation that is superseded while its own guard is mid-emit") {

    // Killing an Airstream subscription during a propagation only schedules its removal; the
    // observer still sees the event that is already in flight. So a guard's answer can reach the
    // router after the navigation it was about has been superseded — here by an app that reacts to
    // the very same event by navigating. Acting on that answer would commit a navigation the
    // router had already abandoned, on top of the one that replaced it.
    val guardBus = new EventBus[Boolean]
    val router = makeRouter(preventNavigation = _ => guardBus.events)
    val entriesBefore = HistoryProbe.length

    // Subscribed before the router's own guard subscription, so it runs first when the bus emits.
    guardBus.events.foreach { _ =>
      if (router.currentPageSignal.now() != SignupPage) {
        router.pushState(SignupPage)
      }
    }(using testOwner)

    router.pushState(LoginPage) // pending on the same bus

    guardBus.emit(false)

    // SignupPage is the navigation that superseded LoginPage, and the only one that may be acted
    // on: one entry, one index, and no trace of the navigation it replaced.
    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 1)
    HistoryProbe.length shouldBe (entriesBefore + 1)
  }

  it("unsubscribes from a guard's stream once it is superseded, and once it is answered") {

    // This is the only signal a guard gets that its question has been withdrawn. A guard that puts
    // a confirmation dialog on screen when its stream starts takes it down again when the stream
    // stops, so the dialog has to stop when the navigation it was asking about is abandoned —
    // otherwise the user is left looking at a question about a navigation that is no longer
    // happening, and answering it does nothing.
    var startedFor = List.empty[AppPage]
    var stops = 0
    var answer: Option[Boolean => Unit] = None

    val router = makeRouter(preventNavigation = { ev =>
      EventStream.fromCustomSource[Boolean](
        start = (fireValue, _, _, _) => {
          startedFor = startedFor :+ ev.toPage
          answer = Some(fireValue)
        },
        stop = _ => stops += 1
      )
    })

    router.pushState(LoginPage)

    startedFor shouldBe List(LoginPage)
    stops shouldBe 0

    router.pushState(SignupPage)

    startedFor shouldBe List(LoginPage, SignupPage)
    withClue("the superseded navigation's guard must be torn down: ") {
      stops shouldBe 1
    }

    // Answering tears down the surviving one too, rather than leaving it subscribed for the life
    // of the router's owner.
    answer.get(false)

    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 1)
    withClue("an answered navigation's guard must be torn down as well: ") {
      stops shouldBe 2
    }
  }
}
