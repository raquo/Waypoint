package com.raquo.waypoint

import com.raquo.laminar.api.L._
import com.raquo.waypoint.navigation.{NavigationKind, NavigationRequest, PendingNavigation, UndoExpectation}
import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.util.{Failure, Success, Try}

/**
  * @param routes              - List of routes that this router can handle. You should only have one router for your
  *                              app, so put all your routes here. There is no concept of subrouters, this is achieved
  *                              by a combination of Page type hierarchy and nested rendering signals using the patterns
  *                              described in README.
  *                              If none of the routes match the provided URL upon initial page load,
  *                              [[routeFallback]] will be used instead.
  *
  * @param routeFallback       - receives URL as input (either initialUrl or on hashChange event) and returns a page
  *                              to render when none of the routes match on initial page load.
  *                              When rendering a fallback page, the URL will stay the same.
  *                              If you want to perform any logic (such as redirects) in case of this fallback,
  *                              put it in the renderer (whatever you have responding to router.\$currentPage).
  *
  * @param popStateEvents      - typically windowEvents(_.onPopState) in Laminar
  *
  * @param preventNavigation   - optionally prevent a navigation, e.g. to show a "you have unsaved changes" dialog.
  *                              For each navigation this callback is invoked and returns an EventStream that should
  *                              emit one Boolean: `true` to prevent (block) the navigation, `false` to allow it.
  *                              If the stream never emits, the navigation stays pending (blocked) indefinitely.
  *                              If another navigation is requested while this stream is still pending, the new
  *                              navigation supersedes the pending one: the pending navigation is abandoned, this
  *                              stream is unsubscribed from (so that a guard which puts a confirmation dialog on
  *                              screen when started can take it down again when stopped), and the guard is asked
  *                              afresh about the new navigation. An answer that arrives from an abandoned stream
  *                              after that point is ignored.
  *                              Note that browser back / forward navigations have already happened by the time
  *                              Waypoint learns about them (this is how the History API works), so preventing
  *                              those un-does the navigation internally.
  *
  * @param owner               - typically unsafeWindowOwner in Laminar (if the router should never die, i.e. it's a whole app router)
  *
  * @param origin              - e.g. "http://localhost:8080", without trailing slash
  *
  * @param currentUrl          - typically isInitial => dom.window.location.href, but you may want to override this
  *                              for testing or to virtually clean the URLs that Waypoint sees if something else is
  *                              messing with your URLs (e.g. Keycloak-JS adding malformed query params)
  *
  * @throws Exception          - when the initial [[currentUrl]] is not absolute or does not match [[origin]]
  */
class Router[BasePage](
  routes: List[Route[? <: BasePage, ?]],
  codec: HistoryCodec[BasePage],
  getPageTitle: BasePage => String,
  routeFallback: String => BasePage = Router.throwOnUnknownUrl,
  popStateEvents: EventStream[dom.PopStateEvent] = windowEvents(_.onPopState),
  preventNavigation: NavigationEvent[BasePage] => EventStream[Boolean] = (_: NavigationEvent[BasePage]) => EventStream.fromValue(false),
  protected val owner: Owner = unsafeWindowOwner,
  val origin: String = Router.canonicalDocumentOrigin,
  currentUrl: => String = dom.window.location.href
) extends Router.All[BasePage] {

  /** Sequential index of the history entry the app has navigated to — the entry whose page we
    * render. Stamped into every history state record we write, so a `popState` can be told to be
    * a back or a forward by comparing the target entry's index with this one. Nothing else can:
    * two adjacent entries can share a URL.
    *
    * Tracks the *committed* position, not the browser's current one. The browser moves
    * before it tells us (a `popState` arrives after the fact), and we may refuse to follow, so
    * during a pending or prevented navigation the two disagree. Holding this still until a
    * navigation commits is what lets [[restoreCommittedEntry]] know where to return to, however
    * far the browser has wandered.
    */
  private var committedIndex: Int = 0

  /** Index of the history entry the browser is on, as opposed to the one the app is on
    * ([[committedIndex]]). They differ whenever the browser has moved and we have not followed:
    * while a guard is deciding about a Back, and after, if we decided against it, until the undo
    * traversal lands.
    *
    * Remembered rather than looked up. `history.state` describes the entry the browser is on, but
    * only the one it is on *right now*, which during a `popState` need not be the entry that event
    * is about: the browser processes queued traversals faster than it delivers their events, so
    * `history.state` can be one or more entries ahead of `ev.state`. Measured in Chrome, with two
    * Backs pressed in quick succession.
    */
  private var browserIndex: Int = 0

  /** Set while an undo of a prevented browser navigation is in flight.
    *
    * The undo moves the browser, firing a `popState` we must ignore — else we would read our own
    * undo as a fresh user navigation the other way, prevent that too, and undo the undo forever.
    *
    * It records *where the undo is meant to land*, rather than just "ignore the next `popState`".
    * A bare flag would be wrong both ways: a user navigation can arrive before our undo (two quick
    * Back presses) and wrongly consume it, and the browser may not deliver a `popState` for our
    * own traversal at all (it can coalesce it with a pending one), leaving the flag to ambush a
    * later real event.
    */
  private var pendingUndoOpt: Option[UndoExpectation] = None

  /** Relative URL of the entry [[committedIndex]] refers to. Only needed to recognize an undo that
    * lands on an entry carrying no state of ours — which happens when the app started on a URL
    * that matched no route, so we never stamped the initial entry.
    */
  private var committedRelativeUrl: String = ""

  /** If defined, a navigation request is still pending because `preventNavigation` has not
    * answered about it yet. A navigation requested while it is pending supersedes it.
    */
  private var pendingNavigationOpt: Option[PendingNavigation[BasePage]] = None

  /** You can force the router to emit a page using `forcePage`.
    * This will not update the URL or create/update a history record.
    * But this will update the document title (unless the page's title is empty)
    */
  private val forcePageBus = new EventBus[BasePage]

  private val pageBus = new EventBus[BasePage]

  /** Note: this can be in an errored state if:
    *  - initial [[currentUrl]] does not match [[origin]]
    *  - [[routeFallback]] was invoked and threw an Exception
    *  - [[codec.deserializeFallback]] was invoked and threw an Exception
    */
  val currentPageSignal: StrictSignal[BasePage] = {
    val forcedPage = forcePageBus.events.map { page =>
      val pageTitle = getPageTitle(page)
      if (pageTitle.nonEmpty) {
        replacePageTitle(pageTitle)
      }
      page
    }

    val initialUrl: String = currentUrl
    lazy val initialPage = {
      if (!Utils.absoluteUrlMatchesOrigin(origin, url = initialUrl)) {
        throw new WaypointException(s"Initial URL does not belong to origin: $initialUrl vs $origin/ - Use the full absolute URL for initialUrl, and don't include the trailing slash in origin")
      }

      val maybeInitialRoutePage = pageForAbsoluteUrl(initialUrl)

      // Where we start out, matched or not: an unmatched initial URL leaves the entry unstamped,
      // and this is then the only way to recognize it again.
      committedRelativeUrl = Utils.makeRelativeUrl(origin, initialUrl)

      maybeInitialRoutePage.foreach { page =>
        // #Note
        //  - `handlePopState` relies on us replacing the URL on page load as this sets a non-null state
        //  - We deliberately use `forceUrl` to prevent overwriting the URL with the canonical URL for the page
        //    because the original URL might have query params that the matching page does not track
        committedIndex = 0
        browserIndex = 0
        val pageTitle = getPageTitle(page)
        dom.window.history.replaceState(
          statedata = codec.encodeState(committedIndex, page),
          title = pageTitle,
          url = committedRelativeUrl
        )
        replacePageTitle(pageTitle)
      }

      // Note: routeFallback can throw
      Try(maybeInitialRoutePage.getOrElse(routeFallback(initialUrl)))
    }

    EventStream
      .merge(pageBus.events, forcedPage)
      .startWithTry(initialPage)
      .observe(using owner)
  }

  // --

  popStateEvents.foreach(handlePopState)(using owner)

  // --

  /** @throws Exception when url is malformed */
  def pageForAbsoluteUrl(url: String): Option[BasePage] = {
    if (Utils.absoluteUrlMatchesOrigin(origin, url = url)) {
      val relativeUrl = url.substring(origin.length)
      pageForRelativeUrl(relativeUrl)
    } else if (url == origin) {
      // Special case to make this case work
      pageForRelativeUrl("/")
    } else {
      None
    }
  }

  /** @throws Exception when url is not relative or is malformed */
  def pageForRelativeUrl(url: String): Option[BasePage] = {
    if (!Utils.isRelative(url)) {
      throw new WaypointException("Relative URL must be relative to the origin, i.e. it must start with /, whereas `$url` was given.")
    }
    var page: Option[BasePage] = None
    routes.exists { route =>
      page = route.pageForRelativeUrl(origin, url)
      page.isDefined
    }
    page
  }

  /** Note: This does not consider fallback pages unless you provided routes for them.
    *
    * @throws Exception when matching route is not found in router.
    */
  def absoluteUrlForPage(page: BasePage): String = {
    origin + relativeUrlForPage(page)
  }

  /** Note: This does not consider fallback pages unless you provided routes for them.
    *
    * @throws Exception when matching route is not found in router.
    */
  def relativeUrlForPage(page: BasePage): String = {
    var url: Option[String] = None
    routes.exists { route =>
      url = route.relativeUrlForPage(page)
      url.isDefined
    }
    url.getOrElse(throw new WaypointException(s"Router has no route for this page: $page"))
  }

  // @TODO[Naming] Rename {push,replace}State to {push,replace}Page?
  //  - Follows History API naming now
  //  - But forcePage can't be forceState, since that doesn't actually touch history state
  //  - Maybe it's for the better the way it is...

  /** Pushes state by creating a new history record.
    *
    * This is the standard method to call to navigate to another page.
    *
    * [[https://developer.mozilla.org/en-US/docs/Web/API/History/pushState pushState @ MDN]]
    * [[https://developer.mozilla.org/en-US/docs/Web/API/History_API History API @ MDN]]
    */
  def pushState(page: BasePage): Unit = {
    Try(submitNavigation(navigationRequestFromPage(page, replace = false))) match {
      case Success(_) => ()
      case Failure(err) => AirstreamError.sendUnhandledError(err)
    }
  }

  /** Replaces state without creating a new history record.
    *
    * Call this when you want to "erase" the current page from history, replacing it with the new page.
    * For example, you may want to replace the login page with the home page after a successful log in,
    * so that if the user goes "back", they skip over the login page to go back further to the previous page.
    *
    * [[https://developer.mozilla.org/en-US/docs/Web/API/History/replaceState replaceState @ MDN]]
    * [[https://developer.mozilla.org/en-US/docs/Web/API/History_API History API @ MDN]]
    */
  def replaceState(page: BasePage): Unit = {
    Try(submitNavigation(navigationRequestFromPage(page, replace = true))) match {
      case Success(_) => ()
      case Failure(err) => AirstreamError.sendUnhandledError(err)
    }
  }

  /** Replaces just the document title (and updates the current history record with it).
    *
    * If your `Page` type does not have enough information in it to set a
    * meaningful document title, for example because you need to asynchronously
    * fetch data containing the name/title from the backend, then you can call
    * this method to update the document title once you know what it should be.
    *
    * This will work like [[replaceState]], except it will only update the title
    * in the current history record, keeping the URL and other state data intact.
    */
  def replacePageTitle(title: String): Unit = {
    dom.document.title = title
  }

  /** Forces router.currentPageSignal to emit this page without updating the URL or touching the history records.
    *
    * Use this to display a full screen "not found" page when the route matched but is invalid
    * for example because /user/123 refers to a non-existing user, something that you can't
    * know during route matching.
    *
    * Note, this will update dom.document.title unless the provided page's title is empty.
    * This will update the title of the current record in the history API.
    */
  def forcePage(page: BasePage): Unit = {
    forcePageBus.emit(page)
  }

  /** This returns a Laminar modifier that should be used like this:
    *
    * {{{
    * a(router.navigateTo(libraryPage), "Library")
    * button(router.navigateTo(logoutPage), "Log out")
    * }}}
    *
    * When the element is clicked, it triggers Waypoint navigation to the provided page.
    *
    * When used with `a` link elements:
    *  - This modifier also sets the `href` attribute to the page's absolute URL
    *  - This modifier ignores clicks when the user is holding a modifier key like Ctrl/Shift/etc. while clicking
    *    - In that case, the browser's default link action (e.g. open in new tab) will happen instead
    */
  def navigateTo(
    page: BasePage,
    replaceState: Boolean = false
  ): Binder[Element] = Binder { el =>
    // #TODO[Airstream/Laminar] We should not need two redundant implementations of navigateTo (but we want to optimize performance)
    //  - We should work on StaticSource type, and perhaps add `mapSource` method on Source for some simple transforms?
    // #TODO[API] What about custom elements / web components? Do we need special handling for them?
    val isLinkElement = el.ref.isInstanceOf[dom.html.Anchor]

    if (isLinkElement) {
      Try(absoluteUrlForPage(page)) match {
        case Success(url) => href(url)(el.asInstanceOf[HtmlElement])
        case Failure(err) => AirstreamError.sendUnhandledError(err)
      }
    }

    // If element is a link, AND user was holding a modifier key while clicking:
    //  - Do nothing, browser will open the URL in new tab / window / etc. depending on the modifier key
    // Otherwise:
    //  - Perform regular pushState/replaceState transition

    val onRegularClick = onClick
      .filter(ev => !(isLinkElement && (ev.ctrlKey || ev.metaKey || ev.shiftKey || ev.altKey)))
      .preventDefault

    (onRegularClick --> { _ =>
      if (replaceState) {
        this.replaceState(page)
      } else {
        pushState(page)
      }
    }).bind(el)
  }

  /** This is similar to the other [[navigateTo]] method, except it takes a Signal[Page] rather than just Page.
    *
    * This returns a Laminar modifier that should be used like this:
    *
    * {{{
    * val signalOfPage: Signal[Page] = ???
    * a(
    *   router.navigateTo(signalOfPage),
    *   "Go to page"
    * )
    * button(
    *   router.navigateTo(signalOfPage),
    *   signalOfPage.map(_.pageName)
    * )
    * }}}
    *
    * When the element is clicked, it triggers Waypoint navigation to the provided page.
    *
    * When used with `a` link elements:
    *  - This modifier also sets the `href` attribute to the page's absolute URL
    *  - This modifier ignores clicks when the user is holding a modifier key like Ctrl/Shift/etc. while clicking
    *    - In that case, the browser's default link action (e.g. open in new tab) will happen instead
    */
  def navigateTo(
    pageSignal: Signal[BasePage],
    replaceState: Boolean
  ): Binder[Element] = Binder { el =>
    // #TODO[API] What about custom elements / web components? Do we need special handling for them?
    val isLinkElement = el.ref.isInstanceOf[dom.html.Anchor]

    if (isLinkElement) {
      (pageSignal --> { page =>
        Try(absoluteUrlForPage(page)) match {
          case Success(url) =>
            href(url)(el.asInstanceOf[HtmlElement])
          case Failure(err) =>
            AirstreamError.sendUnhandledError(err)
        }
      }).bind(el)
    }

    // If element is a link, AND user was holding a modifier key while clicking:
    //  - Do nothing, browser will open the URL in new tab / window / etc. depending on the modifier key
    // Otherwise:
    //  - Perform regular pushState/replaceState transition

    val onRegularClick = onClick
      .filter(ev => !(isLinkElement && (ev.ctrlKey || ev.metaKey || ev.shiftKey || ev.altKey)))
      .preventDefault

    (onRegularClick(_.sample(pageSignal)) --> { page =>
      if (replaceState) {
        this.replaceState(page)
      } else {
        pushState(page)
      }
    }).bind(el)
  }

  // #TODO[API,Laminar,Airstream] We can't have multiple overloads with default args.
  //  Need to use a single SignalSource for a single, efficient, implementation for all of those.
  /** Version of `navigateTo(pageSignal)` with `replaceState = false (the default)` */
  def navigateTo(
    pageSignal: Signal[BasePage]
  ): Binder[Element] = {
    navigateTo(pageSignal, replaceState = false)
  }

  // --

  private def navigationRequestFromPage(
    page: BasePage,
    replace: Boolean
  ): NavigationRequest[BasePage] = {
    new NavigationRequest[BasePage](
      page = page,
      kind = if (replace) NavigationKind.Replace else NavigationKind.Push,
      relativeUrl = relativeUrlForPage(page),
      pageTitle = getPageTitle(page),
      // #Note Built on where the *browser* is, not where the app is. Normally the same entry;
      //  not when this navigation supersedes a still-pending browser navigation: `pushState`
      //  appends after the entry the browser stands on, so that entry is what the new one
      //  follows, and what its index must follow too.
      destinationIndex = if (replace) browserIndex else browserIndex + 1
    )
  }

  /** This method should be added as a listener on window popstate event. Note:
    * - this event is fired when user navigates with back / forward browser functionality
    * - this event is fired when user changes the fragment / hash of the URL (e.g. by clicking a #link or editing the URL)
    * - this event is not fired for initial page load, or when we call pushState or replaceState
    */
  private def handlePopState(ev: dom.PopStateEvent): Unit = {
    // The browser has already moved, so the URL and `ev.state` describe where it now is.
    val relativeUrl = Utils.makeRelativeUrl(origin, currentUrl)

    // Is this the `popState` fired by an undo of ours, not a navigation by the user? We check
    // where the browser landed, not just whether an undo is outstanding: a user navigation can
    // arrive before our undo's event and must not be mistaken for it.
    if (pendingUndoOpt.exists(_.matches(codec.decodeIndex(ev.state), relativeUrl))) {
      pendingUndoOpt = None
      browserIndex = committedIndex
      return
    }

    // Where the app is. The guard is asked about a move away from *this* entry, so this decides
    // whether the browser went back or forward as far as the app is concerned.
    val originIndex = committedIndex

    // Where the browser stood before this navigation. An entry carrying no index of ours gives
    // us nothing to place it by, so we assume the browser appended it right where it stood — as
    // a hash change does. This is the browser's position, not the app's: the same entry unless
    // this navigation arrives on top of another still waiting on the guard.
    val browserOriginIndex = browserIndex

    if (ev.state == null) {
      // Respond to hashChange events here.
      // - hashChange events trigger popState events, like navigation
      // - Unfortunately we can't easily tell which events are coming from hashChange except that ev.state is null
      // - However, when navigating back to the original, initialUrl, ev.state would also normally be null,
      //   but that's not a problem for us because we replaceState on page load, and that replaces the original
      //   history record with one that does have ev.state
      // - So end result is that we can tell that this here is a hashChange event just by looking at ev.state == null
      // https://stackoverflow.com/a/33169145/2601788
      val page = pageForAbsoluteUrl(currentUrl).getOrElse(routeFallback(currentUrl))
      submitBrowserNavigation(new NavigationRequest[BasePage](
        page = page,
        kind = NavigationKind.HashChange,
        relativeUrl = relativeUrl,
        pageTitle = getPageTitle(page),
        destinationIndex = browserOriginIndex + 1
      ))
    } else {
      codec.decodeState(ev.state) match {
        case Some((destinationNavigationIndex, destinationPage)) =>
          val kind =
            if (destinationNavigationIndex < originIndex) {
              NavigationKind.Back
            } else {
              NavigationKind.Forward
            }
          submitBrowserNavigation(new NavigationRequest[BasePage](
            page = destinationPage,
            kind = kind,
            relativeUrl = relativeUrl,
            pageTitle = getPageTitle(destinationPage),
            destinationIndex = destinationNavigationIndex
          ))
        case None =>
          // Malformed history API state. We can't know its position in history,
          // so we treat it like a hash change: best-effort, assume forward.
          val page = codec.deserializeFallback(ev.state)
          submitBrowserNavigation(new NavigationRequest[BasePage](
            page = page,
            kind = NavigationKind.HashChange, // #TODO[block] Why HashChange?
            relativeUrl = relativeUrl,
            pageTitle = getPageTitle(page),
            destinationIndex = browserOriginIndex + 1
          ))
      }
    }
  }

  /** Submits a navigation the browser has *already* performed, recording where it moved to
    * before we decide whether to keep it.
    */
  private def submitBrowserNavigation(request: NavigationRequest[BasePage]): Unit = {
    browserIndex = request.destinationIndex
    submitNavigation(request)
  }

  private def submitNavigation(request: NavigationRequest[BasePage]): Unit = {
    // Last one wins: a navigation requested while another awaits the guard supersedes it, rather
    // than being dropped in its favour. This matches other SPA routers (Vue Router reports the
    // loser as a `cancelled` failure, Angular as a `NavigationCancel` event), and is what an app
    // can work with: a guard is typically pending because a confirmation dialog is on screen, and
    // whatever the user does next — Back, a link, an app redirect — must be able to take over.
    // Dropping the newer request instead leaves the app wedged behind a question nobody answers.
    pendingNavigationOpt.foreach(supersede)

    val pending = new PendingNavigation(request)
    pendingNavigationOpt = Some(pending)

    // #Note: [[committedIndex]] stays put until this navigation commits, even for a browser
    //  navigation that already moved the browser. It is what we undo back to if the guard says
    //  no, so it must not follow the browser around in the meantime.

    val nav = NavigationEvent[BasePage](
      fromPage = currentPageSignal.now(),
      toPage = request.page,
      kind = request.kind,
      toRelativeUrl = request.relativeUrl
    )

    Try(preventNavigation(nav)) match {
      case Success(stream) =>
        val subscription = stream.take(1).foreach(prevent => resolveNavigation(pending, prevent))(using owner)
        // A synchronous guard (the common `EventStream.fromValue` case) has already resolved this
        // navigation by now, before we ever got hold of its subscription.
        if (pending.isResolved) {
          subscription.kill()
        } else {
          pending.holdGuard(subscription)
        }
      case Failure(err) =>
        AirstreamError.sendUnhandledError(err)
        resolveNavigation(pending, prevent = false) // fail open
    }
  }

  /** Abandons a pending navigation in favour of a newer one.
    *
    * Nothing is undone here. A superseded router-initiated navigation never touched the browser;
    * a superseded browser-initiated one left the browser somewhere the navigation now replacing
    * it is responsible for: another browser navigation has moved it on again, and a
    * router-initiated one builds on wherever it now stands (see [[navigationRequestFromPage]])
    * and brings it home if the guard refuses it (see [[restoreCommittedEntry]]).
    */
  private def supersede(pending: PendingNavigation[BasePage]): Unit = {
    pendingNavigationOpt = None
    pending.resolve() // Any answer arriving from its guard after this point is ignored.
  }

  private def resolveNavigation(pending: PendingNavigation[BasePage], prevent: Boolean): Unit = {
    // #Note The `isResolved` check upholds an invariant, not a known path: `take(1)` already
    //  limits a guard to one answer, and killing a superseded navigation's subscription stops
    //  its stream delivering one at all, even mid-propagation (Airstream defers the removal, but
    //  on a stream no longer feeding anything). It is here so "a navigation is acted on at most
    //  once" does not depend on both of those holding.
    if (!pending.isResolved) {
      pending.resolve()
      if (pendingNavigationOpt.contains(pending)) {
        pendingNavigationOpt = None
      }
      if (prevent) {
        restoreCommittedEntry()
      } else {
        commitNavigation(pending.request)
      }
    }
  }

  private def commitNavigation(pending: NavigationRequest[BasePage]): Unit = {
    lazy val encodedState =
      codec.encodeState(pending.destinationIndex, pending.page)
    pending.kind match {
      case NavigationKind.Push =>
        setCommittedEntry(pending)
        dom.window.history.pushState(
          statedata = encodedState,
          title = pending.pageTitle,
          url = pending.relativeUrl
        )

      case NavigationKind.Replace =>
        setCommittedEntry(pending)
        dom.window.history.replaceState(
          statedata = encodedState,
          title = pending.pageTitle,
          url = pending.relativeUrl
        )

      case NavigationKind.HashChange =>
        // Re-stamp the browser-created entry so that future back / forward to it can be detected
        setCommittedEntry(pending)
        dom.window.history.replaceState(
          statedata = encodedState,
          title = pending.pageTitle,
          url = pending.relativeUrl
        )

      case NavigationKind.Back | NavigationKind.Forward =>
        // The browser already performed this navigation, so there is nothing to write; we just
        // adopt the position it moved to as our own.
        setCommittedEntry(pending)
    }

    replacePageTitle(pending.pageTitle)
    pageBus.emit(pending.page)
  }

  /** Records where we are now, and forgets any undo that was aiming at where we used to be. */
  private def setCommittedEntry(pending: NavigationRequest[BasePage]): Unit = {
    committedIndex = pending.destinationIndex
    committedRelativeUrl = pending.relativeUrl
    browserIndex = pending.destinationIndex
    pendingUndoOpt = None
  }

  /** Brings the browser back to the entry the app is on, undoing whatever move it made that we
    * will not follow.
    *
    * It asks the same question whatever was refused — "where is the browser, and where should it
    * be?" — rather than inverting a particular navigation. A refused Back is the obvious case,
    * but a refused `pushState` needs it too: that push may have superseded a browser navigation,
    * leaving the browser a step away from us even though the push wrote nothing. When the browser
    * is already where it belongs (the usual case for a refused `pushState`), this does nothing.
    *
    * We move by the whole distance, not a step at a time. The browser can be several entries away
    * — a user can jump several at once from the Back-button menu, and it keeps moving while a
    * guard is pending. Stepping one at a time would get home eventually, but treat each entry it
    * crosses as a fresh navigation: one guard consultation, so potentially one prompt, per entry.
    * Going by delta is one traversal and one `popState`, however far we go.
    */
  private def restoreCommittedEntry(): Unit = {
    val delta = committedIndex - browserIndex
    if (delta != 0) { // .go(0) would reload the document – avoid that.
      pendingUndoOpt = Some(new UndoExpectation(committedIndex, committedRelativeUrl))
      dom.window.history.go(delta)
    }
  }


}

object Router {

  /** The part of router that does not depend on the page type. */
  trait Shared {
    /** @see [[Router.replacePageTitle]] */
    def replacePageTitle(title: String): Unit

    /** @see [[Router.origin]] */
    val origin: String
  }

  /** The contravariant side of the router. */
  trait In[-BasePage] extends Shared {

    /** @see [[Router.absoluteUrlForPage]] */
    def absoluteUrlForPage(page: BasePage): String

    /** @see [[Router.relativeUrlForPage]] */
    def relativeUrlForPage(page: BasePage): String

    /** @see [[Router.pushState]] */
    def pushState(page: BasePage): Unit

    /** @see [[Router.replaceState]] */
    def replaceState(page: BasePage): Unit

    /** @see [[Router.forcePage]] */
    def forcePage(page: BasePage): Unit

    /** @see [[Router.navigateTo]] */
    def navigateTo(
      page: BasePage,
      replaceState: Boolean = false
    ): Binder[Element]

    /** @see [[Router.navigateTo]] */
    def navigateTo(
      pageSignal: Signal[BasePage],
      replaceState: Boolean
    ): Binder[Element]

    /** @see [[Router.navigateTo]] */
    def navigateTo(
      pageSignal: Signal[BasePage]
    ): Binder[Element]
  }

  /** The covariant side of the router. */
  trait Out[+BasePage] extends Shared {
    /** @see [[Router.currentPageSignal]] */
    val currentPageSignal: StrictSignal[BasePage]

    /** @see [[Router.pageForAbsoluteUrl]] */
    def pageForAbsoluteUrl(url: String): Option[BasePage]

    /** @see [[Router.pageForRelativeUrl]] */
    def pageForRelativeUrl(url: String): Option[BasePage]
  }

  /** The full router. */
  trait All[BasePage] extends Router.In[BasePage] with Router.Out[BasePage]

  /** Like Route.fragmentBasePath, but can be used locally with file:// URLs */
  def localFragmentBasePath: String = {
    if (canonicalDocumentOrigin == "file://") {
      dom.window.location.pathname + "#"
    } else {
      Route.fragmentBasePath
    }
  }

  private val throwOnUnknownUrl = (url: String) => {
    throw new WaypointException("Unable to parse URL into a Page, it does not match any routes: " + url)
  }

  /** In Firefox, `file://` URLs have "null" (a string) as location.origin instead of "file://" like in Chrome or Safari.
    * This helper fixes this discrepancy.
    */
  private val canonicalDocumentOrigin: String = {
    if (dom.document.location.protocol == "file:") "file://" else dom.document.location.origin
  }
}
