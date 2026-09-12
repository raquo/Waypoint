package com.raquo.waypoint

import com.raquo.laminar.api.L._
import com.raquo.waypoint.fixtures.{AppRouterFixture, HistoryProbe, HistorySpy}
import com.raquo.waypoint.fixtures.AppPage._
import com.raquo.waypoint.navigation.NavigationKind
import org.scalajs.dom
import org.scalatest.funspec.AsyncFunSpec

import scala.concurrent.ExecutionContext

/** Tests of browser-initiated navigation — the browser's own Back / Forward, and hash changes —
  * driven through the real browser APIs, against the real session history of the Selenium-driven
  * browser that runs this suite (see `jsEnv` in build.sbt).
  *
  * Why these tests are async, and why they live apart from [[RouterSpec]]: a browser-initiated
  * navigation happens *before* the router hears about it, and the router hears about it through a
  * `popstate` event delivered on a later task of the browser's event loop. So a test can not
  * assert anything on the line after `history.back()` — it has to wait for the event to land.
  *
  * That round trip is also the part of the feature most worth testing for real. The router
  * undoes a prevented Back by moving the browser back to the committed entry and recognizing the
  * resulting `popstate` as its own, so the assertions below only mean something if that traversal
  * really moves the browser, really fires a `popstate`, and really lands on the entry the router
  * expects. Feeding the router synthetic `PopStateEvent`s would verify that it asked to move, but
  * not that moving achieves anything.
  */
class BrowserNavigationSpec
extends AsyncFunSpec
with AppRouterFixture {

  // The default `Serial` execution context expects Futures to resolve synchronously through
  // scalatest's own pumping; it can't wait on a real DOM event delivered later by the browser's
  // actual event loop, so we swap in a genuine one.
  override implicit val executionContext: ExecutionContext =
    scala.scalajs.concurrent.JSExecutionContext.queue

  it("commits a real Back and a real Forward navigation, without undoing anything") {

    var kinds = List.empty[NavigationKind]
    val router = makeRouter(preventNavigation = { ev =>
      kinds = kinds :+ ev.kind
      EventStream.fromValue(false) // allow everything
    })

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2
    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)

    HistorySpy.useAsync { spy =>
      HistoryProbe
        .afterPopStates(1) { spy.browserBack() }
        .map { _ =>
          expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
          kinds shouldBe List(NavigationKind.Push, NavigationKind.Push, NavigationKind.Back)
          withClue("an allowed navigation must not be undone: ") {
            spy.moves shouldBe Nil
          }
        }
        .flatMap { _ =>
          HistoryProbe.afterPopStates(1) { spy.browserForward() }.map { _ =>
            expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)
            kinds.lastOption shouldBe Some(NavigationKind.Forward)
            withClue("an allowed navigation must not be undone: ") {
              spy.moves shouldBe Nil
            }
          }
        }
    }
  }

  it("tells a Back from a Forward between two history entries that have the same URL") {

    // This is the whole reason the router stamps a sequential index into every history entry:
    // the URL alone can not say which direction the browser moved in.
    var kinds = List.empty[NavigationKind]
    val router = makeRouter(preventNavigation = { ev =>
      kinds = kinds :+ ev.kind
      EventStream.fromValue(false)
    })

    router.pushState(LibraryPage(700)) // index 1, same URL as index 0

    expectCurrent(router, LibraryPage(700), "/app/library/700", navigationIndex = 1)

    HistorySpy.useAsync { spy =>
      HistoryProbe
        .afterPopStates(1) { spy.browserBack() }
        .map { _ =>
          expectCurrent(router, LibraryPage(700), "/app/library/700", navigationIndex = 0)
          kinds.lastOption shouldBe Some(NavigationKind.Back)
        }
        .flatMap { _ =>
          HistoryProbe.afterPopStates(1) { spy.browserForward() }.map { _ =>
            expectCurrent(router, LibraryPage(700), "/app/library/700", navigationIndex = 1)
            kinds.lastOption shouldBe Some(NavigationKind.Forward)
          }
        }
    }
  }

  it("prevents a real Back navigation, undoes it by going forward, and allows a later Back") {

    var prevent = false
    val router = makeRouter(preventNavigation = _ => EventStream.fromValue(prevent))

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2
    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)

    HistorySpy.useAsync { spy =>
      prevent = true

      // The browser performs the Back itself and only then tells the router, so "preventing" it
      // really means navigating forward again. Hence two popstate events: the Back itself, and
      // the router's undo of it (recognized and swallowed as its own undo).
      HistoryProbe
        .afterPopStates(2) { spy.browserBack() }
        .map { _ =>
          withClue("a prevented Back must be undone by going one entry forward: ") {
            spy.moves shouldBe List(1)
          }
          // The URL briefly showed /hello/login, and is now back where it started.
          expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)
        }
        .flatMap { _ =>
          // The undo did not leave the router wedged: with the guard lifted, the very same Back
          // navigation now goes through. (The router swallowed the undo's popstate, and nothing
          // else.)
          prevent = false
          spy.reset()
          HistoryProbe.afterPopStates(1) { spy.browserBack() }.map { _ =>
            expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
            withClue("an allowed navigation must not be undone: ") {
              spy.moves shouldBe Nil
            }
          }
        }
        .map { _ =>
          // The router's own index bookkeeping survived the undo, too: the next pushState
          // continues from index 1, not from the index the prevented navigation aimed at.
          router.pushState(TextPage("after"))
          expectCurrent(router, TextPage("after"), "/app/test/after", navigationIndex = 2)
        }
    }
  }

  it("prevents a real Forward navigation, undoes it by going back, and allows a later Forward") {

    var prevent = false
    val router = makeRouter(preventNavigation = _ => EventStream.fromValue(prevent))

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2

    HistorySpy.useAsync { spy =>
      HistoryProbe
        .afterPopStates(1) { spy.browserBack() } // allowed, so that there is something to go forward to
        .map { _ =>
          expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
        }
        .flatMap { _ =>
          prevent = true
          spy.reset()
          HistoryProbe.afterPopStates(2) { spy.browserForward() }.map { _ =>
            withClue("a prevented Forward must be undone by going one entry back: ") {
              spy.moves shouldBe List(-1)
            }
            expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
          }
        }
        .flatMap { _ =>
          prevent = false
          spy.reset()
          HistoryProbe.afterPopStates(1) { spy.browserForward() }.map { _ =>
            expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)
            withClue("an allowed navigation must not be undone: ") {
              spy.moves shouldBe Nil
            }
          }
        }
    }
  }

  it("recovers from a burst of rapid Back presses that are all prevented") {

    // Rapid prevented Backs stress the undo: a later Back's popstate can arrive before an
    // earlier undo's, so recognizing an undo must not rely on ordering. Every correction aims at
    // the committed entry, however far the browser wandered, keeping page, URL and index in sync.
    var prevent = false
    val router = makeRouter(preventNavigation = _ => EventStream.fromValue(prevent))

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2
    router.pushState(TextPage("c")) // index 3

    expectCurrent(router, TextPage("c"), "/app/test/c", navigationIndex = 3)

    HistorySpy.useAsync { spy =>
      prevent = true
      HistoryProbe
        .afterPopStatesSettle() { spy.browserBack(); spy.browserBack() }
        .map { _ =>
          // Whatever the browser did with two queued traversals, every correction the router
          // made went forward, toward the entry it is committed to.
          all(spy.moves) should be > 0
          // And all three answers agree again: rendered page, URL, and stamped index.
          expectCurrent(router, TextPage("c"), "/app/test/c", navigationIndex = 3)
        }
        .flatMap { _ =>
          // The router is not wedged afterwards: a single Back now behaves completely normally.
          prevent = false
          spy.reset()
          HistoryProbe.afterPopStates(1) { spy.browserBack() }.map { _ =>
            expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)
            spy.moves shouldBe Nil
          }
        }
        .map { _ =>
          // And the committed index survived the burst: a fresh push continues from 2, not from
          // whichever entry the browser happened to touch along the way.
          router.pushState(LoginPage)
          expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 3)
        }
    }
  }

  it("recovers from a burst of three rapid Back presses that are all prevented") {

    var prevent = false
    val router = makeRouter(preventNavigation = _ => EventStream.fromValue(prevent))

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2
    router.pushState(TextPage("c")) // index 3

    HistorySpy.useAsync { spy =>
      prevent = true
      HistoryProbe
        .afterPopStatesSettle() { spy.browserBack(); spy.browserBack(); spy.browserBack() }
        .map { _ =>
          all(spy.moves) should be > 0
          expectCurrent(router, TextPage("c"), "/app/test/c", navigationIndex = 3)
        }
    }
  }

  it("recovers from a burst of rapid Forward presses that are all prevented") {

    // Mirror image of the Back burst: the undo moves by a negative delta greater than one.
    var prevent = false
    val router = makeRouter(preventNavigation = _ => EventStream.fromValue(prevent))

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2
    router.pushState(TextPage("c")) // index 3

    HistorySpy.useAsync { spy =>
      // Walk back two entries with the guard allowing, so there is somewhere to go forward to.
      HistoryProbe
        .afterPopStatesSettle() { spy.browserBack(); spy.browserBack() }
        .map { _ =>
          expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
        }
        .flatMap { _ =>
          prevent = true
          spy.reset()
          HistoryProbe
            .afterPopStatesSettle() { spy.browserForward(); spy.browserForward() }
            .map { _ =>
              all(spy.moves) should be < 0
              expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
            }
        }
    }
  }

  it("undoes a prevented multi-entry jump in a single move") {

    // A user can jump several entries at once from the browser's Back-button menu, arriving as
    // one popstate landing far from the app. Undoing it must cover the whole distance: stepping
    // back one at a time would get home too, but by treating each intermediate entry as a fresh
    // navigation — consulting the guard, so potentially prompting the user, once per entry.
    var prevent = false
    var guardCalls = 0
    val router = makeRouter(preventNavigation = { _ =>
      guardCalls += 1
      EventStream.fromValue(prevent)
    })

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2
    router.pushState(TextPage("c")) // index 3

    expectCurrent(router, TextPage("c"), "/app/test/c", navigationIndex = 3)

    HistorySpy.useAsync { spy =>
      prevent = true
      guardCalls = 0
      HistoryProbe
        .afterPopStates(2) { spy.browserGo(-3) } // the jump, and the router's undo of it
        .map { _ =>
          withClue("the undo must cover the whole distance in one move: ") {
            spy.moves shouldBe List(3)
          }
          withClue("and must not re-ask the guard about the entries it passes over: ") {
            guardCalls shouldBe 1
          }
          expectCurrent(router, TextPage("c"), "/app/test/c", navigationIndex = 3)
        }
    }
  }

  it("commits every navigation in a burst of rapid Back presses when they are allowed") {

    val router = makeRouter() // allows everything

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2
    router.pushState(TextPage("c")) // index 3

    HistorySpy.useAsync { spy =>
      HistoryProbe
        .afterPopStatesSettle() { spy.browserBack(); spy.browserBack() }
        .map { _ =>
          withClue("an allowed navigation must not be undone: ") {
            spy.moves shouldBe Nil
          }
          expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
        }
    }
  }

  it("never asks the browser to move by zero, which would reload the page") {

    // `history.go(0)` reloads the document — wiping out this whole test run, not just failing
    // this test — so a prevented navigation already at the committed entry must skip its undo
    // entirely. Two entries stamped with the same index is what makes that happen.
    var prevent = false
    val router = makeRouter(preventNavigation = _ => EventStream.fromValue(prevent))

    router.pushState(LoginPage) // committed index 1

    dom.window.history.pushState(s"1|${serializePage(SignupPage)}", "", "/signup/test")

    HistorySpy.useAsync { spy =>
      prevent = true
      HistoryProbe.afterPopStates(1) { spy.browserBack() }.map { _ =>
        withClue("there is no distance to undo, so nothing should have been requested: ") {
          spy.moves shouldBe Nil
        }
        router.currentPageSignal.now() shouldBe LoginPage
      }
    }
  }

  it("recognizes its own undo even when it lands on an entry it never stamped") {

    // The router only stamps the initial entry when the initial URL matches a route, so after a
    // fallback there is no index on it to match an undo against — only the URL. Without that
    // match the router would read its own undo as a fresh hash change, prevent that too, and
    // bounce backwards until it fell out of the document.
    var prevent = false
    val router = makeRouter(
      preventNavigation = _ => EventStream.fromValue(prevent),
      routeFallback = _ => HomePage,
      initialRelativeUrl = "/nope/not-a-route"
    )

    router.currentPageSignal.now() shouldBe HomePage
    withClue("this test is pointless unless the initial entry really is unstamped: ") {
      HistoryProbe.rawState shouldBe None
    }

    HistorySpy.useAsync { spy =>
      prevent = true
      HistoryProbe
        .afterPopStates(2) { dom.window.location.hash = "#section" }
        .map { _ =>
          spy.moves shouldBe List(-1)
          dom.window.location.href shouldBe (origin + "/nope/not-a-route")
          router.currentPageSignal.now() shouldBe HomePage
        }
    }
  }

  it("lets a browser Back supersede a pending router navigation, and decides it on its own merits") {

    val guardBus = new EventBus[Boolean]
    var defer = false
    var kinds = List.empty[NavigationKind]
    val router = makeRouter(preventNavigation = { ev =>
      kinds = kinds :+ ev.kind
      if (defer) guardBus.events else EventStream.fromValue(false)
    })

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2

    val entriesBefore = HistoryProbe.length

    defer = true
    router.pushState(TextPage("superseded")) // the guard for this one never answers

    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)

    HistorySpy.useAsync { spy =>
      // The user presses Back while the app is still deciding about the navigation above — the
      // "unsaved changes modal is on screen" case. The Back is what the user wants now, so it
      // takes over: the pending navigation is abandoned, and the Back put to the guard itself.
      HistoryProbe
        .afterPopStates(1) { spy.browserBack() }
        .map { _ =>
          kinds shouldBe List(
            NavigationKind.Push, NavigationKind.Push, NavigationKind.Push, NavigationKind.Back
          )
          withClue("superseding a navigation must not move the browser: ") {
            spy.moves shouldBe Nil
          }
          // The Back's own guard has not answered, so the browser has moved but the app has
          // not followed — as for any pending browser navigation.
          dom.window.location.href shouldBe (origin + "/hello/login")
          router.currentPageSignal.now() shouldBe SignupPage
        }
        .map { _ =>
          // Answering now answers the *Back*, not the navigation it replaced: the abandoned
          // `pushState` and its guard's subscription are gone.
          guardBus.emit(false)
          kinds should have size 4
          expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
          withClue("the superseded pushState must not have created an entry: ") {
            HistoryProbe.length shouldBe entriesBefore
          }
        }
        .map { _ =>
          // And the router carries on from where the Back left it, not from the index the
          // abandoned navigation was aiming at.
          defer = false
          router.pushState(TextPage("after"))
          expectCurrent(router, TextPage("after"), "/app/test/after", navigationIndex = 2)
        }
    }
  }

  it("lets a router navigation supersede a pending browser Back, building on where the browser is") {

    val guardBus = new EventBus[Boolean]
    var defer = false
    var kinds = List.empty[NavigationKind]
    val router = makeRouter(preventNavigation = { ev =>
      kinds = kinds :+ ev.kind
      if (defer) guardBus.events else EventStream.fromValue(false)
    })

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2

    val entriesBefore = HistoryProbe.length

    defer = true

    HistorySpy.useAsync { spy =>
      HistoryProbe
        .afterPopStates(1) { spy.browserBack() } // pending: neither undone nor committed
        .map { _ =>
          kinds.lastOption shouldBe Some(NavigationKind.Back)

          // While a browser navigation is pending, router and browser disagree by design:
          // the browser has already moved, so the URL is the destination, but the router has
          // not rendered that page, and will not until the guard answers.
          dom.window.location.href shouldBe (origin + "/hello/login")
          router.currentPageSignal.now() shouldBe SignupPage

          // The app now navigates — a redirect, a click on a link still on screen. It supersedes
          // the Back, and is allowed.
          defer = false
          router.pushState(LibraryPage(100))

          kinds shouldBe List(
            NavigationKind.Push, NavigationKind.Push, NavigationKind.Back, NavigationKind.Push
          )
          withClue("superseding must not move the browser back first: ") {
            spy.moves shouldBe Nil
          }

          // `pushState` appends after the entry the *browser* stands on — the one the superseded
          // Back moved it to — so that is what the new entry follows, and its index must follow
          // too, or a later Back to it would read as a Forward. It also means the entry the
          // browser moved away from is overwritten rather than added to.
          expectCurrent(router, LibraryPage(100), "/app/library/100", navigationIndex = 2)
          HistoryProbe.length shouldBe entriesBefore
        }
        .flatMap { _ =>
          // The stack this leaves is coherent: Back from here lands on the entry the superseded
          // Back had moved to, and is recognized as a Back.
          HistoryProbe.afterPopStates(1) { spy.browserBack() }.map { _ =>
            kinds.lastOption shouldBe Some(NavigationKind.Back)
            expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
            spy.moves shouldBe Nil
          }
        }
    }
  }

  it("brings the browser home when a navigation that superseded a pending Back is itself prevented") {

    // The case that makes superseding more than bookkeeping. The `pushState` below writes
    // nothing to history, so there seems nothing to undo when it is refused — but the browser is
    // one entry from the app, because the Back this navigation superseded moved it there and was
    // never undone. Undoing a refused navigation can not mean inverting that navigation; it means
    // putting the browser back where the app is.
    val guardBus = new EventBus[Boolean]
    var defer = false
    var prevent = false
    val router = makeRouter(preventNavigation = { _ =>
      if (defer) guardBus.events else EventStream.fromValue(prevent)
    })

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2

    defer = true

    HistorySpy.useAsync { spy =>
      HistoryProbe
        .afterPopStates(1) { spy.browserBack() } // pending
        .flatMap { _ =>
          dom.window.location.href shouldBe (origin + "/hello/login")

          defer = false
          prevent = true
          // Refusing this one must move the browser forward again, even though the refused
          // navigation never touched history itself. Hence a popstate to wait for.
          HistoryProbe.afterPopStates(1) { router.pushState(LibraryPage(100)) }.map { _ =>
            withClue("the browser must be returned to the committed entry: ") {
              spy.moves shouldBe List(1)
            }
            expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)
          }
        }
        .flatMap { _ =>
          // Nothing is wedged: a Back now behaves completely normally again.
          prevent = false
          spy.reset()
          HistoryProbe.afterPopStates(1) { spy.browserBack() }.map { _ =>
            expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
            spy.moves shouldBe Nil
          }
        }
    }
  }

  it("lets a second Back supersede the first while the first one's guard is still pending") {

    // Two Backs far enough apart that each gets its own popstate, with a guard slow enough that
    // the first is still unanswered when the second arrives. The second must supersede the first
    // rather than be rejected outright: rejecting it would undo it on the spot, moving the
    // browser back to where it started, so answering the first would render an entry the browser
    // is no longer on — desynchronizing page, URL and index.
    val guardBus = new EventBus[Boolean]
    var kinds = List.empty[NavigationKind]
    val router = makeRouter(preventNavigation = { ev =>
      kinds = kinds :+ ev.kind
      if (ev.kind == NavigationKind.Back) guardBus.events else EventStream.fromValue(false)
    })

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2
    router.pushState(TextPage("c")) // index 3

    HistorySpy.useAsync { spy =>
      HistoryProbe
        .afterPopStates(1) { spy.browserBack() } // to index 2, pending
        .flatMap { _ =>
          HistoryProbe.afterPopStates(1) { spy.browserBack() } // to index 1, supersedes it
        }
        .map { _ =>
          withClue("neither Back was refused, so neither may have been undone: ") {
            spy.moves shouldBe Nil
          }
          kinds shouldBe List(
            NavigationKind.Push, NavigationKind.Push, NavigationKind.Push,
            NavigationKind.Back, NavigationKind.Back
          )
          dom.window.location.href shouldBe (origin + "/hello/login")
          router.currentPageSignal.now() shouldBe TextPage("c")

          // One answer resolves the navigation actually outstanding — the second Back — and the
          // app lands where the browser really is, not one entry behind it.
          guardBus.emit(false)

          expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
          kinds should have size 5
        }
    }
  }

  it("places an entry it never stamped relative to the browser, not relative to the app") {

    // A hash change appends an entry with no state, so there is no index to place it by and the
    // router assumes the browser appended it right where it stood. "Where it stood" is the
    // browser's entry, not the app's: the same entry until a browser navigation is waiting on the
    // guard, and this one is. Measuring the undo from the app's entry would make the router go one
    // entry short, land somewhere it does not recognize, and re-ask the guard about an entry it is
    // only passing through.
    val guardBus = new EventBus[Boolean]
    var defer = false
    var prevent = false
    val router = makeRouter(preventNavigation = { _ =>
      if (defer) guardBus.events else EventStream.fromValue(prevent)
    })

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2

    HistorySpy.useAsync { spy =>
      HistoryProbe
        .afterPopStates(1) { spy.browserBack() } // allowed, so there is something to go forward to
        .map { _ =>
          expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
        }
        .flatMap { _ =>
          defer = true
          // The Forward moves the browser to index 2 and stops there, unanswered.
          HistoryProbe.afterPopStates(1) { spy.browserForward() }.map { _ =>
            dom.window.location.href shouldBe (origin + "/signup/test")
            router.currentPageSignal.now() shouldBe LoginPage
          }
        }
        .flatMap { _ =>
          defer = false
          prevent = true
          spy.reset()
          // Now the hash changes — an in-page anchor, a manual edit of the URL. It supersedes the
          // Forward, and is refused. Getting home is two entries back: the hash entry the browser
          // just created, and the one the superseded Forward had taken it to.
          HistoryProbe.afterPopStates(2) { dom.window.location.hash = "#section" }.map { _ =>
            withClue("the undo must span the hash entry and the superseded Forward: ") {
              spy.moves shouldBe List(-2)
            }
            expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
          }
        }
    }
  }

  it("prevents a real hash change, undoes it by going back, and allows a later hash change") {

    var prevent = false
    var kinds = List.empty[NavigationKind]
    val router = makeRouter(preventNavigation = { ev =>
      kinds = kinds :+ ev.kind
      EventStream.fromValue(prevent)
    })

    expectCurrent(router, LibraryPage(700), "/app/library/700", navigationIndex = 0)

    HistorySpy.useAsync { spy =>
      prevent = true

      // A hash change is not a navigation the router made: the browser appends a history entry
      // with a null state and fires a popstate for it, which is how the router recognizes it.
      // Undoing it means going back, and that produces the second popstate we wait for.
      HistoryProbe
        .afterPopStates(2) { dom.window.location.hash = "#section" }
        .map { _ =>
          kinds.lastOption shouldBe Some(NavigationKind.HashChange)
          withClue("a prevented hash change must be undone by going one entry back: ") {
            spy.moves shouldBe List(-1)
          }
          expectCurrent(router, LibraryPage(700), "/app/library/700", navigationIndex = 0)
        }
        .flatMap { _ =>
          prevent = false
          spy.reset()
          HistoryProbe.afterPopStates(1) { dom.window.location.hash = "#section" }.map { _ =>
            // An allowed hash change is committed by re-stamping the entry the browser created,
            // so that a later Back / Forward to it can be recognized like any other.
            expectCurrent(router, LibraryPage(700), "/app/library/700#section", navigationIndex = 1)
            withClue("an allowed navigation must not be undone: ") {
              spy.moves shouldBe Nil
            }
          }
        }
    }
  }
}
