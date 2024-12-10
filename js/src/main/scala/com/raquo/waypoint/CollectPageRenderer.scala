package com.raquo.waypoint

/** @param matchRender - match the page and render it.
  *                      This partial function should only be defined for pages that this rendered can render.
  */
sealed class CollectPageRenderer[Page, +View] private[waypoint] (
  matchRender: PartialFunction[Page, View]
) extends Renderer[Page, View] {

  override def render(rawNextPage: Page): Option[View] = {
    matchRender
      .andThen[Option[View]](Some(_))
      .applyOrElse(rawNextPage, (_: Page) => None)
  }

  override def discard(): Unit = ()
}
