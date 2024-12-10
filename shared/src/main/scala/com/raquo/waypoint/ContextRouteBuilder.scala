package com.raquo.waypoint

import urldsl.errors.DummyError
import urldsl.language.{PathSegment, PathSegmentWithQueryParams, QueryParameters}

import scala.reflect.ClassTag

// @TODO[API] Add PF methods here similar to Route$, I guess

/** A builder for routes with context.
  *
  * Context, in this context (heheh), is a type that encodes a set of query params
  * that is shared between the routes created by this builder.
  *
  * For example, your Laminar web app might have a big documentation section with
  * many routes, but where every route is expected to have a "lang" query param to
  * indicate the selected language and a "version" param to indicate the product
  * version (unrealistic doc standards, I know).
  *
  * So, you could add these two params to every page type that needs them and to
  * every route for those page types, but that could be annoying. So instead, you
  * could create a ContextRouteBuilder which will let you specify the necessary
  * types / conversions / patterns only once.
  *
  * See ContextRouteBuilderSpec for a concrete example.
  *
  * @tparam Bundle - The type of pages (including the context part) handled by the routes produced by this builder.
  *                  A bundle consists of a route-specific Page, and a Context shared by all routes by this builder.
  *                  In the simplest case, imagine `case class Bundle(p: Page, ctx: Context)`
  *
  * @tparam Page   - The base type for the route-specific parts of the Bundle
  * @tparam Ctx    - The part of the Bundle shared by all routes made by this builder,
  *                  In the simplest case, imagine `case class SharedParams(lang: Option[String], version: Option[String])`
  */
class ContextRouteBuilder[Bundle: ClassTag, Page, Ctx: ClassTag, CtxArgs](
  encodeContext: Ctx => CtxArgs,
  decodeContext: CtxArgs => Ctx,
  contextPattern: QueryParameters[CtxArgs, DummyError],
  pageFromBundle: Bundle => Page,
  contextFromBundle: Bundle => Ctx,
  bundleFromPageWithContext: (Page, Ctx) => Bundle
) {

  /** Create a context route for an otherwise static page.
    *
    * That is, while the Page itself does not encode any data in the URL,
    * the bundle does encode context args in the query.
    */
  def static(
    staticPage: Page,
    pattern: PathSegment[Unit, DummyError],
  ): Route[Bundle, PatternArgs[Unit, CtxArgs]] = Route.withQueryPF(
    decode = { case bundleArgs =>
      bundleFromPageWithContext(staticPage, decodeContext(bundleArgs.params))
    },
    matchEncode = {
      case bundle: Bundle if pageFromBundle(bundle) == staticPage =>
        PatternArgs((), encodeContext(contextFromBundle(bundle)))
    },
    pattern = pattern ? contextPattern
  )

  /** Create a context route for pages that are encoded in path segments only */
  def apply[P <: Page: ClassTag, PageArgs](
    encode: P => PageArgs,
    decode: PageArgs => P,
    pattern: PathSegment[PageArgs, DummyError]
  ): Route[Bundle, PatternArgs[PageArgs, CtxArgs]] = Route.withQueryPF(
    matchEncode = {
      val matchBundle: PartialFunction[Any, (Page, Ctx)] = {
        case bundle: Bundle => (pageFromBundle(bundle), contextFromBundle(bundle))
      }
      val matchPageEncodeBundle: PartialFunction[(Page, Ctx), PatternArgs[PageArgs, CtxArgs]] = {
        case (p: P, ctx: Ctx) =>
          val pageArgs = encode(p)
          val ctxArgs = encodeContext(ctx)
          PatternArgs(path = pageArgs, params = ctxArgs)
      }
      Utils.andThenPF(matchBundle, matchPageEncodeBundle)
    },
    decode = { case bundleArgs =>
      val page = decode(bundleArgs.path)
      val context = decodeContext(bundleArgs.params)
      bundleFromPageWithContext(page, context)
    },
    pattern = pattern ? contextPattern
  )

  /** Create a context route for pages that are encoded in query params only */
  def onlyQuery[P <: Page: ClassTag, PageArgs](
    encode: P => PageArgs,
    decode: PageArgs => P,
    pattern: PathSegmentWithQueryParams[Unit, DummyError, PageArgs, DummyError],
  ): Route[Bundle, (PageArgs, CtxArgs)] = Route.onlyQueryPF(
    matchEncode = {
      val matchBundle: PartialFunction[Any, (Page, Ctx)] = {
        case bundle: Bundle => (pageFromBundle(bundle), contextFromBundle(bundle))
      }
      val matchPageEncodeBundle: PartialFunction[(Page, Ctx), (PageArgs, CtxArgs)] = {
        case (page: P, ctx: Ctx) =>
          val pageArgs = encode(page)
          val ctxArgs = encodeContext(ctx)
          (pageArgs, ctxArgs)
      }
      Utils.andThenPF(matchBundle, matchPageEncodeBundle)
    },
    decode = {
      case (pageArgs, ctxArgs) =>
        val page = decode(pageArgs)
        val ctx = decodeContext(ctxArgs)
        bundleFromPageWithContext(page, ctx)
    },
    pattern = pattern & contextPattern
  )

  /** Create a context route for page that are encoded in both path segments in query params */
  def withQuery[P <: Page: ClassTag, PathArgs, QueryArgs](
    encode: P => PatternArgs[PathArgs, QueryArgs],
    decode: PatternArgs[PathArgs, QueryArgs] => P,
    pattern: PathSegmentWithQueryParams[PathArgs, DummyError, QueryArgs, DummyError]
  ): Route[Bundle, PatternArgs[PathArgs, (QueryArgs, CtxArgs)]] = Route.withQueryPF(
    matchEncode = {
      val matchBundle: PartialFunction[Any, (Page, Ctx)] = {
        case bundle: Bundle => (pageFromBundle(bundle), contextFromBundle(bundle))
      }
      val matchPageEncodePage: PartialFunction[(Page, Ctx), PatternArgs[PathArgs, (QueryArgs, CtxArgs)]] = {
        case (page: P, ctx: Ctx) =>
          val pageArgs = encode(page)
          val ctxArgs = encodeContext(ctx)
          PatternArgs(
            path = pageArgs.path,
            params = (pageArgs.params, ctxArgs)
          )
      }
      Utils.andThenPF(matchBundle, matchPageEncodePage)
    },
    decode = { case bundleArgs =>
      val pagePathArgs = bundleArgs.path
      val (pageQueryArgs, contextArgs) = bundleArgs.params
      val page = decode(PatternArgs(pagePathArgs, pageQueryArgs))
      val context = decodeContext(contextArgs)
      bundleFromPageWithContext(page, context)
    },
    pattern = pattern & contextPattern
  )
}
