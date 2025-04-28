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
  * @param serializePage       - encode page into a History API state record. Return any string here (JSON string is ok)
  *                              This is called any time you trigger navigation to a page.
  *
  * @param deserializePage     - parse the result of [[serializePage]] back into a page.
  *                              This is called when user navigates using browser back / forward buttons and we load
  *                              history state
  *                              If you throw here, [[deserializeFallback]] will be called instead.
  *
  * @param routeFallback       - receives URL as input (either initialUrl or on hashChange event) and returns a page
  *                              to render when none of the routes match on initial page load.
  *                              When rendering a fallback page, the URL will stay the same.
  *                              If you want to perform any logic (such as redirects) in case of this fallback,
  *                              put it in the renderer (whatever you have responding to router.\$currentPage).
  *
  * @param deserializeFallback - receives raw state from History API as input and returns a page to render when
  *                              the entry in the history can not be deserialized using [[deserializePage]].
  *                              If you want to perform any logic (such as redirects) in case of this fallback,
  *                              put it in the renderer (whatever you have responding to router.\$currentPage).
  *
  * @param popStateEvents      - typically windowEvents(_.onPopState) in Laminar
  *
  * @param owner               - typically unsafeWindowOwner in Laminar (if the router should never die, i.e. it's a whole app router)
  *
  * @param origin              - e.g. "http://localhost:8080", without trailing slash
  *
  * @param initialUrl          - e.g. "http://localhost:8080/page"
  *
  * @throws Exception          - when [[initialUrl]] is not absolute or does not match [[origin]]
  */
class Router[BasePage](
  routes: List[Route[_ <: BasePage, _]],
  serializePage: BasePage => String,
  deserializePage: String => BasePage,
  getPageTitle: BasePage => String,
  routeFallback: String => BasePage = Router.throwOnUnknownUrl,
  deserializeFallback: Any => BasePage = Router.throwOnInvalidState,
  popStateEvents: EventStream[dom.PopStateEvent] = windowEvents(_.onPopState),
  protected val owner: Owner = unsafeWindowOwner,
  val origin: String = Router.canonicalDocumentOrigin,
  initialUrl: String = dom.window.location.href
) extends Router.All[BasePage] {

  private val routeEventBus = new EventBus[RouteEvent[BasePage]]

  /** You can force the router to emit a page using `forcePage`.
    * This will not update the URL or create/update a history record.
    * But this will update the document title (unless the page's title is empty)
    */
  private val forcePageBus = new EventBus[BasePage]

  /** Note: this can be in an errored state if:
    *  - [[initialUrl]] does not match [[origin]]
    *  - [[routeFallback]] was invoked and threw an Exception
    *  - [[deserializeFallback]] was invoked and threw an Exception
    */
  val currentPageSignal: StrictSignal[BasePage] = {
    val pageFromRoute = routeEventBus.events.map(_.page)
    val forcedPage = forcePageBus.events.map { page =>
      val pageTitle = getPageTitle(page)
      if (pageTitle.nonEmpty) {
        replacePageTitle(pageTitle)
      }
      page
    }

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
        val routeEvent = pageToRouteEvent(
          page,
          replace = true,
          fromPopState = false,
          forceUrl = Utils.makeRelativeUrl(origin, initialUrl)
        )
        handleRouteEvent(routeEvent)
      }

      // Note: routeFallback can throw
      Try(maybeInitialRoutePage.getOrElse(routeFallback(initialUrl)))
    }

    EventStream
      .merge(pageFromRoute, forcedPage)
      .startWithTry(initialPage)
      .observe(owner)
  }

  // --

  routeEventBus.events.foreach(handleRouteEvent)(owner)

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
    routeEventBus.writer.onTry(Try(pageToRouteEvent(page, replace = false, fromPopState = false)))
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
    routeEventBus.writer.onTry(Try(pageToRouteEvent(page, replace = true, fromPopState = false)))
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
    // #TODO[API] What about custom elements / web components? Do we need special handling for them?
    val isLinkElement = el.ref.isInstanceOf[dom.html.Anchor]

    if (isLinkElement) {
      Try(absoluteUrlForPage(page)) match {
        case Success(url) => el.asInstanceOf[HtmlElement].amend(href(url))
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

  // --

  private def pageToRouteEvent(
    page: BasePage,
    replace: Boolean,
    fromPopState: Boolean,
    forceUrl: String = ""
  ): RouteEvent[BasePage] = {
    new RouteEvent(
      page,
      stateData = serializePage(page),
      pageTitle = getPageTitle(page),
      url = if (forceUrl.nonEmpty) forceUrl else relativeUrlForPage(page),
      replace = replace,
      fromPopState = fromPopState
    )
  }

  private def handleRouteEvent(ev: RouteEvent[BasePage]): Unit = {
    if (!ev.fromPopState) {
      if (ev.replace) {
        dom.window.history.replaceState(statedata = ev.stateData, title = ev.pageTitle, url = ev.url)
      } else {
        dom.window.history.pushState(statedata = ev.stateData, title = ev.pageTitle, url = ev.url)
      }
    }
    // 1) Browsers don't currently support the `title` argument in pushState / replaceState
    // 2) We need to set the title when popping state too
    replacePageTitle(ev.pageTitle)
  }

  /** This method should be added as a listener on window popstate event. Note:
    * - this event is fired when user navigates with back / forward browser functionality
    * - this event is fired when user changes the fragment / hash of the URL (e.g. by clicking a #link or editing the URL)
    * - this event is not fired for initial page load, or when we call pushState or replaceState
    */
  private def handlePopState(ev: dom.PopStateEvent): Unit = {
    val routeEvent = (ev.state: Any) match {

      case stateStr: String =>
        val page = Try(deserializePage(stateStr)).getOrElse(deserializeFallback(ev.state))
        pageToRouteEvent(page, replace = false, fromPopState = true)

      case null =>
        // Respond to hashChange events here.
        // - hashChange events trigger popState events, like navigation
        // - Unfortunately we can't easily tell which events are coming from hashChange except that ev.state is null
        // - However, when navigating back to the original, initialUrl, ev.state would also normally be null,
        //   but that's not a problem for us because we replaceState on page load, and that replaces the original
        //   history record with one that does have ev.state
        // - So end result is that we can tell that this here is a hashChange event just by looking at ev.state == null
        // https://stackoverflow.com/a/33169145/2601788
        val absoluteUrl = dom.window.location.href
        val relativeUrl = Utils.makeRelativeUrl(origin, absoluteUrl)
        val page = pageForAbsoluteUrl(absoluteUrl).getOrElse(routeFallback(absoluteUrl))
        pageToRouteEvent(page, replace = false, fromPopState = true, forceUrl = relativeUrl)

      case _ =>
        val fallbackPage = deserializeFallback(ev.state)
        pageToRouteEvent(fallbackPage, replace = false, fromPopState = true)
    }
    routeEventBus.emit(routeEvent)
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

  /** History State can be any serializable JS value. Could be a string, a plain object, etc.
    * Waypoint itself uses the result of `serializePage` which is a string.
    */
  private val throwOnInvalidState = (state: Any) => {
    // @TODO `null` is needed to work around https://github.com/lampepfl/dotty/issues/11943, drop it later
    throw new WaypointException("Unable to deserialize history state: " + JSON.stringify(state.asInstanceOf[js.Any], null))
  }

  /** In Firefox, `file://` URLs have "null" (a string) as location.origin instead of "file://" like in Chrome or Safari.
    * This helper fixes this discrepancy.
    */
  private val canonicalDocumentOrigin: String = {
    if (dom.document.location.protocol == "file:") "file://" else dom.document.location.origin
  }
}
