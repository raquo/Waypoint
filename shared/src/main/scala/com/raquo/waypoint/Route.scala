package com.raquo.waypoint

import urldsl.errors.DummyError
import urldsl.language.{PathQueryFragmentRepr, PathSegment, PathSegmentWithQueryParams}
import urldsl.vocabulary.PathQueryFragmentMatching

import scala.reflect.ClassTag

/** Encoding and decoding are partial functions. Their partial-ness should be symmetrical,
  * otherwise you'll end up with a route that can parse a URL into a Page but can't encode
  * the page into the same URL (or vice versa)
  *
  * @param matchEncodePF - Match Any to Page, and if successful, encode it into Args
  * @param decodePF      - Decode Args into Page, if args are valid
  * @param basePath      - This string is inserted after `origin`. If not empty, must start with `/`.
  * @tparam Page         - Types of pages that this Route is capable of matching.
  *                        Note: the Route might match only a subset of pages of this type.
  * @tparam Args         - Type of data saved in the URL for pages matched by this route
  *                        Note: the Route might match only a subset of args of this type.
  */
class Route[Page, Args] private(
  matchEncodePF: PartialFunction[Any, Args],
  decodePF: PartialFunction[Args, Page],
  createRelativeUrl: Args => String,
  matchRelativeUrl: String => Option[Args],
  basePath: String
) {

  if (basePath.nonEmpty && !basePath.startsWith("/")) {
    throw new Exception(s"Route's basePath, when not empty, must start with `/`. basePath is `$basePath` for this route.")
  }

  def argsFromPage(page: Page): Option[Args] = encode(page)

  def relativeUrlForPage(page: Any): Option[String] = encode(page).map(args => basePath + createRelativeUrl(args))

  /** @param origin - typically dom.window.location.origin.get e.g. "http://localhost:8080"
    *
    * @throws Exception when url is not absolute or is malformed, or in case of https://github.com/raquo/Airstream#firefox-and-file-urls
    */
  def pageForAbsoluteUrl(origin: String, url: String): Option[Page] = {
    if (origin == "null") {
      throw new Exception("pageForAbsoluteUrl was provided with a \"null\" origin. See https://github.com/raquo/Airstream#firefox-and-file-urls")
    }
    val originMatches = Utils.absoluteUrlMatchesOrigin(origin, url)
    val urlToMatch = if (originMatches) url.substring(origin.length) else url
    // @TODO[API] We evaluate the page unconditionally, as that will consistently throw in case of malformed URL
    val maybePage = matchRelativeUrl(urlToMatch).flatMap(decode) // This just ignores the origin present in the url
    if (originMatches) {
      maybePage
    } else {
      None
    }
  }

  /** @param origin - typically dom.window.location.origin.get e.g. "http://localhost:8080"
    *
    * @throws Exception when url is not relative, or in case of https://github.com/raquo/Airstream#firefox-and-file-urls
    */
  def pageForRelativeUrl(origin: String, url: String): Option[Page] = {
    if (origin == "null") {
      throw new Exception("pageForRelativeUrl was provided with a \"null\" origin. See https://github.com/raquo/Airstream#firefox-and-file-urls")
    }
    if (!Utils.isRelative(url)) {
      throw new Exception(s"Relative URL must be relative to the origin, i.e. it must start with /, whereas `$url` was given.")
    }
    if (url.startsWith(basePath)) {
      val urlWithoutBasePath = url.substring(basePath.length)
      matchRelativeUrl(origin + urlWithoutBasePath).flatMap(decode)
    } else if (Utils.basePathHasEmptyFragment(basePath) && url == Utils.basePathWithoutFragment(basePath)) {
      matchRelativeUrl(origin).flatMap(decode)
    } else {
      None
    }
  }

  private def encode(page: Any): Option[Args] = {
    matchEncodePF
      .andThen(Some(_))
      .applyOrElse(page, (_: Any) => None)
  }

  private def decode(args: Args): Option[Page] = {
    decodePF
      .andThen[Option[Page]](Some(_))
      .applyOrElse(args, (_: Args) => None)
  }
}

object Route {

  // @TODO[URL-DSL] We need better abstractions, like Args[P, Q, F] and UrlPart[P, Q, F].
  //  All these builders should not have such bespoke implementations.

  /** Use this as `basePath` if you want your route to match `/#/foo` instead of `/foo` */
  val fragmentBasePath: String = "/#"

  /** Create a route with path segments only */
  def apply[Page: ClassTag, Args](
    encode: Page => Args,
    decode: Args => Page,
    pattern: PathSegment[Args, DummyError],
    basePath: String = ""
  ): Route[Page, Args] = {
    applyPF(
      matchEncode = matchPageByClassTag[Page, Args](encode),
      decode = { case args => decode(args) },
      pattern = pattern,
      basePath = basePath
    )
  }

  // @TODO[Naming] Not a fan of `applyPF`...
  /** Create a partial route with path segments only
    *
    * In this version you can match only a subset of the Page type.
    * Make sure that the partiality of `matchEncode` mirrors that of
    * `decode`, otherwise you'll have a route that can match a page
    * but can not produce a url for that page (or vice versa).
    *
    * @param matchEncode - convert a Page into args. `Any` because it can be called with pages of other routes.
    */
  def applyPF[Page, Args](
    matchEncode: PartialFunction[Any, Args],
    decode: PartialFunction[Args, Page],
    pattern: PathSegment[Args, DummyError],
    basePath: String = ""
  ): Route[Page, Args] = {
    new Route(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = args => "/" + pattern.createPath(args),
      matchRelativeUrl = relativeUrl => pattern.matchRawUrl(relativeUrl).toOption,
      basePath = basePath
    )
  }

  /** Create a route with page data encoded in query params only */
  def onlyQuery[Page: ClassTag, QueryArgs](
    encode: Page => QueryArgs,
    decode: QueryArgs => Page,
    pattern: PathSegmentWithQueryParams[Unit, DummyError, QueryArgs, DummyError],
    basePath: String = ""
  ): Route[Page, QueryArgs] = {
    onlyQueryPF(
      matchEncode = matchPageByClassTag[Page, QueryArgs](encode),
      decode = { case args => decode(args) },
      pattern = pattern,
      basePath = basePath
    )
  }

  /** Create a partial route with page data encoded in query params only
    *
    * In this version you can match only a subset of the Page type.
    * Make sure that the partiality of `matchEncode` mirrors that of
    * `decode`, otherwise you'll have a route that can match a page
    * but can not produce a url for that page (or vice versa).
    *
    * @param matchEncode - convert a Page into args. `Any` because it can be called with pages of other routes.
    */
  def onlyQueryPF[Page, QueryArgs](
    matchEncode: PartialFunction[Any, QueryArgs],
    decode: PartialFunction[QueryArgs, Page],
    pattern: PathSegmentWithQueryParams[Unit, DummyError, QueryArgs, DummyError],
    basePath: String = ""
  ): Route[Page, QueryArgs] = {
    new Route(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = args => "/" + pattern.createUrlString(path = (), params = args),
      matchRelativeUrl = relativeUrl => pattern.matchRawUrl(relativeUrl).toOption.map(_.params),
      basePath = basePath
    )
  }

  /** Create a route with page data encoded in path segments and query params */
  def withQuery[Page: ClassTag, PathArgs, QueryArgs](
    encode: Page => PatternArgs[PathArgs, QueryArgs],
    decode: PatternArgs[PathArgs, QueryArgs] => Page,
    pattern: PathSegmentWithQueryParams[PathArgs, DummyError, QueryArgs, DummyError],
    basePath: String = ""
  ): Route[Page, PatternArgs[PathArgs, QueryArgs]] = {
    withQueryPF(
      matchEncode = matchPageByClassTag[Page, PatternArgs[PathArgs, QueryArgs]](encode),
      decode = { case args => decode(args) },
      pattern = pattern,
      basePath = basePath
    )
  }

  /** Create a partial route with page data encoded in path segments and query params
    *
    * In this version you can match only a subset of the Page type.
    * Make sure that the partiality of `matchEncode` mirrors that of
    * `decode`, otherwise you'll have a route that can match a page
    * but can not produce a url for that page (or vice versa).
    *
    * @param matchEncode - convert a Page into args. `Any` because it can be called with pages of other routes.
    */
  def withQueryPF[Page, PathArgs, QueryArgs](
    matchEncode: PartialFunction[Any, PatternArgs[PathArgs, QueryArgs]],
    decode: PartialFunction[PatternArgs[PathArgs, QueryArgs], Page],
    pattern: PathSegmentWithQueryParams[PathArgs, DummyError, QueryArgs, DummyError],
    basePath: String = ""
  ): Route[Page, PatternArgs[PathArgs, QueryArgs]] = {
    new Route(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = args => "/" + pattern.createUrlString(path = args.path, params = args.params),
      matchRelativeUrl = relativeUrl => pattern.matchRawUrl(relativeUrl).toOption,
      basePath = basePath
    )
  }

  /** Create a route with page data encoded in query params only */
  def onlyFragment[Page: ClassTag, FragmentArgs](
    encode: Page => FragmentArgs,
    decode: FragmentArgs => Page,
    pattern: PathQueryFragmentRepr[Unit, DummyError, Unit, DummyError, FragmentArgs, DummyError],
    basePath: String = ""
  ): Route[Page, FragmentArgs] = {
    onlyFragmentPF(
      matchEncode = matchPageByClassTag[Page, FragmentArgs](encode),
      decode = { case args => decode(args) },
      pattern = pattern,
      basePath = basePath
    )
  }

  /** Create a partial route with page data encoded in query params only
    *
    * In this version you can match only a subset of the Page type.
    * Make sure that the partiality of `matchEncode` mirrors that of
    * `decode`, otherwise you'll have a route that can match a page
    * but can not produce a url for that page (or vice versa).
    *
    * @param matchEncode - convert a Page into args. `Any` because it can be called with pages of other routes.
    */
  def onlyFragmentPF[Page, FragmentArgs](
    matchEncode: PartialFunction[Any, FragmentArgs],
    decode: PartialFunction[FragmentArgs, Page],
    pattern: PathQueryFragmentRepr[Unit, DummyError, Unit, DummyError, FragmentArgs, DummyError],
    basePath: String = ""
  ): Route[Page, FragmentArgs] = {
    new Route(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = args => "/" + pattern.fragmentOnly.createPart(args),
      matchRelativeUrl = relativeUrl => pattern.matchRawUrl(relativeUrl).toOption.map(_.fragment),
      basePath = basePath
    )
  }

  /** Create a route with page data encoded in path segments and fragment */
  def withFragment[Page: ClassTag, PathArgs, FragmentArgs](
    encode: Page => FragmentPatternArgs[PathArgs, Unit, FragmentArgs],
    decode: FragmentPatternArgs[PathArgs, Unit, FragmentArgs] => Page,
    pattern: PathQueryFragmentRepr[PathArgs, DummyError, Unit, DummyError, FragmentArgs, DummyError],
    basePath: String = ""
  ): Route[Page, FragmentPatternArgs[PathArgs, Unit, FragmentArgs]] = {
    withFragmentPF(
      matchEncode = matchPageByClassTag[Page, FragmentPatternArgs[PathArgs, Unit, FragmentArgs]](encode),
      decode = { case args => decode(args) },
      pattern = pattern,
      basePath = basePath
    )
  }

  /** Create a partial route with page data encoded in path segments and fragment
    *
    * In this version you can match only a subset of the Page type.
    * Make sure that the partiality of `matchEncode` mirrors that of
    * `decode`, otherwise you'll have a route that can match a page
    * but can not produce a url for that page (or vice versa).
    *
    * @param matchEncode - convert a Page into args. `Any` because it can be called with pages of other routes.
    */
  def withFragmentPF[Page, PathArgs, FragmentArgs](
    matchEncode: PartialFunction[Any, FragmentPatternArgs[PathArgs, Unit, FragmentArgs]],
    decode: PartialFunction[FragmentPatternArgs[PathArgs, Unit, FragmentArgs], Page],
    pattern: PathQueryFragmentRepr[PathArgs, DummyError, Unit, DummyError, FragmentArgs, DummyError],
    basePath: String = ""
  ): Route[Page, FragmentPatternArgs[PathArgs, Unit, FragmentArgs]] = {
    new Route(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = { args =>
        val patternArgs = PathQueryFragmentMatching(path = args.path, query = (), fragment = args.fragment)
        "/" + pattern.createPart(patternArgs)
      },
      matchRelativeUrl = relativeUrl => pattern.matchRawUrl(relativeUrl).toOption,
      basePath = basePath
    )
  }

  /** Create a route with page data encoded in query params and fragment */
  def onlyQueryAndFragment[Page: ClassTag, QueryArgs, FragmentArgs](
    encode: Page => FragmentPatternArgs[Unit, QueryArgs, FragmentArgs],
    decode: FragmentPatternArgs[Unit, QueryArgs, FragmentArgs] => Page,
    pattern: PathQueryFragmentRepr[Unit, DummyError, QueryArgs, DummyError, FragmentArgs, DummyError],
    basePath: String = ""
  ): Route[Page, FragmentPatternArgs[Unit, QueryArgs, FragmentArgs]] = {
    onlyQueryAndFragmentPF(
      matchEncode = matchPageByClassTag[Page, FragmentPatternArgs[Unit, QueryArgs, FragmentArgs]](encode),
      decode = { case args => decode(args) },
      pattern = pattern,
      basePath = basePath
    )
  }

  /** Create a partial route with page data encoded in query params and fragment
    *
    * In this version you can match only a subset of the Page type.
    * Make sure that the partiality of `matchEncode` mirrors that of
    * `decode`, otherwise you'll have a route that can match a page
    * but can not produce a url for that page (or vice versa).
    *
    * @param matchEncode - convert a Page into args. `Any` because it can be called with pages of other routes.
    */
  def onlyQueryAndFragmentPF[Page, QueryArgs, FragmentArgs](
    matchEncode: PartialFunction[Any, FragmentPatternArgs[Unit, QueryArgs, FragmentArgs]],
    decode: PartialFunction[FragmentPatternArgs[Unit, QueryArgs, FragmentArgs], Page],
    pattern: PathQueryFragmentRepr[Unit, DummyError, QueryArgs, DummyError, FragmentArgs, DummyError],
    basePath: String = ""
  ): Route[Page, FragmentPatternArgs[Unit, QueryArgs, FragmentArgs]] = {
    new Route(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = { args =>
        val patternArgs = PathQueryFragmentMatching(path = (), query = args.query, fragment = args.fragment)
        "/" + pattern.createPart(patternArgs)
      },
      matchRelativeUrl = relativeUrl => pattern.matchRawUrl(relativeUrl).toOption,
      basePath = basePath
    )
  }

  /** Create a route with page data encoded in path segments, query params and fragment */
  def withQueryAndFragment[Page: ClassTag, PathArgs, QueryArgs, FragmentArgs](
    encode: Page => FragmentPatternArgs[PathArgs, QueryArgs, FragmentArgs],
    decode: FragmentPatternArgs[PathArgs, QueryArgs, FragmentArgs] => Page,
    pattern: PathQueryFragmentRepr[PathArgs, DummyError, QueryArgs, DummyError, FragmentArgs, DummyError],
    basePath: String = ""
  ): Route[Page, FragmentPatternArgs[PathArgs, QueryArgs, FragmentArgs]] = {
    withQueryAndFragmentPF(
      matchEncode = matchPageByClassTag[Page, FragmentPatternArgs[PathArgs, QueryArgs, FragmentArgs]](encode),
      decode = { case args => decode(args) },
      pattern = pattern,
      basePath = basePath
    )
  }

  /** Create a partial route with page data encoded in path segments, query params and fragment
    *
    * In this version you can match only a subset of the Page type.
    * Make sure that the partiality of `matchEncode` mirrors that of
    * `decode`, otherwise you'll have a route that can match a page
    * but can not produce a url for that page (or vice versa).
    *
    * @param matchEncode - convert a Page into args. `Any` because it can be called with pages of other routes.
    */
  def withQueryAndFragmentPF[Page, PathArgs, QueryArgs, FragmentArgs](
    matchEncode: PartialFunction[Any, FragmentPatternArgs[PathArgs, QueryArgs, FragmentArgs]],
    decode: PartialFunction[FragmentPatternArgs[PathArgs, QueryArgs, FragmentArgs], Page],
    pattern: PathQueryFragmentRepr[PathArgs, DummyError, QueryArgs, DummyError, FragmentArgs, DummyError],
    basePath: String = ""
  ): Route[Page, FragmentPatternArgs[PathArgs, QueryArgs, FragmentArgs]] = {
    new Route(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = { args =>
        val patternArgs = PathQueryFragmentMatching(path = args.path, query = args.query, fragment = args.fragment)
        "/" + pattern.createPart(patternArgs)
      },
      matchRelativeUrl = relativeUrl => pattern.matchRawUrl(relativeUrl).toOption,
      basePath = basePath
    )
  }

  /** Create a route for a static page that does not encode any data in the URL */
  def static[Page](
    staticPage: Page,
    pattern: PathSegment[Unit, DummyError],
    basePath: String = ""
  ): Route[Page, Unit] = {
    new Route[Page, Unit](
      matchEncodePF = { case p if p == staticPage => () },
      decodePF = { case _ => staticPage },
      createRelativeUrl = args => "/" + pattern.createPath(args),
      matchRelativeUrl = relativeUrl => pattern.matchRawUrl(relativeUrl).toOption,
      basePath = basePath
    )
  }

  private def matchPageByClassTag[Page: ClassTag, Args](encode: Page => Args): PartialFunction[Any, Args] = {
    case page: Page => encode(page)
  }

}
