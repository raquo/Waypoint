package com.raquo.waypoint

import com.raquo.airstream.ownership.Owner
import com.raquo.laminar.api._
import com.raquo.waypoint.fixtures.AppPage.DocsSection._
import com.raquo.waypoint.fixtures.AppPage.{LibraryPage, _}
import com.raquo.waypoint.fixtures.{AppPage, UnitSpec}
import upickle.default._

import scala.util.Try

class BasePathSpec extends UnitSpec {

  private val testOwner = new Owner {}

  List(
    "http://example.com",
    "https://localhost:8080",
    "file://"
  ).foreach { origin =>
    runTest(origin, basePath = "")
    runTest(origin, basePath = Router.localFragmentBasePath)
    runTest(origin, basePath = "/#base/path")
    runTest(origin, basePath = "/path/to/index.html#")
    runTest(origin, basePath = "/path/to/index.html#base/path")
    runTest(origin, basePath = "/base/path")
    runTest(origin, basePath = "/path/to/index.html")
  }

  def runTest(origin: String, basePath: String): Unit = {

    describe(s"origin = `$origin`") {

      describe(s"basePath = `$basePath`") {

        val homeRoute: Route.Partial[HomePage.type, Unit] = Route.static(
          HomePage,
          pattern = root / endOfSegments,
          basePath = basePath
        )

        val homeRouteTotal: Route.Total[HomePage.type, Unit] = Route.staticTotal(
          HomePage,
          pattern = root / endOfSegments,
          basePath = basePath
        )

        val libraryRoute: Route.Total[LibraryPage, Int] = Route(
          encode = _.libraryId,
          decode = arg => LibraryPage(libraryId = arg),
          pattern = root / "app" / "library" / segment[Int] / endOfSegments,
          basePath = basePath
        )

        val textRoute: Route.Total[TextPage, String] = Route(
          encode = _.text,
          decode = arg => TextPage(text = arg),
          pattern = root / "app" / "test" / segment[String] / endOfSegments,
          basePath = basePath
        )

        val noteRoute: Route.Total[NotePage, (Int, Int)] = Route(
          encode = page => (page.libraryId, page.noteId),
          decode = args => NotePage(libraryId = args._1, noteId = args._2, scrollPosition = 0),
          pattern = root / "app" / "library" / segment[Int] / "note" / segment[Int] / endOfSegments,
          basePath = basePath
        )

        val searchRoute: Route.Total[SearchPage, String] = Route.onlyQuery(
          encode = page => page.query,
          decode = arg => SearchPage(arg),
          pattern = (root / "search" / endOfSegments) ? param[String]("query"),
          basePath = basePath
        )

        val bigLegalRoute: Route.Total[BigLegalPage, FragmentPatternArgs[String, Unit, String]] = Route.withFragment(
          encode = page => FragmentPatternArgs(path = page.page, (), fragment = page.section),
          decode = args => BigLegalPage(page = args.path, section = args.fragment),
          pattern = (root / "legal" / segment[String] / endOfSegments) withFragment fragment[String],
          basePath = basePath
        )

        // partial function match routes

        val bigNumRoute: Route[DocsPage, Int] = Route.applyPF(
          matchEncode = {
            case DocsPage(NumPage(i)) if i > 100 => i
          },
          decode = {
            case arg if arg > 100 => DocsPage(NumPage(arg))
          },
          pattern = root / "docs" / "num" / segment[Int] / endOfSegments,
          basePath = basePath
        )

        val negNumRoute: Route[DocsPage, Int] = Route.applyPF(
          matchEncode = {
            case DocsPage(NumPage(i)) if i < 0 => i
          },
          decode = {
            case arg if arg < 0 => DocsPage(NumPage(arg))
          },
          pattern = root / "docs" / "num" / segment[Int] / endOfSegments,
          basePath = basePath
        )

        val zeroNumRoute: Route[DocsPage, Int] = Route.applyPF(
          matchEncode = {
            case DocsPage(NumPage(i)) if i == 0 => i
          },
          decode = {
            case arg if arg == 0 => DocsPage(NumPage(arg))
          },
          pattern = root / "docs" / "zero" / segment[Int] / endOfSegments,
          basePath = basePath
        )

        val exampleRoute: Route[DocsPage, String] = Route.onlyQueryPF(
          matchEncode = {
            case DocsPage(ExamplePage(s)) => s
          },
          decode = {
            case args => DocsPage(ExamplePage(args))
          },
          pattern = (root / "docs" / "example" / endOfSegments) ? param[String]("name"),
          basePath = basePath
        )

        val componentRoute: Route[DocsPage, PatternArgs[String, String]] = Route.withQueryPF(
          matchEncode = {
            case DocsPage(ComponentPage(s, str)) => PatternArgs(s, str)
          },
          decode = {
            case args => DocsPage(ComponentPage(args.path, args.params))
          },
          pattern = (root / "docs" / "component" / segment[String] / endOfSegments) ? param[String]("group"),
          basePath = basePath
        )

        val router = new Router[AppPage](
          routes = List(
            libraryRoute,
            textRoute,
            noteRoute,
            homeRoute,
            homeRouteTotal,
            searchRoute,
            bigLegalRoute,
            bigNumRoute,
            negNumRoute,
            zeroNumRoute,
            exampleRoute,
            componentRoute
          ),
          getPageTitle = _.pageTitle,
          serializePage = page => write(page)(AppPage.rw),
          deserializePage = pageStr => read(pageStr)(AppPage.rw)
        )(
          popStateEvents = L.windowEvents(_.onPopState),
          owner = testOwner,
          origin = origin,
          initialUrl = origin + s"${basePath}/app/library/700"
        )

        it("segment routes - parse urls - match") {
          def testHomeRoute(homeRoute: Route[HomePage.type, Unit]) = {
            if (basePath.nonEmpty) {
              expectPageRelative(homeRoute, origin, s"$basePath", Some(HomePage))
            } else {
              // (basePath + url) must be a relative URL, so if basePath is empty, url can't also be empty, it must start with `/`
              expectPageRelativeFailure(homeRoute, origin, s"$basePath")
            }
            expectPageRelative(homeRoute, origin, s"$basePath/", Some(HomePage))
          }

          testHomeRoute(homeRoute)
          testHomeRoute(homeRouteTotal)

          expectPageRelative(libraryRoute, origin, s"$basePath/app/library/1234", Some(LibraryPage(libraryId = 1234)))
          expectPageRelative(libraryRoute, origin, s"$basePath/app/library/100?hello=world", Some(LibraryPage(libraryId = 100)))

          expectPageRelative(noteRoute, origin, s"$basePath/app/library/1234/note/4567", Some(NotePage(libraryId = 1234, noteId = 4567, scrollPosition = 0)))
          expectPageRelative(noteRoute, origin, s"$basePath/app/library/1234/note/4567?", Some(NotePage(libraryId = 1234, noteId = 4567, scrollPosition = 0)))

          expectPageRelative(bigNumRoute, origin, s"$basePath/docs/num/1234", Some(DocsPage(NumPage(1234))))
          expectPageRelative(bigNumRoute, origin, s"$basePath/docs/num/50", None)
          expectPageRelative(exampleRoute, origin, s"$basePath/docs/example?name=ala", Some(DocsPage(ExamplePage("ala"))))
          expectPageRelative(componentRoute, origin, s"$basePath/docs/component/kot?group=ala", Some(DocsPage(ComponentPage(name = "kot", group = "ala"))))
        }

        it("segment routes - parse urls - no match") {
          expectPageRelative(libraryRoute, origin, s"$basePath/app/library/1234h", None)
          expectPageRelative(noteRoute, origin, s"$basePath/app/library/1234/note/4567/yolo", None)
          expectPageRelative(noteRoute, origin, s"$basePath/app/library/1234/note/hey", None)
        }

        it("segment routes - parse urls - weird cases") {
          expectPageRelativeFailure(libraryRoute, origin, "app/library/1234")
          expectPageRelativeFailure(libraryRoute, origin, "//app/library/1234")
          expectPageRelativeFailure(libraryRoute, origin, "//evil.com/app/library/1234")
          expectPageRelativeFailure(libraryRoute, origin, "https://evil.com/app/library/1234")

          expectPageRelative(libraryRoute, origin, "/app/library/1234", if (basePath.isEmpty) Some(LibraryPage(1234)) else None)

          def testHomeRoute(homeRoute: Route[HomePage.type, Unit]) = {
            if (Utils.basePathHasEmptyFragment(basePath)) {
              val basePathWithoutFragment = Utils.basePathWithoutFragment(basePath)
              expectPageRelative(homeRoute, origin, s"$basePathWithoutFragment", Some(HomePage))
            }
          }
          testHomeRoute(homeRoute)
          testHomeRoute(homeRouteTotal)

          // @TODO[API] I mean... these are not absolute urls...
          expectPageAbsoluteFailure(libraryRoute, origin, "//app/library/1234")
          expectPageAbsoluteFailure(libraryRoute, origin, "//evil.com/app/library/1234")

          expectPageAbsolute(libraryRoute, origin, "https://evil.com/app/library/1234", None)

          expectPageRelative(textRoute, origin, s"$basePath/app/test/abc123", Some(TextPage("abc123")))
          expectPageRelative(textRoute, origin, s"$basePath/app/test/abc%3F%2F%3D%2B%3C%3E%20%D0%B9%D0%BE%D0%BB%D0%BE", Some(TextPage("abc?/=+<> йоло")))

          // Browser will always give us properly encoded URL. Same goes for router.urlForPage. We don't expect an invalid URL.
          //val thrown = intercept[URISyntaxException] {
          //  testRoute.pageForUrl("/app/test/abc?/=+<> йоло")
          //}
          //assert(thrown.getMessage === "Malformed URI in app/test/abc?/=+<> йоло at -1")
          // @TODO[API] ^^^ Figure out how we want this to behave and why. I think it's not throwing right now, just failing to match
        }

        it("query routes - parse urls") {
          expectPageRelative(searchRoute, origin, s"$basePath/search?query=hello", Some(SearchPage("hello")))
          expectPageRelative(searchRoute, origin, s"$basePath/search?query=", Some(SearchPage("")))
          expectPageRelative(searchRoute, origin, s"$basePath/othersearch?query=sugar", None)
        }

        it("fragment routes - parse urls") {
          expectPageRelative(bigLegalRoute, origin, s"$basePath/legal/privacy#thirdparty", Some(BigLegalPage(page = "privacy", section = "thirdparty")))
          expectPageRelative(bigLegalRoute, origin, s"$basePath/legal/privacy#", None)
          expectPageRelative(bigLegalRoute, origin, s"$basePath/legal/privacy", None)
        }

        it("segment routes - generate urls") {

          @inline def urlForPage(page: AppPage): Option[String] = Try(router.relativeUrlForPage(page)).toOption

          urlForPage(LibraryPage(700)) shouldBe Some(s"$basePath/app/library/700")
          urlForPage(NotePage(libraryId = 100, noteId = 200, scrollPosition = 5)) shouldBe Some(s"$basePath/app/library/100/note/200")
          urlForPage(TextPage("abc123")) shouldBe Some(s"$basePath/app/test/abc123")
          urlForPage(TextPage("abc?/=+<> йоло")) shouldBe Some(s"$basePath/app/test/abc%3F%2F%3D%2B%3C%3E%20%D0%B9%D0%BE%D0%BB%D0%BE")
        }

        it("query routes - generate urls") {
          @inline def urlForPage(page: AppPage): Option[String] = Try(router.relativeUrlForPage(page)).toOption

          urlForPage(SearchPage("hello")) shouldBe Some(s"$basePath/search?query=hello")
          urlForPage(SearchPage("hello world")) shouldBe Some(s"$basePath/search?query=hello%20world")
          urlForPage(SearchPage("")) shouldBe Some(s"$basePath/search?query=") // @TODO[API] We can't parse this URL tho
          urlForPage(SearchPage("")) shouldBe Some(s"$basePath/search?query=") // @TODO[API] We can't parse this URL tho
        }

        it("fragment routes - generate urls") {

          @inline def urlForPage(page: AppPage): Option[String] = Try(router.relativeUrlForPage(page)).toOption

          urlForPage(BigLegalPage(page = "privacy", section = "thirdparty")) shouldBe Some(s"$basePath/legal/privacy#thirdparty")
        }

        it("partial routes - generate urls") {
          @inline def urlForPage(page: AppPage): Option[String] = Try(router.relativeUrlForPage(page)).toOption

          urlForPage(DocsPage(NumPage(123))) shouldBe Some(s"$basePath/docs/num/123")
          urlForPage(DocsPage(NumPage(-123))) shouldBe Some(s"$basePath/docs/num/-123")
          urlForPage(DocsPage(NumPage(0))) shouldBe Some(s"$basePath/docs/zero/0")
          urlForPage(DocsPage(NumPage(50))) shouldBe None
        }
      }
    }
  }
}
