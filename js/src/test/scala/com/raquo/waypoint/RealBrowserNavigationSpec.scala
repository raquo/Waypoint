package com.raquo.waypoint

import com.raquo.airstream.ownership.Owner
import com.raquo.waypoint.fixtures.AppPage
import com.raquo.waypoint.fixtures.AppPage._
import org.scalajs.dom
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

/** Exercises basic Waypoint navigation using the real Selenium-driven browser's own back / forward
  * history mechanism, as opposed to the synthetic `PopStateEvent`s that [[RouterSpec]] feeds through
  * a mocked `popStateEvents` bus. This is only meaningful under a real browser jsEnv (e.g. Selenium):
  * jsdom does not reliably dispatch real `popstate` events from `history.back()` / `.forward()`.
  */
class RealBrowserNavigationSpec extends AsyncFunSpec with Matchers {

  // The default `Serial` execution context expects Futures to resolve synchronously through
  // scalatest's own pumping; it can't wait on a real DOM event delivered later by the browser's
  // actual event loop, so we swap in a genuine one.
  override implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global

  private val testOwner = new Owner {}

  private val libraryRoute = Route[LibraryPage, Int](
    encode = _.libraryId,
    decode = arg => LibraryPage(libraryId = arg),
    pattern = root / "app" / "library" / segment[Int]
  )

  private val loginRoute = Route.static(LoginPage, root / "hello" / "login")

  private val signupRoute = Route.static(SignupPage, root / "signup" / "test")

  /** Resolves the next time a real `popstate` event reaches the window, i.e. after the browser
    * (not our test code) has actually processed a `history.back()` / `.forward()` call.
    */
  private def nextPopState(): Future[Unit] = {
    val promise = Promise[Unit]()
    lazy val handler: js.Function1[dom.PopStateEvent, Unit] = { _ =>
      dom.window.removeEventListener("popstate", handler)
      promise.success(())
    }
    dom.window.addEventListener("popstate", handler)
    promise.future
  }

  it("navigates via the real browser's back / forward buttons") {

    // Set initial URL
    dom.window.history.pushState(new js.Object, "", "/app/library/700")

    val codec = new HistoryCodec[AppPage](
      serializePage = page => write(page)(AppPage.rw),
      deserializePage = pageStr => read(pageStr)(AppPage.rw),
    )

    // Note: popStateEvents defaults to the real `windowEvents(_.onPopState)`, unlike RouterSpec's
    // makeRouter helper, which lets tests substitute a mocked bus.
    val router = new Router[AppPage](
      routes = libraryRoute :: loginRoute :: signupRoute :: Nil,
      codec = codec,
      getPageTitle = _.toString,
      owner = testOwner,
    )

    router.currentPageSignal.now() shouldBe LibraryPage(700)

    router.pushState(LoginPage) // idx 1
    router.pushState(SignupPage) // idx 2

    router.currentPageSignal.now() shouldBe SignupPage

    val backObserved = nextPopState()
    dom.window.history.back()

    backObserved
      .map { _ =>
        router.currentPageSignal.now() shouldBe LoginPage
      }
      .flatMap { _ =>
        val forwardObserved = nextPopState()
        dom.window.history.forward()
        forwardObserved.map { _ =>
          router.currentPageSignal.now() shouldBe SignupPage
        }
      }
  }
}
