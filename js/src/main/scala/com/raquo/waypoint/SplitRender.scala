package com.raquo.waypoint

import com.raquo.airstream.core.Signal

import scala.reflect.ClassTag

case class SplitRender[Page, View](
  pageSignal: Signal[Page],
  renderers: List[Renderer[Page, View]] = Nil
) {

  /** Add a standard renderer for Page type */
  def collect[P <: Page : ClassTag](render: P => View): SplitRender[Page, View] = {
    val renderer = new CollectPageRenderer[Page, P, View](
      { case p: P => p },
      render
    )
    copy(renderers = renderers :+ renderer)
  }

  /** Just a convenience method for static pages. `page` is expected to be an `object`. */
  def collectStatic[P <: Page : ClassTag](page: P)(view: => View): SplitRender[Page, View] = {
    collect[P](_ => view)
  }

  /** This renderer is efficient. It creates only a single element (instance of Out)
    * that takes Signal[P] as a parameter instead of creating a Signal of elements.
    */
  def collectSignal[P <: Page : ClassTag](render: Signal[P] => View): SplitRender[Page, View] = {
    val renderer = new CollectPageSignalRenderer[Page, P, View](
      { case p: P => p },
      render
    )
    copy(renderers = renderers :+ renderer)
  }

  /** Signal of output elements. Put this in your DOM with:
    * `child <-- SplitRender($page).collect(...).collectSignal(...).signal`
    */
  def $view: Signal[View] = {
    var maybeCurrentRenderer: Option[Renderer[Page, View]] = None

    pageSignal.map { nextPage =>
      val iterator = renderers.iterator
      var nextView: Option[View] = None

      // Here's how this works.
      // - We try to render the next page using every rendered in the router
      // - The renderer that recognizes this page will return Some(view)
      // - pageSignal will return this view, BUT
      //   - If this was the same renderer as previous page,
      //     this element might actually be the same element as before
      //     because the renderer internals update the view internals
      //     instead of re-creating the element
      //   - If this was a new renderer, we need to notify the previous renderer
      //     that it's no longer active by calling discard() on it

      while (nextView.isEmpty && iterator.hasNext) {
        val renderer = iterator.next()
        val newView = renderer.render(nextPage)
        if (newView.isDefined) {
          maybeCurrentRenderer.filter(_ != renderer).foreach(_.discard())
          maybeCurrentRenderer = Some(renderer)
          nextView = newView
        }
      }

      nextView.getOrElse(throw new Exception("Page did not match any renderer: " + nextPage.toString))
    }
  }
}
