package com.raquo.waypoint

import com.raquo.laminar.api.L._

/** The job of this renderer is to provide Signal[OutPage] => View
  * instead of the more obvious but less efficient Signal[OutPage] => Signal[View]
  *
  * It is sort of like a specialized version of Airstream .split
  */
sealed class CollectPageSignalRenderer[InPage, OutPage <: InPage, +View] private[waypoint](
  collectPage: PartialFunction[InPage, OutPage],
  render: Signal[OutPage] => View
) extends Renderer[InPage, View] {

  // @TODO[Integrity] Because we don'ot have collect on Signal, this emits events in a different Transaction than the original $page Signal.
  //  When we have Signal.collect, we should re-implement this in its terms.

  private[this] var signalVar: Var[OutPage] = null

  private[this] var currentView: Option[View] = None

  override def render(rawNextPage: InPage): Option[View] = {
    collectPage.andThen[Option[OutPage]](Some(_)).applyOrElse(rawNextPage, (_: InPage) => None).map { nextPage =>
      if (signalVar == null) {
        signalVar = Var(nextPage)
        currentView = Some(render(signalVar.signal))
      } else {
        signalVar.set(nextPage)
      }
      currentView.get
    }
  }

  override def discard(): Unit = {
    signalVar = null
    currentView = None
  }
}
