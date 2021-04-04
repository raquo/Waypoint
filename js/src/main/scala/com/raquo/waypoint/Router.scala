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
  * @param routeFallback       - receives initial URL as input and returns a page to render when
  *                              none of the routes match on initial page load.
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
  * @param origin              - e.g. "http://localhost:8080"
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
  routeFallback: String => BasePage = Router.throwOnInvalidInitialUrl,
  deserializeFallback: Any => BasePage = Router.throwOnInvalidState
)(
  $popStateEvent: EventStream[dom.PopStateEvent],
  owner: Owner,
  val origin: String = dom.window.location.origin.get,
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
  val $currentPage: StrictSignal[BasePage] = {
    val $pageFromRoute = routeEventBus.events.map(_.page)
    val $forcedPage = forcePageBus.events.map { page =>
      val pageTitle = getPageTitle(page)
      if (pageTitle.nonEmpty) {
        dom.document.title = pageTitle
      }
      page
    }

    lazy val initialPage = {
      if (!initialUrl.startsWith(origin + "/")) {
        throw new Exception(s"Initial URL does not belong to origin: $initialUrl vs $origin/ - Use the full absolute URL for initialUrl, and that don't include the trailing slash in origin")
      }

      val maybeInitialRoutePage = pageForAbsoluteUrl(initialUrl)

      maybeInitialRoutePage.foreach { page =>
        handleRouteEvent(pageToRouteEvent(page, replace = true, fromPopState = false))
      }

      // Note: routeFallback can throw
      Try(maybeInitialRoutePage.getOrElse(routeFallback(initialUrl)))
    }

    EventStream
      .merge($pageFromRoute, $forcedPage)
      .startWithTry(initialPage)
      .observe(owner)
  }

  // --

  routeEventBus.events.foreach(handleRouteEvent)(owner)

  $popStateEvent.foreach(handlePopState)(owner)

  // --

  /** @throws Exception when url is malformed */
  def pageForAbsoluteUrl(url: String): Option[BasePage] = {
    if (Utils.absoluteUrlMatchesOrigin(origin, url)) {
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
      throw new Exception("Relative URL must be relative to the origin, i.e. it must start with /")
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

  private def pageToRouteEvent(page: BasePage, replace: Boolean, fromPopState: Boolean): RouteEvent[BasePage] = {
    new RouteEvent(
      page,
      stateData = serializePage(page),
      pageTitle = getPageTitle(page),
      url = relativeUrlForPage(page),
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
  }

  /** This method should be added as a listener on window popstate event. Note:
    * - this event is fired when user navigates with back / forward browser functionality.
    * - this event is not fired for initial page load, or when we call pushState or replaceState
    */
  private def handlePopState(ev: dom.PopStateEvent): Unit = {
    // Note: deserializeFallback
    val page = Try((ev.state: Any)).map {
      case stateStr: String =>
        Try(deserializePage(stateStr)).getOrElse(deserializeFallback(ev.state))
      case _ =>
        deserializeFallback(ev.state)
    }
    routeEventBus.emitTry(page.map { page =>
      pageToRouteEvent(page, replace = false, fromPopState = true)
    })
  }

}

object Router {

  private val throwOnInvalidInitialUrl = (initialUrl: String) => {
    throw new Exception("Unable to parse initial URL into a Page: " + initialUrl)
  }

  /** History State can be any serializable JS value. Could be a string, a plain object, etc.
    * Waypoint itself uses the result of `serializePage` which is a string.
    */
  private val throwOnInvalidState = (state: Any) => {
    // @TODO `null` is needed to work around https://github.com/lampepfl/dotty/issues/11943, drop it later
    throw new Exception("Unable to deserialize history state: " + JSON.stringify(state.asInstanceOf[js.Any], null))
  }
}
