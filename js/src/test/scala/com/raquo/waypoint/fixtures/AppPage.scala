package com.raquo.waypoint.fixtures

import upickle.default._

sealed trait AppPage {
  val pageTitle: String = "Test"
}

object AppPage {

  case class NotePage(libraryId: Int, noteId: Int, scrollPosition: Int) extends AppPage
  case class LibraryPage(libraryId: Int) extends AppPage
  case class WorkspacePage(workspaceId: String) extends AppPage
  case class SearchPage(query: String) extends AppPage
  case class WorkspaceSearchPage(workspaceId: String, query: String) extends AppPage
  case class TextPage(text: String) extends AppPage

  case object LoginPage extends AppPage
  case object SignupPage extends AppPage
  case object HomePage extends AppPage

  sealed trait DocsSection

  object DocsSection {

    case class NumPage(num: Int) extends DocsSection
    case class ExamplePage(name: String) extends DocsSection
    case class ComponentPage(name: String, group: String) extends DocsSection

    implicit val rwInnerAPage: ReadWriter[NumPage] = macroRW
    implicit val rwInnerBPage: ReadWriter[ExamplePage] = macroRW
    implicit val rwInnerCPage: ReadWriter[ComponentPage] = macroRW
    implicit val rw: ReadWriter[DocsSection] = macroRW
  }

  case class DocsPage(innerPage: DocsSection) extends AppPage

  implicit val OuterPageRW: ReadWriter[DocsPage] = macroRW
  implicit val NotePageRW: ReadWriter[NotePage] = macroRW
  implicit val LibraryPageRW: ReadWriter[LibraryPage] = macroRW
  implicit val WorkspacePageRW: ReadWriter[WorkspacePage] = macroRW
  implicit val SearchPageRW: ReadWriter[SearchPage] = macroRW
  implicit val WorkspaceSearchPageRW: ReadWriter[WorkspaceSearchPage] = macroRW
  implicit val TextPageRW: ReadWriter[TextPage] = macroRW

  implicit val rw: ReadWriter[AppPage] = macroRW
}
