package com.raquo.waypoint

trait Renderer[-Page, +View] {

  /** Called for next page. Return Some(el) to be rendered if the page belongs to this renderer, or None otherwise. */
  def render(rawNextPage: Page): Option[View]

  /** Called when the next page belongs to a different renderer */
  def discard(): Unit
}
