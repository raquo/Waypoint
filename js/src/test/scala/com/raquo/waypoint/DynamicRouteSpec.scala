package com.raquo.waypoint

import com.raquo.waypoint.fixtures.{AppPage, UnitSpec}
import com.raquo.waypoint.fixtures.AppPage._
import com.raquo.airstream.ownership.Owner
import com.raquo.laminar.api.L
import com.raquo.waypoint.fixtures.AppPage.DocsSection._
import com.raquo.waypoint.fixtures.AppPage.LibraryPage
import org.scalatest.Assertion
import upickle.default._

import scala.util.{Success, Try}

class DynamicRouteSpec extends UnitSpec {

  private val testOwner = new Owner {}

  val origin = "http://example.com"

  val libraryRoute: Route[LibraryPage, Int] = Route(
    encode = _.libraryId,
    decode = arg => LibraryPage(libraryId = arg),
    pattern = root / "app" / "library" / segment[Int] / endOfSegments
  )

  val textRoute: Route[TextPage, String] = Route(
    encode = _.text,
    decode = arg => TextPage(text = arg),
    pattern = root / "app" / "test" / segment[String] / endOfSegments
  )

  val noteRoute: Route[NotePage, (Int, Int)] = Route(
    encode = page => (page.libraryId, page.noteId),
    decode = args => NotePage(libraryId = args._1, noteId = args._2, scrollPosition = 0),
    pattern = root / "app" / "library" / segment[Int] / "note" / segment[Int] / endOfSegments
  )

  val searchRoute: Route[SearchPage, String] = Route.onlyQuery(
    encode = page => page.query,
    decode = arg => SearchPage(arg),
    pattern = (root / "search" / endOfSegments) ? param[String]("query")
  )

  val workspaceSearchRoute: Route[WorkspaceSearchPage, PatternArgs[String, String]] = Route.withQuery(
    encode = page => PatternArgs(page.workspaceId, page.query),
    decode = args => WorkspaceSearchPage(workspaceId = args.path, query = args.params),
    pattern = (root / "workspace" / segment[String] / endOfSegments) ? param[String]("query")
  )

  // partial function match routes

  val bigNumRoute: Route[DocsPage, Int] = Route.applyPF(
    matchEncode = { case DocsPage(NumPage(i)) if i > 100 => i },
    decode = { case arg if arg > 100 => DocsPage(NumPage(arg)) },
    pattern = root / "docs" / "num" / segment[Int] / endOfSegments
  )

  val negNumRoute: Route[DocsPage, Int] = Route.applyPF(
    matchEncode = { case DocsPage(NumPage(i)) if i < 0 => i },
    decode = { case arg if arg < 0 => DocsPage(NumPage(arg)) },
    pattern = root / "docs" / "num" / segment[Int] / endOfSegments
  )

  val zeroNumRoute: Route[DocsPage, Int] = Route.applyPF(
    matchEncode = { case DocsPage(NumPage(i)) if i == 0 => i },
    decode = { case arg if arg == 0 => DocsPage(NumPage(arg)) },
    pattern = root / "docs" / "zero" / segment[Int] / endOfSegments
  )

  val exampleRoute: Route[DocsPage, String] = Route.onlyQueryPF(
    matchEncode = { case DocsPage(ExamplePage(s)) => s },
    decode = args => DocsPage(ExamplePage(args)),
    pattern = (root / "docs" / "example" / endOfSegments) ? param[String]("name")
  )

  val componentRoute: Route[DocsPage, PatternArgs[String, String]] = Route.withQueryPF(
    matchEncode = { case DocsPage(ComponentPage(s, str)) => PatternArgs(s, str) },
    decode = args => DocsPage(ComponentPage(args.path, args.params)),
    pattern = (root / "docs" / "component" / segment[String] / endOfSegments) ? param[String]("group")
  )

  val router = new Router[AppPage](
    initialUrl = origin + "/app/library/700",
    origin = origin,
    routes = List(
      libraryRoute,
      textRoute,
      noteRoute,
      searchRoute,
      workspaceSearchRoute,
      bigNumRoute,
      negNumRoute,
      zeroNumRoute,
      exampleRoute,
      componentRoute
    ),
    owner = testOwner,
    $popStateEvent = L.windowEvents.onPopState,
    getPageTitle = _.pageTitle,
    serializePage = page => write(page)(AppPage.rw),
    deserializePage = pageStr => read(pageStr)(AppPage.rw)
  )

  it ("segment routes - parse urls - match") {
    expectPageRelative(libraryRoute, origin, "/app/library/1234", Some(LibraryPage(libraryId = 1234)))
    expectPageRelative(libraryRoute, origin, "/app/library/1234/", Some(LibraryPage(libraryId = 1234))) // We allow trailing slashes
    expectPageRelative(libraryRoute, origin, "/app/library/-100500", Some(LibraryPage(libraryId = -100500)))
    expectPageRelative(libraryRoute, origin, "/app/library/-100500?", Some(LibraryPage(libraryId = -100500)))
    expectPageRelative(libraryRoute, origin, "/app/library/100?hello", Some(LibraryPage(libraryId = 100)))
    expectPageRelative(libraryRoute, origin, "/app/library/100?hello=world", Some(LibraryPage(libraryId = 100)))

    expectPageRelative(noteRoute, origin, "/app/library/1234/note/4567", Some(NotePage(libraryId = 1234, noteId = 4567, scrollPosition = 0)))
    expectPageRelative(noteRoute, origin, "/app/library/1234/note/1234", Some(NotePage(libraryId = 1234, noteId = 1234, scrollPosition = 0)))
    expectPageRelative(noteRoute, origin, "/app/library/1234/note/4567?", Some(NotePage(libraryId = 1234, noteId = 4567, scrollPosition = 0)))

    expectPageRelative(bigNumRoute, origin, "/docs/num/1234", Some(DocsPage(NumPage(1234))))
    expectPageRelative(bigNumRoute, origin, "/docs/num/50", None)
    expectPageRelative(bigNumRoute, origin, "/docs/num/-1234", None)
    expectPageRelative(negNumRoute, origin, "/docs/num/-1234", Some(DocsPage(NumPage(-1234))))
    expectPageRelative(negNumRoute, origin, "/docs/num/1234", None)
    expectPageRelative(zeroNumRoute, origin, "/docs/zero/1234", None)
    expectPageRelative(zeroNumRoute, origin, "/docs/zero/-1234", None)
    expectPageRelative(zeroNumRoute, origin, "/docs/zero/0", Some(DocsPage(NumPage(0))))
    expectPageRelative(exampleRoute, origin, "/docs/example?name=ala", Some(DocsPage(ExamplePage("ala"))))
    expectPageRelative(componentRoute, origin, "/docs/component/kot?group=ala", Some(DocsPage(ComponentPage(name = "kot", group = "ala"))))
  }

  it ("segment routes - parse urls - no match") {

    expectPageRelative(libraryRoute, origin, "/app/library/1234h", None)
    expectPageRelative(libraryRoute, origin, "/app/library/124.00", None)
    expectPageRelative(libraryRoute, origin, "/app/library/124.50", None)
    expectPageRelative(libraryRoute, origin, "/app/library/0e", None)
    expectPageRelative(libraryRoute, origin, "/app/library/1234/note/4567", None)
    expectPageRelative(libraryRoute, origin, "/app/library/1234/blah", None)
    expectPageRelative(libraryRoute, origin, "/app/library/1234/blah?", None)

    // @TODO[API] This should fail, but doesn't
    //expectPageRelative(libraryRoute, origin, "/app/library/1234//", None)

    expectPageRelative(noteRoute, origin, "/app/library/1234/note/4567/yolo", None)
    expectPageRelative(noteRoute, origin, "/app/library/1234/note/hey", None)
  }

  it ("segment routes - parse urls - weird cases") {
    expectPageRelativeFailure(libraryRoute, origin, "app/library/1234")
    expectPageRelativeFailure(libraryRoute, origin, "//app/library/1234")
    expectPageRelativeFailure(libraryRoute, origin, "//evil.com/app/library/1234")
    expectPageRelativeFailure(libraryRoute, origin, "https://evil.com/app/library/1234")

    // @TODO[API] I mean... these are not absolute urls...
    expectPageAbsoluteFailure(libraryRoute, origin, "//app/library/1234")
    expectPageAbsoluteFailure(libraryRoute, origin, "//evil.com/app/library/1234")

    expectPageAbsolute(libraryRoute, origin, "https://evil.com/app/library/1234", None)

    expectPageRelative(textRoute, origin, "/app/test/abc123", Some(TextPage("abc123")))
    expectPageRelative(textRoute, origin, "/app/test/abc%3F%2F%3D%2B%3C%3E%20%D0%B9%D0%BE%D0%BB%D0%BE", Some(TextPage("abc?/=+<> йоло")))

    // Browser will always give us properly encoded URL. Same goes for router.urlForPage. We don't expect an invalid URL.
    //val thrown = intercept[URISyntaxException] {
    //  testRoute.pageForUrl("/app/test/abc?/=+<> йоло")
    //}
    //assert(thrown.getMessage === "Malformed URI in app/test/abc?/=+<> йоло at -1")
    // @TODO[API] ^^^ Figure out how we want this to behave and why. I think it's not throwing right now, just failing to match
  }

  it ("query routes - parse urls") {
    expectPageRelative(searchRoute, origin, "/search?query=hello", Some(SearchPage("hello")))
    expectPageRelative(searchRoute, origin, "/search?query=hello&world=yes", Some(SearchPage("hello")))
    expectPageRelative(searchRoute, origin, "/search?world=yes&query=hello", Some(SearchPage("hello")))
    expectPageRelative(searchRoute, origin, "/search?query=hello%20world", Some(SearchPage("hello world"))) // @TODO[API] Not sure what we want to do with "+". Use %20 to encode space
    expectPageRelative(searchRoute, origin, "/search?query=hello+world", Some(SearchPage("hello+world"))) // @TODO[API] Not sure what we want to do with "+". Use %20 to encode space

    expectPageRelative(searchRoute, origin, "/search?query=one&query=two", Some(SearchPage("one"))) // @TODO[API] Not sure if desirable. Use `listParam` instead of `param` if you expect this.
    expectPageRelative(searchRoute, origin, "/search?query=", None) // @TODO[API] This should probably catch an empty string
    expectPageRelative(searchRoute, origin, "/search?query", None) // @TODO[API] Might want to create a matcher for value-less keys
    expectPageRelative(searchRoute, origin, "/othersearch?query=sugar", None)
  }

  it ("combined routes - parse urls") {
    expectPageRelative(workspaceSearchRoute, origin, "/workspace/123?query=hello", Some(WorkspaceSearchPage("123", "hello")))
    expectPageRelative(workspaceSearchRoute, origin, "/workspace/123?query=hello&world=yes", Some(WorkspaceSearchPage("123", "hello")))
    expectPageRelative(workspaceSearchRoute, origin, "/workspace/123?world=yes&query=hello", Some(WorkspaceSearchPage("123", "hello")))
    expectPageRelative(workspaceSearchRoute, origin, "/workspace/123?query=hello%20world", Some(WorkspaceSearchPage("123", "hello world"))) // @TODO[API] Not sure what we want to do with "+". Use %20 to encode space
    expectPageRelative(workspaceSearchRoute, origin, "/workspace/123?query=hello+world", Some(WorkspaceSearchPage("123", "hello+world"))) // @TODO[API] Not sure what we want to do with "+". Use %20 to encode space

    expectPageRelative(workspaceSearchRoute, origin, "/workspace/123/?query=hello", Some(WorkspaceSearchPage("123", "hello"))) // @TODO[API] Not sure if we want to catch this trailing slash
    expectPageRelative(workspaceSearchRoute, origin, "/workspace/123?query=one&query=two", Some(WorkspaceSearchPage("123", "one"))) // @TODO[API] Not sure if desirable. Use `listParam` instead of `param` if you expect this.
    expectPageRelative(workspaceSearchRoute, origin, "/workspace/123?query=", None) // @TODO[API] This should probably catch an empty string
    expectPageRelative(workspaceSearchRoute, origin, "/workspace/123?query", None) // @TODO[API] Might want to create a matcher for value-less keys
    expectPageRelative(workspaceSearchRoute, origin, "/workspace/123/search?query=sugar", None)

    expectPageRelative(workspaceSearchRoute, origin, "/workspace?query=sugar", None)
    expectPageRelative(workspaceSearchRoute, origin, "/workspace/?query=sugar", None)
  }

  it ("segment routes - generate urls") {

    @inline def urlForPage(page: AppPage): Option[String] = Try(router.relativeUrlForPage(page)).toOption

    urlForPage(LibraryPage(700)) shouldBe Some("/app/library/700")
    urlForPage(NotePage(libraryId = 100, noteId = 200, scrollPosition = 5)) shouldBe Some("/app/library/100/note/200")
    urlForPage(TextPage("abc123")) shouldBe Some("/app/test/abc123")
    urlForPage(TextPage("abc?/=+<> йоло")) shouldBe Some("/app/test/abc%3F%2F%3D%2B%3C%3E%20%D0%B9%D0%BE%D0%BB%D0%BE")
  }

  it ("query routes - generate urls") {
    @inline def urlForPage(page: AppPage): Option[String] = Try(router.relativeUrlForPage(page)).toOption

    urlForPage(SearchPage("hello")) shouldBe Some("/search?query=hello")
    urlForPage(SearchPage("hello world")) shouldBe Some("/search?query=hello%20world")
    urlForPage(SearchPage("")) shouldBe Some("/search?query=") // @TODO[API] We can't parse this URL tho
    urlForPage(SearchPage("")) shouldBe Some("/search?query=") // @TODO[API] We can't parse this URL tho
  }

  it ("combined routes - generate urls") {
    @inline def urlForPage(page: AppPage): Option[String] = Try(router.relativeUrlForPage(page)).toOption

    urlForPage(WorkspaceSearchPage("1234", "hello")) shouldBe Some("/workspace/1234?query=hello")
    urlForPage(WorkspaceSearchPage("1234", "hello world")) shouldBe Some("/workspace/1234?query=hello%20world")
    urlForPage(WorkspaceSearchPage("1234", "")) shouldBe Some("/workspace/1234?query=") // @TODO[API] We can't parse this URL tho
    urlForPage(WorkspaceSearchPage("1234", "")) shouldBe Some("/workspace/1234?query=") // @TODO[API] We can't parse this URL tho
    urlForPage(WorkspaceSearchPage("", "hello")) shouldBe Some("/workspace?query=hello") // @TODO[API] This is not correct, we can't parse this back into the same page
  }

  it ("partial routes - generate urls") {
    @inline def urlForPage(page: AppPage): Option[String] = Try(router.relativeUrlForPage(page)).toOption

    urlForPage(DocsPage(NumPage(123))) shouldBe Some("/docs/num/123")
    urlForPage(DocsPage(NumPage(-123))) shouldBe Some("/docs/num/-123")
    urlForPage(DocsPage(NumPage(0))) shouldBe Some("/docs/zero/0")
    urlForPage(DocsPage(NumPage(50))) shouldBe None
  }

  def expectPageAbsolute(route: Route[_, _], origin: String, url: String, expectedPage: Option[AppPage]): Assertion = {
    withClue("expectPageAbsolute: " + url + " ? " + expectedPage.toString + "\n") {
      Try(route.pageForAbsoluteUrl(origin, url)) shouldBe Success(expectedPage)
    }
  }

  def expectPageRelative(route: Route[_, _], origin: String, url: String, expectedPage: Option[AppPage]): Assertion = {
    withClue("expectPageRelative: " + url + " ? " + expectedPage.toString + "\n") {
      Try(route.pageForRelativeUrl(origin, url)) shouldBe Success(expectedPage)
    }
  }

  def expectPageAbsoluteFailure(route: Route[_, _], origin: String, url: String): Assertion = {
    withClue("expectPageAbsoluteFailure: " + url + " ? failure\n") {
      Try(route.pageForAbsoluteUrl(origin, url)).toOption shouldBe None
    }
  }

  def expectPageRelativeFailure(route: Route[_, _], origin: String, url: String): Assertion = {
    withClue("expectPageRelativeFailure: " + url + " ? failure\n") {
      Try(route.pageForRelativeUrl(origin, url)).toOption shouldBe None
    }
  }

  // @TODO[Test] >>>
  // - Make sure to test both matches and non-matches
  // - Make sure to test edge cases like external URLs and //schema URLs and empty URLs
  // - Test the other way too, converting pages into URLs
  // - Test Router as well as individual Route-s
}
