package com.raquo

import urldsl.errors._

/** This is the public API. Import com.raquo.waypoint or com.raquo.waypoint._ */
package object waypoint extends Waypoint(
  DummyError.dummyErrorIsPathMatchingError,
  DummyError.dummyErrorIsParamMatchingError,
  DummyError.dummyErrorIsFragmentMatchingError
) {

  /** You can import waypoint.simple._ to use URL-DSl Simple* errors,
    * but make sure you DO NOT import waypoint._ in this case, otherwise
    * you will get ambiguous implicits.
    */
  lazy val simple: Waypoint[SimplePathMatchingError, SimpleParamMatchingError, SimpleFragmentMatchingError] = new Waypoint(
    SimplePathMatchingError.pathMatchingError,
    SimpleParamMatchingError.itIsParamMatchingError,
    SimpleFragmentMatchingError.itIsFragmentMatchingError
  )
}
