package com.raquo.waypoint

import com.raquo.airstream.core.EventStream
import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.{StrictSignal, Var}
import org.scalajs.dom

import scala.util.Try

/**
  * @throws Exception when `initialUrl` does is not absolute or does not fit `origin`
  *
  * @param owner typically unsafeWindowOwner in Laminar (if the router should never die, i.e. it's a whole app router)
  * @param $popStateEvent typically windowEvents.onPopState in Laminar
  *
  * @param initialUrl  typically dom.window.location.href, e.g. "http://localhost:8080/page"
  * @param origin   typically dom.window.location.origin.get e.g. "http://localhost:8080"
  */
class Router[BasePage](
  initialUrl: String,
  origin: String,
  routes: List[Route[_ <: BasePage, _]],
  owner: Owner,
  $popStateEvent: EventStream[dom.PopStateEvent],
  getPageTitle: BasePage => String,
  serializePage: BasePage => String,
  deserializePage: String => BasePage
) {

  private val initialRouteEvent = Try {
    if (!initialUrl.startsWith(origin + "/")) {
      throw new Exception(s"Initial URL does not belong to origin: $initialUrl vs $origin/ - Use the full absolute URL for initialUrl, and that don't include the trailing slash in origin")
    }

    val page = pageForAbsoluteUrl(initialUrl)
      .getOrElse(throw new Exception("Unable to parse initial URL into a Page: " + initialUrl))

    pageToRouteEvent(page, replace = true, fromPopState = false)
  }

  private val $routeEvent = Var.fromTry(initialRouteEvent)

  val $currentPage: StrictSignal[BasePage] = $routeEvent.signal.map { ev =>
    ev
  }.map(_.page).observe(owner)

  // --

  $routeEvent.signal.foreach(handleRouteEvent)(owner)

  $popStateEvent.foreach(handlePopState)(owner)

  // --

  /** @throws Exception when url is malformed */
  def pageForAbsoluteUrl(url: String): Option[BasePage] = {
    if (absoluteUrlMatchesOrigin(origin, url)) {
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
    if (!isRelative(url)) {
      throw new Exception("Relative URL must be relative to the origin, i.e. it must start with /")
    }
    var page: Option[BasePage] = None
    routes.exists { route =>
      page = route.pageForRelativeUrl(origin, url)
      page.isDefined
    }
    page
  }

  /** @throws Exception when matching route is not found in router. */
  def absoluteUrlForPage(page: BasePage): String = {
    origin + relativeUrlForPage(page)
  }

  /** @throws Exception when matching route is not found in router. */
  def relativeUrlForPage(page: BasePage): String = {
    var url: Option[String] = None
    routes.exists { route =>
      url = route.relativeUrlForPage(page)
      url.isDefined
    }
    url.getOrElse(throw new Exception("Router has no route for this page"))
  }

  /** Pushes state by creating a new history record. See History API docs on MDN. */
  def pushState(page: BasePage): Unit = {
    $routeEvent.writer.onTry(Try(pageToRouteEvent(page, replace = false, fromPopState = false)))
  }

  /** Replaces state without creating a new history record. See History API docs on MDN. */
  def replaceState(page: BasePage): Unit = {
    $routeEvent.writer.onTry(Try(pageToRouteEvent(page, replace = true, fromPopState = false)))
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
    val pageTry = Try((ev.state: Any) match {
      case stateStr: String => deserializePage(stateStr)
      case _ => throw new Exception("Unable to deserialize history state because it is not a string: " + ev.state)
    })
    $routeEvent.writer.onTry(pageTry.map { page =>
      pageToRouteEvent(page, replace = false, fromPopState = true)
    })
  }

}
