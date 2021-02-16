package com.raquo.waypoint.fixtures

import upickle.default._

sealed trait TestPage {
  val pageTitle: String = "Test"
}


sealed trait InnerPage

object InnerPage {
  case class InnerAPage(i: Int) extends InnerPage
  case class InnerBPage(s: String) extends InnerPage
  case class InnerCPage(s: String, str: String) extends InnerPage

  implicit val rwInnerAPage: ReadWriter[InnerAPage] = macroRW
  implicit val rwInnerBPage: ReadWriter[InnerBPage] = macroRW
  implicit val rwInnerCPage: ReadWriter[InnerCPage] = macroRW
  implicit val rw: ReadWriter[InnerPage] = macroRW
}
  

object TestPage {

  case class NotePage(libraryId: Int, noteId: Int, scrollPosition: Int) extends TestPage
  case class LibraryPage(libraryId: Int) extends TestPage
  case class WorkspacePage(workspaceId: String) extends TestPage
  case class SearchPage(query: String) extends TestPage
  case class WorkspaceSearchPage(workspaceId: String, query: String) extends TestPage
  case class TextPage(text: String) extends TestPage

  case class OuterPage(innerPage: InnerPage) extends TestPage
  case object LoginPage extends TestPage
  case object SignupPage extends TestPage
  case object HomePage extends TestPage

  implicit val OuterPageRW: ReadWriter[OuterPage] = macroRW
  implicit val NotePageRW: ReadWriter[NotePage] = macroRW
  implicit val LibraryPageRW: ReadWriter[LibraryPage] = macroRW
  implicit val WorkspacePageRW: ReadWriter[WorkspacePage] = macroRW
  implicit val SearchPageRW: ReadWriter[SearchPage] = macroRW
  implicit val WorkspaceSearchPageRW: ReadWriter[WorkspaceSearchPage] = macroRW
  implicit val TextPageRW: ReadWriter[TextPage] = macroRW

  implicit val rw: ReadWriter[TestPage] = macroRW
}
