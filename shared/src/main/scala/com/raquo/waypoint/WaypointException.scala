package com.raquo.waypoint

/**
 * Prefixes the exception message to make it clear that this is a Waypoint exception.
 *
 * When you are in ScalaJS production build and the source maps are not loaded, it is quite hard
 * to tell where an exception comes from.
 */
class WaypointException(message: String) extends Exception(s"[Waypoint] $message")
