package com.raquo.waypoint

object Utils {

  // @TODO[Security] This is rather ad-hoc. Review later.
  @inline private[waypoint] def isRelative(url: String): Boolean = {
    url.startsWith("/") && !url.startsWith("//")
  }

  @inline private[waypoint] def absoluteUrlMatchesOrigin(origin: String, url: String): Boolean = {
    url.startsWith(origin + "/")
  }
}
