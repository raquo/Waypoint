package com.raquo.waypoint

import urldsl.errors.DummyError
import urldsl.language.{PathQueryFragmentRepr, PathSegment, PathSegmentWithQueryParams}
import urldsl.vocabulary.PathQueryFragmentMatching

import scala.reflect.ClassTag

/** Base class for all Routes.
  *
  * @param basePath      - This string is inserted after `origin`. If not empty, must start with `/`.
  * @tparam Page         - Types of pages that this Route is capable of matching.
  *                        Note: the Route might match only a subset of pages of this type.
  * @tparam Args         - Type of data saved in the URL for pages matched by this route
  *                        Note: the Route might match only a subset of args of this type.
  */
sealed abstract class Route[Page, Args] private[waypoint] (
  basePath: String
) {
  if (basePath.nonEmpty && !basePath.startsWith("/")) {
    throw new WaypointException(s"Route's basePath, when not empty, must start with `/`. basePath is `$basePath` for this route.")
  }

  protected val matchEncodePF: PartialFunction[Any, Args]

  protected val decodePF: PartialFunction[Args, Page]

  protected val createRelativeUrl: Args => String

  protected val matchAbsoluteUrl: String => Option[Args]

  /** @return None if the [[Route]] is partial and the given object does not match. */
  def argsFromPage(page: Page): Option[Args] = encodeOpt(page)

  /** @return None if the [[Route]] is partial and the given object does not match. */
  def relativeUrlForPage[P >: Page](page: P): Option[String] =
    encodeOpt(page).map(relativeUrlForArgs)

  def relativeUrlForArgs(args: Args): String =
    basePath + createRelativeUrl(args)

  /** @param origin - typically dom.window.location.origin.get e.g. "http://localhost:8080"
    *
    * @throws Exception when url is not absolute or is malformed, or in case of https://github.com/raquo/Waypoint#firefox-and-file-urls
    */
  def pageForAbsoluteUrl(origin: String, url: String): Option[Page] = {
    if (origin == "null") {
      throw new WaypointException("pageForAbsoluteUrl was provided with a \"null\" origin. See https://github.com/raquo/Waypoint#firefox-and-file-urls")
    }
    // @TODO[API] We evaluate the page unconditionally, as that will consistently throw in case of malformed URL
    val maybePage = matchAbsoluteUrl(url).flatMap(decodeOpt)
    if (Utils.absoluteUrlMatchesOrigin(origin, url)) {
      maybePage
    } else {
      None
    }
  }

  /** @param origin - typically dom.window.location.origin.get e.g. "http://localhost:8080"
    *
    * @throws Exception when url is not relative, or in case of https://github.com/raquo/Waypoint#firefox-and-file-urls
    */
  def pageForRelativeUrl(origin: String, url: String): Option[Page] = {
    if (origin == "null") {
      throw new WaypointException("pageForRelativeUrl was provided with a \"null\" origin. See https://github.com/raquo/Waypoint#firefox-and-file-urls")
    }
    if (!Utils.isRelative(url)) {
      throw new WaypointException(s"Relative URL must be relative to the origin, i.e. it must start with /, whereas `$url` was given.")
    }
    // basepath ends in #, and the url contains nothing except the basepath without said `#` symbol.
    lazy val trailingHashSpecialCase = {
      Utils.basePathHasEmptyFragment(basePath) &&
      url == Utils.basePathWithoutFragment(basePath)
    }

    // Carefullly remove the basepath from the absolute URL
    val absoluteUrlOpt = if (url.startsWith(basePath)) {
      // Basically we want to remove the base path here, as if it doesn't exist.
      val urlWithoutBasePath = url.substring(basePath.length)
      if (urlWithoutBasePath.isEmpty || urlWithoutBasePath.startsWith("/") || urlWithoutBasePath.startsWith("?")) {
        Some(origin + urlWithoutBasePath)
      } else {
        // if urlWithoutBasePath does not include a separator, we need to include it ourselves.
        // This could happen if basepath ends in #, and the hash url does not start with '/'.
        Some(origin + "/" + urlWithoutBasePath)
      }
    } else if (trailingHashSpecialCase) {
      Some(origin)
    } else {
      None
    }

    absoluteUrlOpt.flatMap(matchAbsoluteUrl).flatMap(decodeOpt)
  }

  private def encodeOpt(page: Any): Option[Args] = {
    matchEncodePF
      .andThen(Some(_))
      .applyOrElse(page, (_: Any) => None)
  }

  private def decodeOpt(args: Args): Option[Page] = {
    decodePF
      .andThen[Option[Page]](Some(_))
      .applyOrElse(args, (_: Args) => None)
  }
}
object Route {
  /**
   * A partial route.
   *
   * Encoding and decoding are partial functions. Their partial-ness should be symmetrical,
   * otherwise you'll end up with a route that can parse a URL into a Page but can't encode
   * the page into the same URL (or vice versa)
   *
   * @param matchEncodePF - Match Any to Page, and if successful, encode it into Args
   * @param decodePF      - Decode Args into Page, if args are valid
   * @tparam Page         - Types of pages that this Route is capable of matching.
   *                        Note: the Route might match only a subset of pages of this type.
   * @tparam Args         - Type of data saved in the URL for pages matched by this route
   *                        Note: the Route might match only a subset of args of this type.
   */
  class Partial[Page, Args] private[waypoint] (
    override protected val matchEncodePF: PartialFunction[Any, Args],
    override protected val decodePF: PartialFunction[Args, Page],
    override protected val createRelativeUrl: Args => String,
    override protected val matchAbsoluteUrl: String => Option[Args],
    basePath: String
  ) extends Route[Page, Args](
    basePath = basePath
  )

  /**
   * A total route is a route that can always translate a [[Page]] into a [[Args]] and vice versa.
   */
  class Total[Page: ClassTag, Args] private[waypoint] (
    encode: Page => Args,
    decode: Args => Page,
    override protected val createRelativeUrl: Args => String,
    override protected val matchAbsoluteUrl: String => Option[Args],
    basePath: String
  ) extends Route[Page, Args](
    basePath = basePath
  ) {
    override protected val matchEncodePF: PartialFunction[Any, Args] = { case p: Page => encode(p) }

    override protected val decodePF: PartialFunction[Args, Page] = { case args => decode(args) }

    def argsFromPageTotal(page: Page): Args =
      encode(page)

    def relativeUrlForPage(page: Page): String =
      relativeUrlForArgs(encode(page))
  }

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
  ): Total[Page, Args] = {
    new Total(
      encode, decode,
      createRelativeUrl = args => "/" + pattern.createPath(args),
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption,
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
  ): Partial[Page, Args] = {
    new Partial(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = args => "/" + pattern.createPath(args),
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption,
      basePath = basePath
    )
  }

  /** Create a route with page data encoded in query params only */
  def onlyQuery[Page: ClassTag, QueryArgs](
    encode: Page => QueryArgs,
    decode: QueryArgs => Page,
    pattern: PathSegmentWithQueryParams[Unit, DummyError, QueryArgs, DummyError],
    basePath: String = ""
  ): Total[Page, QueryArgs] = {
    new Total(
      encode, decode,
      createRelativeUrl = args => "/" + pattern.createUrlString(path = (), params = args),
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption.map(_.params),
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
  ): Partial[Page, QueryArgs] = {
    new Partial(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = args => "/" + pattern.createUrlString(path = (), params = args),
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption.map(_.params),
      basePath = basePath
    )
  }

  /** Create a route with page data encoded in path segments and query params */
  def withQuery[Page: ClassTag, PathArgs, QueryArgs](
    encode: Page => PatternArgs[PathArgs, QueryArgs],
    decode: PatternArgs[PathArgs, QueryArgs] => Page,
    pattern: PathSegmentWithQueryParams[PathArgs, DummyError, QueryArgs, DummyError],
    basePath: String = ""
  ): Total[Page, PatternArgs[PathArgs, QueryArgs]] = {
    new Total(
      encode, decode,
      createRelativeUrl = args => "/" + pattern.createUrlString(path = args.path, params = args.params),
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption,
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
  ): Partial[Page, PatternArgs[PathArgs, QueryArgs]] = {
    new Partial(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = args => "/" + pattern.createUrlString(path = args.path, params = args.params),
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption,
      basePath = basePath
    )
  }

  /** Create a route with page data encoded in query params only */
  def onlyFragment[Page: ClassTag, FragmentArgs](
    encode: Page => FragmentArgs,
    decode: FragmentArgs => Page,
    pattern: PathQueryFragmentRepr[Unit, DummyError, Unit, DummyError, FragmentArgs, DummyError],
    basePath: String = ""
  ): Total[Page, FragmentArgs] = {
    new Total(
      encode, decode,
      createRelativeUrl = args => "/" + pattern.fragmentOnly.createPart(args),
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption.map(_.fragment),
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
  ): Partial[Page, FragmentArgs] = {
    new Partial(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = args => "/" + pattern.fragmentOnly.createPart(args),
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption.map(_.fragment),
      basePath = basePath
    )
  }

  /** Create a route with page data encoded in path segments and fragment */
  def withFragment[Page: ClassTag, PathArgs, FragmentArgs](
    encode: Page => FragmentPatternArgs[PathArgs, Unit, FragmentArgs],
    decode: FragmentPatternArgs[PathArgs, Unit, FragmentArgs] => Page,
    pattern: PathQueryFragmentRepr[PathArgs, DummyError, Unit, DummyError, FragmentArgs, DummyError],
    basePath: String = ""
  ): Total[Page, FragmentPatternArgs[PathArgs, Unit, FragmentArgs]] = {
    new Total(
      encode, decode,
      createRelativeUrl = { args =>
        val patternArgs = PathQueryFragmentMatching(path = args.path, query = (), fragment = args.fragment)
        "/" + pattern.createPart(patternArgs)
      },
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption,
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
  ): Partial[Page, FragmentPatternArgs[PathArgs, Unit, FragmentArgs]] = {
    new Partial(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = { args =>
        val patternArgs = PathQueryFragmentMatching(path = args.path, query = (), fragment = args.fragment)
        "/" + pattern.createPart(patternArgs)
      },
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption,
      basePath = basePath
    )
  }

  /** Create a route with page data encoded in query params and fragment */
  def onlyQueryAndFragment[Page: ClassTag, QueryArgs, FragmentArgs](
    encode: Page => FragmentPatternArgs[Unit, QueryArgs, FragmentArgs],
    decode: FragmentPatternArgs[Unit, QueryArgs, FragmentArgs] => Page,
    pattern: PathQueryFragmentRepr[Unit, DummyError, QueryArgs, DummyError, FragmentArgs, DummyError],
    basePath: String = ""
  ): Total[Page, FragmentPatternArgs[Unit, QueryArgs, FragmentArgs]] = {
    new Total(
      encode, decode,
      createRelativeUrl = { args =>
        val patternArgs = PathQueryFragmentMatching(path = (), query = args.query, fragment = args.fragment)
        "/" + pattern.createPart(patternArgs)
      },
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption,
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
  ): Partial[Page, FragmentPatternArgs[Unit, QueryArgs, FragmentArgs]] = {
    new Partial(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = { args =>
        val patternArgs = PathQueryFragmentMatching(path = (), query = args.query, fragment = args.fragment)
        "/" + pattern.createPart(patternArgs)
      },
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption,
      basePath = basePath
    )
  }

  /** Create a route with page data encoded in path segments, query params and fragment */
  def withQueryAndFragment[Page: ClassTag, PathArgs, QueryArgs, FragmentArgs](
    encode: Page => FragmentPatternArgs[PathArgs, QueryArgs, FragmentArgs],
    decode: FragmentPatternArgs[PathArgs, QueryArgs, FragmentArgs] => Page,
    pattern: PathQueryFragmentRepr[PathArgs, DummyError, QueryArgs, DummyError, FragmentArgs, DummyError],
    basePath: String = ""
  ): Total[Page, FragmentPatternArgs[PathArgs, QueryArgs, FragmentArgs]] = {
    new Total(
      encode, decode,
      createRelativeUrl = { args =>
        val patternArgs = PathQueryFragmentMatching(path = args.path, query = args.query, fragment = args.fragment)
        "/" + pattern.createPart(patternArgs)
      },
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption,
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
  ): Partial[Page, FragmentPatternArgs[PathArgs, QueryArgs, FragmentArgs]] = {
    new Partial(
      matchEncodePF = matchEncode,
      decodePF = decode,
      createRelativeUrl = { args =>
        val patternArgs = PathQueryFragmentMatching(path = args.path, query = args.query, fragment = args.fragment)
        "/" + pattern.createPart(patternArgs)
      },
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption,
      basePath = basePath
    )
  }

  /** Create a route for a static page that does not encode any data in the URL.
    *
    * This version only allows using singleton types, like `object Foo`.
    * See [[staticPartial]] for a more relaxed version.
    *
    * @see [[ValueOf]] - evidence that `Page` is a singleton type
    */
  def static[Page: ValueOf: ClassTag](
    staticPage: Page,
    pattern: PathSegment[Unit, DummyError],
    basePath: String = ""
  ): Total[Page, Unit] = {
    new Total[Page, Unit](
      encode = _ => (),
      decode = _ => staticPage,
      createRelativeUrl = args => "/" + pattern.createPath(args),
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption,
      basePath = basePath
    )
  }

  /** Create a route for a static page that does not encode any data in the URL.
    *
    * This returns a [[Partial]] route. If you want a [[Total]] route,
    * use [[static]] instead. They behave the same, but the total version
    * offers a couple extra methods, but it requires that the `staticPage` is
    * a singleton (e.g. `object HomePage`).
    */
  def staticPartial[Page](
    staticPage: Page,
    pattern: PathSegment[Unit, DummyError],
    basePath: String = ""
  ): Partial[Page, Unit] = {
    new Partial[Page, Unit](
      matchEncodePF = { case p if p == staticPage => () },
      decodePF = { case _ => staticPage },
      createRelativeUrl = args => "/" + pattern.createPath(args),
      matchAbsoluteUrl = absoluteUrl => pattern.matchRawUrl(absoluteUrl).toOption,
      basePath = basePath
    )
  }
}
