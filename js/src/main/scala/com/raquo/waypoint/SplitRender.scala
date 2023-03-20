package com.raquo.waypoint

// #TODO Test all this

import com.raquo.airstream.core.Signal
import scala.reflect.ClassTag

case class SplitRender[Page, View](
  pageSignal: Signal[Page],
  renderers: List[Renderer[Page, View]] = Nil
) {

  /** Add a standard renderer for Page type */
  def collect[P <: Page : ClassTag](render: P => View): SplitRender[Page, View] = {
    val renderer = new CollectPageRenderer[Page, View]({ case p: P => render(p) })
    copy(renderers = renderers :+ renderer)
  }

  /** Just a convenience method for static pages. `page` is expected to be an `object`. */
  def collectStatic[P <: Page](page: P)(view: => View): SplitRender[Page, View] = {
    collectStaticPF { case `page` => view }
  }

  /** Similar to `collectStatic`, but evaluates `view` only once, when this method is called. */
  def collectStaticStrict[P <: Page](page: P)(view: View): SplitRender[Page, View] = {
    collectStaticPF { case `page` => view }
  }

  // @TODO[Naming] This is really the PF equivalent of collectStatic, right...?
  def collectStaticPF(render: PartialFunction[Page, View]): SplitRender[Page, View] = {
    val renderer = new CollectPageRenderer[Page, View](render)
    copy(renderers = renderers :+ renderer)
  }

  /** This renderer is efficient. It creates only a single element (instance of Out)
    * that takes Signal[P] as a parameter instead of creating a Signal of elements.
    */
  def collectSignal[P <: Page : ClassTag](render: Signal[P] => View): SplitRender[Page, View] = {
    collectSignalPF({ case p: P => p })(render)
  }

  def collectSignalPF[P](pf: PartialFunction[Page, P])(render: Signal[P] => View): SplitRender[Page, View] = {
    val renderer = new CollectPageSignalRenderer[Page, P, View](pf, render)
    copy(renderers = renderers :+ renderer)
  }

  /** Signal of output elements. Put this in your DOM with:
    * `child <-- SplitRender(page).collect(...).collectSignal(...).signal`
    */
  def signal: Signal[View] = {
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
