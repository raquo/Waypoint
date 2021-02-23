package com.raquo

import urldsl.errors._
import urldsl.vocabulary.UrlMatching

/** This is the public API. Import com.raquo.waypoint or com.raquo.waypoint._ */
package object waypoint extends Waypoint(
  DummyError.dummyErrorIsPathMatchingError,
  DummyError.dummyErrorIsParamMatchingError,
  DummyError.dummyErrorIsFragmentMatchingError
) {

  /** A bundle of path args and query param args */
  type PatternArgs[PathArgs, QueryArgs] = UrlMatching[PathArgs, QueryArgs]

  @inline def PatternArgs: UrlMatching.type = UrlMatching
}
