package com.raquo.waypoint.fixtures

import upickle.default._

sealed trait TestPage {
  val pageTitle: String = "Test"
}

object TestPage {

  case class NotePage(libraryId: Int, noteId: Int, scrollPosition: Int) extends TestPage
  case class LibraryPage(libraryId: Int) extends TestPage
  case class WorkspacePage(workspaceId: String) extends TestPage
  case class SearchPage(query: String) extends TestPage
  case class WorkspaceSearchPage(workspaceId: String, query: String) extends TestPage
  case class TextPage(text: String) extends TestPage
  case object LoginPage extends TestPage
  case object SignupPage extends TestPage
  case object HomePage extends TestPage


  implicit val NotePageRW: ReadWriter[NotePage] = macroRW
  implicit val LibraryPageRW: ReadWriter[LibraryPage] = macroRW
  implicit val WorkspacePageRW: ReadWriter[WorkspacePage] = macroRW
  implicit val SearchPageRW: ReadWriter[SearchPage] = macroRW
  implicit val WorkspaceSearchPageRW: ReadWriter[WorkspaceSearchPage] = macroRW
  implicit val TextPageRW: ReadWriter[TextPage] = macroRW

  implicit val rw: ReadWriter[TestPage] = macroRW
}
