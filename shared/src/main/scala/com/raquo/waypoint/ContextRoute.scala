package com.raquo.waypoint

import com.raquo.waypoint.Route
import urldsl.errors.DummyError
import urldsl.language.{PathSegment, PathSegmentWithQueryParams, QueryParameters}
import urldsl.vocabulary.UrlMatching

import scala.reflect.ClassTag

trait TPageWithContext[OuterPage, Page, Context] {
  def build(page: Page, context: Context): OuterPage
  def context(p: OuterPage): Context
  def page(p: OuterPage): Page
}

class ContextRoute[OuterPage: ClassTag, Page, Context: ClassTag, CArgs](
                                           encodeContext: Context => CArgs,
                                           decodeContext: CArgs => Context,
                                           queryParametersContext: QueryParameters[CArgs, DummyError]
                                         )(implicit ev: TPageWithContext[OuterPage, Page, Context]) {
  def static[P <: Page](staticPage: P,
                        pattern: PathSegment[Unit, DummyError],
                       ): Route[OuterPage, UrlMatching[Unit, CArgs]] = {
    val patternWithContext = pattern ? queryParametersContext
    val decodeC = (urlMatching: UrlMatching[Unit, CArgs]) =>
      ev.build(staticPage: Page, decodeContext(urlMatching.params))
    val matchPageC: PartialFunction[Any, UrlMatching[Unit, CArgs]] = {
      case p: OuterPage if ev.page(p) == staticPage => UrlMatching((), encodeContext(ev.context(p)))
    }

    Route.withQueryPF[OuterPage, Unit, CArgs](
      decode = decodeC,
      matchPF = matchPageC,
      patternWithContext
    )
  }

  def apply[P <: Page : ClassTag, PArgs](
                                         encode: P => PArgs,
                                         decode: PArgs => P,
                                         pattern: PathSegment[PArgs, DummyError]): Route[OuterPage, UrlMatching[PArgs, CArgs]] = {
    val patternWithContext = pattern ? queryParametersContext
    val decodeC = (urlMatching: UrlMatching[PArgs, CArgs]) =>
      ev.build(decode(urlMatching.path), decodeContext(urlMatching.params))
    val matchPageC: PartialFunction[Any, UrlMatching[PArgs, CArgs]] = {
      val pf: PartialFunction[(Page, Context), UrlMatching[PArgs, CArgs]] = {
        case (p: P, ctx: Context) => UrlMatching(encode(p), encodeContext(ctx))
      }
      pf.compose({ case outerPage: OuterPage => (ev.page(outerPage), ev.context(outerPage)) })
    }

    Route.withQueryPF[OuterPage, PArgs, CArgs](
      decode = decodeC,
      matchPF = matchPageC,
      patternWithContext
    )
  }

  def onlyQuery[P <: Page : ClassTag, QueryArgs](
                                                            encode: P => QueryArgs,
                                                            decode: QueryArgs => P,
																														patternPath: PathSegment[Unit, DummyError],
                                                            patternQuery: QueryParameters[QueryArgs, DummyError]
                                                          ): Route[OuterPage, (QueryArgs, CArgs)] = {
    val patternWithContext: PathSegmentWithQueryParams[Unit, DummyError, (QueryArgs, CArgs), DummyError] = patternPath ? (patternQuery & queryParametersContext)
    val decodeC = (args: (QueryArgs, CArgs)) =>
      ev.build(decode(args._1), decodeContext(args._2))
    val matchPageC: PartialFunction[Any, (QueryArgs, CArgs)] = {
      val pf: PartialFunction[(Page, Context), (QueryArgs, CArgs)] = {
        case (p: P, ctx: Context) => (encode(p), encodeContext(ctx))
      }
      pf.compose({ case outerPage: OuterPage => (ev.page(outerPage), ev.context(outerPage)) })
    }
    Route.onlyQueryPF[OuterPage, (QueryArgs, CArgs)](
      decode = decodeC,
      matchPF = matchPageC,
      patternWithContext
    )
  }

  def withQuery[P <: Page : ClassTag, PathArgs, QueryArgs](
                                                            encode: P => UrlMatching[PathArgs, QueryArgs],
                                                            decode: UrlMatching[PathArgs, QueryArgs] => P,
                                                            patternPath: PathSegment[PathArgs, DummyError],
                                                            patternQuery: QueryParameters[QueryArgs, DummyError]
                                                          ): Route[OuterPage, UrlMatching[PathArgs, (QueryArgs, CArgs)]] = {
    val patternWithContext: PathSegmentWithQueryParams[PathArgs, DummyError, (QueryArgs, CArgs), DummyError] = patternPath ? (patternQuery & queryParametersContext)
    val decodeC = (urlMatching: UrlMatching[PathArgs, (QueryArgs, CArgs)]) =>
      ev.build(decode(UrlMatching(urlMatching.path, urlMatching.params._1)), decodeContext(urlMatching.params._2))
    val matchPageC: PartialFunction[Any, UrlMatching[PathArgs, (QueryArgs, CArgs)]] = {
      val pf: PartialFunction[(Page, Context), UrlMatching[PathArgs, (QueryArgs, CArgs)]] = {
        case (p: P, ctx: Context) => 
				  val enc = encode(p)
					UrlMatching(enc.path, (enc.params, encodeContext(ctx)))
      }
      pf.compose({ case outerPage: OuterPage => (ev.page(outerPage), ev.context(outerPage)) })
    }
    Route.withQueryPF[OuterPage, PathArgs, (QueryArgs, CArgs)](
      decode = decodeC,
      matchPF = matchPageC,
      patternWithContext
    )
  }
}
