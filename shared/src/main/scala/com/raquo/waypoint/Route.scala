package com.raquo.waypoint

import urldsl.errors.DummyError
import urldsl.language.{PathSegment, PathSegmentWithQueryParams}
import urldsl.vocabulary.UrlMatching

import scala.reflect.ClassTag

class Route[Page, Args] private (
  encode: Page => Args,
  decode: Args => Page,
  matchPage: Any => Option[Page],
  createRelativeUrl: Args => String,
  matchRelativeUrl: String => Option[Args]
) {

  // @TODO[API] Define a UrlCodec or something?

  def argsFromPage(page: Page): Args = encode(page)

  def relativeUrlForPage(page: Any): Option[String] = {
    matchPage(page).map { matchedPage =>
      val args = encode(matchedPage)
      createRelativeUrl(args)
    }
  }

  /** @throws Exception when url is not absolute or is malformed */
  def pageForAbsoluteUrl(origin: String, url: String): Option[Page] = {
    // @TODO[API] We evaluate the page first, as that will consistently throw in case of malformed URL
    val maybePage = matchRelativeUrl(url).map(decode)
    if (Utils.absoluteUrlMatchesOrigin(origin, url)) {
      maybePage
    } else {
      None
    }
  }

  /** @throws Exception when url is not relative */
  def pageForRelativeUrl(origin: String, url: String): Option[Page] = {
    if (!Utils.isRelative(url)) {
      throw new Exception("Relative URL must be relative to the origin, i.e. it must start with /")
    }
    matchRelativeUrl(origin + url).map(decode)
  }
}

object Route {

  def apply[Page: ClassTag, Args](
    encode: Page => Args,
    decode: Args => Page,
    pattern: PathSegment[Args, DummyError]
  ): Route[Page, Args] = {
    new Route(
      encode = encode,
      decode = decode,
      matchPage = matchPageByClassTag[Page],
      createRelativeUrl = args => "/" + pattern.createPath(args),
      matchRelativeUrl = relativeUrl => pattern.matchRawUrl(relativeUrl).toOption)
  }

  def onlyQuery[Page: ClassTag, QueryArgs](
    encode: Page => QueryArgs,
    decode: QueryArgs => Page,
    pattern: PathSegmentWithQueryParams[Unit, DummyError, QueryArgs, DummyError]
  ): Route[Page, QueryArgs] = {
    new Route(
      encode = encode,
      decode = decode,
      matchPage = matchPageByClassTag[Page],
      createRelativeUrl = args => "/" + pattern.createUrlString(path = (), params = args),
      matchRelativeUrl = relativeUrl => pattern.matchRawUrl(relativeUrl).toOption.map(_.params)
    )
  }

  def withQuery[Page: ClassTag, PathArgs, QueryArgs](
    encode: Page => UrlMatching[PathArgs, QueryArgs],
    decode: UrlMatching[PathArgs, QueryArgs] => Page,
    pattern: PathSegmentWithQueryParams[PathArgs, DummyError, QueryArgs, DummyError]
  ): Route[Page, UrlMatching[PathArgs, QueryArgs]] = {
    new Route(
      encode = encode,
      decode = decode,
      matchPage = matchPageByClassTag[Page],
      createRelativeUrl = args => "/" + pattern.createUrlString(path = args.path, params = args.params),
      matchRelativeUrl = relativeUrl => pattern.matchRawUrl(relativeUrl).toOption
    )
  }

  def static[Page](
    staticPage: Page,
    pattern: PathSegment[Unit, DummyError]
  ): Route[Page, Unit] = {
    new Route[Page, Unit](
      encode = _ => (),
      decode = _ => staticPage,
      matchPage = rawPage => if (rawPage == staticPage) Some(staticPage) else None,
      createRelativeUrl = args => "/" + pattern.createPath(args),
      matchRelativeUrl = relativeUrl => pattern.matchRawUrl(relativeUrl).toOption
    )
  }

  private def matchPageByClassTag[Page: ClassTag](page: Any): Option[Page] = page match {
    case page: Page => Some(page)
    case _ => None
  }
}
