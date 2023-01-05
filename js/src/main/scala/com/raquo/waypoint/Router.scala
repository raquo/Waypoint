package com.raquo.waypoint

import com.raquo.airstream.core.EventStream
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.StrictSignal
import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.util.Try

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
  * @param \$popStateEvent     - typically windowEvents.onPopState in Laminar
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
  deserializeFallback: Any => BasePage = Router.throwOnInvalidState
)(
  popStateEvents: EventStream[dom.PopStateEvent],
  owner: Owner,
  val origin: String = Router.canonicalDocumentOrigin,
  initialUrl: String = dom.window.location.href
) {

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
        dom.document.title = pageTitle
      }
      page
    }

    lazy val initialPage = {
      if (!Utils.absoluteUrlMatchesOrigin(origin, url = initialUrl)) {
        throw new Exception(s"Initial URL does not belong to origin: $initialUrl vs $origin/ - Use the full absolute URL for initialUrl, and don't include the trailing slash in origin")
      }

      val maybeInitialRoutePage = pageForAbsoluteUrl(initialUrl)

      maybeInitialRoutePage.foreach { page =>
        // #Note
        //  - `handlePopState` replies on us replacing the URL on page load as this sets a non-null state
        //  - We deliberately use `forceUrl` to prevent overwriting the URL with the canonical URL for the page
        //    because the original URL might have query params that the matching page does not track
        handleRouteEvent(pageToRouteEvent(page, replace = true, fromPopState = false, forceUrl = Utils.makeRelativeUrl(origin, initialUrl)))
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
      throw new Exception("Relative URL must be relative to the origin, i.e. it must start with /, whereas `$url` was given.")
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
    url.getOrElse(throw new Exception("Router has no route for this page"))
  }

  // @TODO[Naming] Rename {push,replace}State to {push,replace}Page?
  //  - Follows History API naming now
  //  - But forcePage can't be forceState, since that doesn't actually touch history state
  //  - Maybe it's for the better the way it is...

  /** Pushes state by creating a new history record. See History API docs on MDN. */
  def pushState(page: BasePage): Unit = {
    routeEventBus.writer.onTry(Try(pageToRouteEvent(page, replace = false, fromPopState = false)))
  }

  /** Replaces state without creating a new history record. See History API docs on MDN. */
  def replaceState(page: BasePage): Unit = {
    routeEventBus.writer.onTry(Try(pageToRouteEvent(page, replace = true, fromPopState = false)))
  }

  /** Forces router.\$currentPage to emit this page without updating the URL or touching the history records.
    *
    * Use this to display a full screen "not found" page when the route matched but is invalid
    * for example because /user/123 refers to a non-existing user, something that you can't
    * know during route matching.
    *
    * Note, this will update document.title unless the provided page's title is empty.
    * I think updating the title could affect the current record in the history API
    * (not sure, you'd need to check if you care)
    */
  def forcePage(page: BasePage): Unit = {
    forcePageBus.emit(page)
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

  // @TODO[API] I think we'll benefit from some kind of replacePageTitle method
  //  - Rethink API a bit. Do we want page title to be part of Page?
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
    dom.document.title = ev.pageTitle
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

  /** Like Route.fragmentBasePath, but can be used locally with file:// URLs */
  def localFragmentBasePath: String = {
    if (canonicalDocumentOrigin == "file://") {
      dom.window.location.pathname + "#"
    } else {
      Route.fragmentBasePath
    }
  }

  private val throwOnUnknownUrl = (url: String) => {
    throw new Exception("Unable to parse URL into a Page, it does not match any routes: " + url)
  }

  /** History State can be any serializable JS value. Could be a string, a plain object, etc.
    * Waypoint itself uses the result of `serializePage` which is a string.
    */
  private val throwOnInvalidState = (state: Any) => {
    // @TODO `null` is needed to work around https://github.com/lampepfl/dotty/issues/11943, drop it later
    throw new Exception("Unable to deserialize history state: " + JSON.stringify(state.asInstanceOf[js.Any], null))
  }

  /** In Firefox, `file://` URLs have "null" (a string) as location.origin instead of "file://" like in Chrome or Safari.
    * This helper fixes this discrepancy.
    */
  private val canonicalDocumentOrigin: String = {
    if (dom.document.location.protocol == "file:") "file://" else dom.document.location.origin.get
  }
}
