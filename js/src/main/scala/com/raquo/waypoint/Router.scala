package com.raquo.waypoint

import com.raquo.laminar.api.L._
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
  *                              While such a stream is pending, any other navigation is rejected outright.
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
  routes: List[Route[_ <: BasePage, _]],
  codec: HistoryCodec[BasePage],
  getPageTitle: BasePage => String,
  routeFallback: String => BasePage = Router.throwOnUnknownUrl,
  popStateEvents: EventStream[dom.PopStateEvent] = windowEvents(_.onPopState),
  preventNavigation: NavigationEvent[BasePage] => EventStream[Boolean] = (_: NavigationEvent[BasePage]) => EventStream.fromValue(false),
  protected val owner: Owner = unsafeWindowOwner,
  val origin: String = Router.canonicalDocumentOrigin,
  currentUrl: => String = dom.window.location.href
) extends Router.All[BasePage] {

  /** The stamp / sequential index of the history entry that the browser is currently on.
    * We write this index into every history state record we create so that we can later tell
    * whether a `popState` event navigated back or forward (by comparing the target entry's
    * index with this one).
    */
  private var navigationIndex: Int = 0

  /** Set when we undo a browser back / forward navigation that was blocked by [[preventNavigation]].
    * The `history.back()` / `history.forward()` call we use to undo such navigation triggers its own
    * `popState` event, which we must ignore.
    */
  private var suppressNextPopState: Boolean = false

  /** If defined, it means that the previous navigation request is still pending
    * due to `preventNavigation` not having allowed us to proceed yet.
    * All other navigation should be rejected until this one resolves.
    */
  private var pendingNavigationOpt: Option[NavigationRequest[BasePage]] = None

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

      maybeInitialRoutePage.foreach { page =>
        // #Note
        //  - `handlePopState` relies on us replacing the URL on page load as this sets a non-null state
        //  - We deliberately use `forceUrl` to prevent overwriting the URL with the canonical URL for the page
        //    because the original URL might have query params that the matching page does not track
        navigationIndex = 0
        val pageTitle = getPageTitle(page)
        val relativeUrl = Utils.makeRelativeUrl(origin, initialUrl)
        dom.window.history.replaceState(
          statedata = codec.encodeState(navigationIndex, page),
          title = pageTitle,
          url = relativeUrl
        )
        replacePageTitle(pageTitle)
      }

      // Note: routeFallback can throw
      Try(maybeInitialRoutePage.getOrElse(routeFallback(initialUrl)))
    }

    EventStream
      .merge(pageBus.events, forcedPage)
      .startWithTry(initialPage)
      .observe(owner)
  }

  // --

  popStateEvents.foreach(handlePopState)(owner)

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
      originIndex = navigationIndex,
      destinationIndex = if (replace) navigationIndex else navigationIndex + 1
    )
  }

  /** This method should be added as a listener on window popstate event. Note:
    * - this event is fired when user navigates with back / forward browser functionality
    * - this event is fired when user changes the fragment / hash of the URL (e.g. by clicking a #link or editing the URL)
    * - this event is not fired for initial page load, or when we call pushState or replaceState
    */
  private def handlePopState(ev: dom.PopStateEvent): Unit = {
    if (suppressNextPopState) {
      suppressNextPopState = false
      return
    }

    val originIndex = navigationIndex

    // The URL has already updated
    val relativeUrl = Utils.makeRelativeUrl(origin, currentUrl)

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
      submitNavigation(new NavigationRequest[BasePage](
        page = page,
        kind = NavigationKind.HashChange,
        relativeUrl = relativeUrl,
        pageTitle = getPageTitle(page),
        originIndex = originIndex,
        destinationIndex = originIndex + 1
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
          submitNavigation(new NavigationRequest[BasePage](
            page = destinationPage,
            kind = kind,
            relativeUrl = relativeUrl,
            pageTitle = getPageTitle(destinationPage),
            originIndex = originIndex,
            destinationIndex = destinationNavigationIndex
          ))
        case None =>
          // Malformed history API state. We can't know its position in history,
          // so we treat it like a hash change: best-effort, assume forward.
          val page = codec.deserializeFallback(ev.state)
          submitNavigation(new NavigationRequest[BasePage](
            page = page,
            kind = NavigationKind.HashChange, // #TODO[block] Why HashChange?
            relativeUrl = relativeUrl,
            pageTitle = getPageTitle(page),
            originIndex = originIndex,
            destinationIndex = originIndex + 1
          ))
      }
    }
  }

  private def submitNavigation(pending: NavigationRequest[BasePage]): Unit = {
    pendingNavigationOpt match {
      case Some(_) =>
        // A navigation guard is already pending. Reject this new navigation outright:
        //  - App-initiated navigations did not even touch the browser history yet, so we just drop them.
        //  - Browser navigations have already happened, so we undo them immediately (no guard, no render).
        pending.kind match {
          case _: NavigationKind.RouterInitiated => ()
          case _ => revertNavigation(pending)
        }

      case None =>
        pendingNavigationOpt = Some(pending)

        // Mirror the browser's actual position while we wait for the guard to resolve.
        //  - For push / replace, the browser has NOT moved yet, so internalIndex stays put until commit.
        //  - For pop kinds, the browser has ALREADY moved, so update internalIndex now.
        if (pending.kind != NavigationKind.Push && pending.kind != NavigationKind.Replace) {
          navigationIndex = pending.destinationIndex
        }

        val nav = NavigationEvent[BasePage](
          fromPage = currentPageSignal.now(),
          toPage = pending.page,
          kind = pending.kind,
          toRelativeUrl = pending.relativeUrl
        )

        Try(preventNavigation(nav)) match {
          case Success(stream) =>
            stream.take(1).foreach(prevent => resolveNavigation(prevent))(owner)
          case Failure(err) =>
            AirstreamError.sendUnhandledError(err)
            resolveNavigation(prevent = false) // fail open
        }
    }
  }

  private def resolveNavigation(prevent: Boolean): Unit = {
    val pending = pendingNavigationOpt.getOrElse(
      throw new WaypointException("resolveNavigation does not have a pending navigation")
    )
    pendingNavigationOpt = None
    if (prevent) {
      revertNavigation(pending)
    } else {
      commitNavigation(pending)
    }
  }

  private def commitNavigation(pending: NavigationRequest[BasePage]): Unit = {
    lazy val encodedState =
      codec.encodeState(pending.destinationIndex, pending.page)
    pending.kind match {
      case NavigationKind.Push =>
        navigationIndex = pending.destinationIndex
        dom.window.history.pushState(
          statedata = encodedState,
          title = pending.pageTitle,
          url = pending.relativeUrl
        )

      case NavigationKind.Replace =>
        navigationIndex = pending.destinationIndex
        dom.window.history.replaceState(
          statedata = encodedState,
          title = pending.pageTitle,
          url = pending.relativeUrl
        )

      case NavigationKind.HashChange =>
        // Re-stamp the browser-created entry so that future back / forward to it can be detected
        navigationIndex = pending.destinationIndex
        dom.window.history.replaceState(
          statedata = encodedState,
          title = pending.pageTitle,
          url = pending.relativeUrl
        )

      case NavigationKind.Back | NavigationKind.Forward =>
        () // Browser already performed the navigation; internalIndex was already updated when the request arrived.
    }

    replacePageTitle(pending.pageTitle)
    pageBus.emit(pending.page)
  }

  private def revertNavigation(pending: NavigationRequest[BasePage]): Unit = {
    pending.kind match {
      case _: NavigationKind.RouterInitiated =>
        () // We never wrote anything to history, so there is nothing to undo.

      case NavigationKind.Back =>
        navigationIndex = pending.originIndex
        suppressNextPopState = true
        dom.window.history.forward()

      case NavigationKind.Forward | NavigationKind.HashChange =>
        navigationIndex = pending.originIndex
        suppressNextPopState = true
        dom.window.history.back()
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
