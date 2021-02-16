package com.raquo.waypoint

sealed class CollectPageRenderer[InPage, OutPage, +View] private[waypoint](
  collectPage: PartialFunction[InPage, OutPage],
  render: OutPage => View
) extends Renderer[InPage, View] {

  override def render(rawNextPage: InPage): Option[View] = {
    collectPage.andThen[Option[OutPage]](Some(_)).applyOrElse(rawNextPage, (_: InPage) => None).map(render)
  }

  override def discard(): Unit = ()
}
