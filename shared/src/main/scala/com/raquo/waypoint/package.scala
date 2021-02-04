package com.raquo

import urldsl.errors.{DummyError, PathMatchingError}
import urldsl.language.{PathSegment, QueryParameters}
import urldsl.vocabulary.{FromString, Printer}

/** This is the public API. Import com.raquo.waypoint or com.raquo.waypoint._ */
package object waypoint {

  private implicit val error: PathMatchingError[DummyError] = DummyError.dummyErrorIsPathMatchingError

  // type StaticRoute[Page: ClassTag] = Route[Page, Unit]

  val root: PathSegment[Unit, DummyError] =
    PathSegment.root

  implicit final def unaryPathSegment[T](
        t: T
    )(
        implicit fromString: FromString[T, DummyError],
        printer: Printer[T],
    ): PathSegment[Unit, DummyError] = PathSegment.unaryPathSegment(t)

  def segment[Arg](implicit fromString: FromString[Arg, DummyError], printer: Printer[Arg]): PathSegment[Arg, DummyError] =
    PathSegment.segment[Arg, DummyError]

  val remainingSegments: PathSegment[List[String], DummyError] =
    PathSegment.remainingSegments

  val endOfSegments: PathSegment[Unit, DummyError] =
    PathSegment.endOfSegments

  //val noMatchSegment: PathSegment[Unit, DummyError] =
  //  PathSegment.noMatch[DummyError]

  //def oneOf[T](ts: T*)(implicit fromString: FromString[T, DummyError], printer: Printer[T]): PathSegment[Unit, DummyError] =
  //  PathSegment.oneOf(ts.headOption.getOrElse(throw new Exception("waypoint.oneOf requires a non-empty list")), ts.tail: _*)

  def param[Arg](
    paramName: String
  )(implicit
    fromString: FromString[Arg, DummyError],
    printer: Printer[Arg]
  ): QueryParameters[Arg, DummyError] =
    QueryParameters.param(paramName)

  def listParam[Arg](
    paramName: String
  )(implicit
    fromString: FromString[Arg, DummyError],
    printer: Printer[Arg]
  ): QueryParameters[List[Arg], DummyError] =
    QueryParameters.listParam(paramName)

  //val emptyParams: QueryParameters[Unit, DummyError] =
  //  QueryParameters.empty[DummyError]

  // @TODO[Security] This is rather ad-hoc. Review later.
  @inline private[waypoint] def isRelative(url: String): Boolean = {
    url.startsWith("/") && !url.startsWith("//")
  }

  // @TODO move?
  @inline private[waypoint] def absoluteUrlMatchesOrigin(origin: String, url: String): Boolean = {
    url.startsWith(origin + "/")
  }
}
