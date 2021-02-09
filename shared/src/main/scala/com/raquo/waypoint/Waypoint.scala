package com.raquo.waypoint

import urldsl.errors.{FragmentMatchingError, ParamMatchingError, PathMatchingError}
import urldsl.language._

class Waypoint[PathErr, QueryErr, FragErr](
  override protected val pathError: PathMatchingError[PathErr],
  override protected val queryError: ParamMatchingError[QueryErr],
  override protected val fragmentError: FragmentMatchingError[FragErr],
) extends PathSegmentImpl[PathErr]
  with QueryParametersImpl[QueryErr]
  with FragmentImpl[FragErr]
