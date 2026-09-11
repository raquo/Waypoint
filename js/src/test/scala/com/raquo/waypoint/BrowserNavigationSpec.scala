package com.raquo.waypoint

import com.raquo.laminar.api.L._
import com.raquo.waypoint.fixtures.{AppRouterFixture, HistoryProbe, HistorySpy}
import com.raquo.waypoint.fixtures.AppPage._
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
  * That round trip is also the part of the feature that is most worth testing for real. The
  * router undoes a prevented Back by calling `history.forward()` and then swallowing the
  * `popstate` that results (`suppressNextPopState`), so the assertions below only mean something
  * if `history.forward()` really moves the browser, really fires a `popstate`, and really lands
  * on the entry the router expects. Feeding the router synthetic `PopStateEvent`s instead would
  * verify that it calls `forward()`, but not that calling `forward()` achieves anything.
  */
class BrowserNavigationSpec
extends AsyncFunSpec
with AppRouterFixture {

  // The default `Serial` execution context expects Futures to resolve synchronously through
  // scalatest's own pumping; it can't wait on a real DOM event delivered later by the browser's
  // actual event loop, so we swap in a genuine one.
  override implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global

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
            spy.backCalls shouldBe 0
            spy.forwardCalls shouldBe 0
          }
        }
        .flatMap { _ =>
          HistoryProbe.afterPopStates(1) { spy.browserForward() }.map { _ =>
            expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)
            kinds.lastOption shouldBe Some(NavigationKind.Forward)
            withClue("an allowed navigation must not be undone: ") {
              spy.backCalls shouldBe 0
              spy.forwardCalls shouldBe 0
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

  it("prevents a real Back navigation, undoes it with history.forward(), and allows a later Back") {

    var prevent = false
    val router = makeRouter(preventNavigation = _ => EventStream.fromValue(prevent))

    router.pushState(LoginPage) // index 1
    router.pushState(SignupPage) // index 2
    expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)

    HistorySpy.useAsync { spy =>
      prevent = true

      // The browser performs the Back navigation itself and only then tells the router about it,
      // so "preventing" it really means navigating forward again. Hence two popstate events: the
      // Back itself, and the router's undo of it (which the router recognizes as its own and
      // swallows via `suppressNextPopState`).
      HistoryProbe
        .afterPopStates(2) { spy.browserBack() }
        .map { _ =>
          withClue("a prevented Back must be undone by going forward: ") {
            spy.forwardCalls shouldBe 1
            spy.backCalls shouldBe 0
          }
          // The URL briefly showed /hello/login, and is now back where it started.
          expectCurrent(router, SignupPage, "/signup/test", navigationIndex = 2)
        }
        .flatMap { _ =>
          // The undo did not leave the router wedged: with the guard lifted, the very same Back
          // navigation now goes through. (`suppressNextPopState` swallowed the undo's popstate,
          // and nothing else.)
          prevent = false
          spy.reset()
          HistoryProbe.afterPopStates(1) { spy.browserBack() }.map { _ =>
            expectCurrent(router, LoginPage, "/hello/login", navigationIndex = 1)
            withClue("an allowed navigation must not be undone: ") {
              spy.backCalls shouldBe 0
              spy.forwardCalls shouldBe 0
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

  it("prevents a real Forward navigation, undoes it with history.back(), and allows a later Forward") {

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
            withClue("a prevented Forward must be undone by going back: ") {
              spy.backCalls shouldBe 1
              spy.forwardCalls shouldBe 0
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
              spy.backCalls shouldBe 0
              spy.forwardCalls shouldBe 0
            }
          }
        }
    }
  }

  it("prevents a real hash change, undoes it with history.back(), and allows a later hash change") {

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
          withClue("a prevented hash change must be undone by going back: ") {
            spy.backCalls shouldBe 1
            spy.forwardCalls shouldBe 0
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
              spy.backCalls shouldBe 0
              spy.forwardCalls shouldBe 0
            }
          }
        }
    }
  }
}
