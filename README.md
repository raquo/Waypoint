# Waypoint

[![Join the chat at https://gitter.im/Laminar_/Lobby](https://badges.gitter.im/Laminar_/Lobby.svg)](https://gitter.im/Laminar_/Lobby)
![Maven Central](https://img.shields.io/maven-central/v/com.raquo/waypoint_sjs1_2.13.svg)

Waypoint is an efficient Router for [Laminar](https://github.com/raquo/Laminar) using [@sherpal](https://github.com/sherpal)'s [URL DSL](https://github.com/sherpal/url-dsl) library for URL matching and the browser's [History API](https://developer.mozilla.org/en-US/docs/Web/API/History) for managing URL transitions.

This is an early but functional version. While Laminar itself is quite polished, Waypoint might be a bit rough around the edges including the docs.

    "com.raquo" %%% "waypoint" % "0.2.0"   // Requires Airstream 0.10.0 & URL DSL 0.2.0

## Routing Basics

Different libraries use different language to describe the routing problem. The following are high level concepts to get us on the same page (ha!), not specific Scala types.

A **URL** is a well formed web address. The router deals only with URLs from the same origin (i.e. schema + domain + port) because the History API is unable to manage UI state across origins without a page reload.

A **View** is the content that should be rendered based on current **Page**. Typically it's a Laminar `ReactiveElement`.

A **Page** represents a specific UI State that a **Route** (and therefore a **Router**) can have. It is typically a case class with parameters matching a given Route, such as `UserPage(userId: Int)`, or simply `LoginPage`.

A **Pattern** is a construct that can extract a tuple of data from **URLs** and compile a URL from a tuple of data. For example, `root / "user" / segment[Int] / endOfSegments`. In Waypoint, patterns are provided by the [URL DSL](https://github.com/sherpal/url-dsl) library.

A **Route** is a class that defines how a class of **Page**s corresponds to a **Pattern**, and how to convert between the two. For example, `Route.static(LoginPage, root / "login" / endOfSegments)`

A **Router** is a class that provides methods to both set current **Page** and listen to changes of current **Page**. Because router manages the browser's History API, you typically instantiate only one router per `dom.window`.


## Rendering Views

So how do **Views** fit into all of the above? We need to render certain views based on the current page reported by the router. Here's our setup:

```scala
import com.raquo.laminar.api.L
import com.raquo.waypoint._
import upickle.default._
import org.scalajs.dom


sealed trait Page
case class UserPage(userId: Int) extends Page
case object LoginPage extends Page

implicit val UserPageRW: ReadWriter[UserPage] = macroRW
implicit val rw: ReadWriter[Page] = macroRW

val userRoute = Route(
  encode = userPage => userPage.userId,
  decode = arg => UserPage(userId = arg),
  pattern = root / "user" / segment[Int] / endOfSegments
)
val loginRoute = Route.static(LoginPage, root / "login" / endOfSegments)

val router = new Router[Page](
  initialUrl = dom.document.location.href, // must be a valid LoginPage or UserPage url
  origin = dom.document.location.origin.get,
  routes = List(userRoute, loginRoute),
  owner = L.unsafeWindowOwner, // this router will live as long as the window
  $popStateEvent = L.windowEvents.onPopState, // this being a param lets Waypoint avoid an explicit dependency on Laminar
  getPageTitle = _.toString, // mock page title (displayed in the browser tab next to favicon)
  serializePage = page => write(page)(rw), // serialize page data for storage in History API log
  deserializePage = pageStr => read(pageStr)(rw) // deserialize the above
)
```

Now, the naive approach to make this routing logic render some HTML is available in Laminar itself:

```scala
def renderPage(page: Page): Div = {
  page match {
    case UserPage(userId) => div("User page " + userId)
    case LoginPage => div("Login page.")
  }
}

val app: Div = div(
  h1("Routing App"),
  child <-- router.$currentPage.map(renderPage)
)

render(
  dom.document.getElementById("app-container"), // make sure you add such a container element to your HTML.
  app
)
```

This works, you just need to call `router.pushState(page)` or `router.replaceState(page)` somewhere to trigger the URL changes, and the view will update to show which page was selected.

However, as you know, this rendering is not efficient in Laminar by design. Every time `router.$currentPage` is updated, renderPage is called, creating a whole new element. Not a big deal at all in this toy example, but in the real world it would be re-creating your whole app's DOM tree on every URL change. That is simply unacceptable.

Waypoint provides a convenient but somewhat opinionated helper to solve this problem:

```scala
val splitter = SplitRender[Page, HtmlElement](router.$currentPage)
  .collectSignal[UserPage] { $userPage => renderUserPage($userPage) }
  .collectStatic(LoginPage) { div("Login page") }
 
def renderUserPage($userPage: Signal[UserPage]): Div = {
  div(
    "User page ",
    child.text <-- $userPage.map(user => user.userId)
  )
}
 
val app: Div = div(
  h1("Routing App"),
  child <-- splitter.$view
)
``` 

This is essentially a specialized version of the Airstream's [`split` operator](https://github.com/raquo/Laminar/blob/master/docs/Documentation.md#performant-children-rendering--split). The big idea is the same: provide a helper that lets you provide an efficient `Signal[A] => HtmlElement` instead of the inefficient `Signal[A] => Signal[HtmlElement]`. The difference is that the split operator groups together models by key, **which is a value**, whereas SplitRender groups together models by **subtype** and refines them to a subtype much like a `$currentPage.collect { case p: UserPage => p }` would if `collect` method existed on Signals.

You should read the linked `split` docs to understand the general splitting pattern, as I will only cover this specialized case very lightly.

In the previous, "naive" example, we were creating a new div element every time we navigated to a new user page, even if we're switching from one user page to a different user's page. But in that latter case, the DOM structure is already there, it would be much more efficient to just update the data in the DOM to a different user's values.

And this is exactly what `SplitRender.collectSignal` lets you do: it provides you a refined `Signal[UserPage]` instead of `Signal[Page]`, and it's trivial to build a single div that uses that `$userPage` signal like we do.


## Page Hierarchy

SplitRender's `collect` and `collectSignal` use Scala's [ClassTag](https://medium.com/@sinisalouc/overcoming-type-erasure-in-scala-8f2422070d20) to refine the general `Page` type into more specialized `UserPage`. You need to understand the limitations of ClassTag: it is only able to differentiate top level types, so in general your page types should not have type params, or if they do, you should know the limitations on matching those types with ClassTag.

To make the best use of SplitRender, you should make a base `Page` trait and have each of your pages as a distinct subclass. Static pages that carry no arguments can be `object`s, you can use SplitRender's `collectStatic` method to match them, it uses basic equality instead of ClassTag.

As your application grows you will likely have more than one level to your Page hierarchy. For example, you could have:

```scala
import com.raquo.waypoint._
 
sealed trait Page
sealed trait AppPage extends Page
sealed case class UserPage(userId: Int) extends AppPage
sealed case class NotePage(workspaceId: Int, noteId: Int) extends AppPage
case object LoginPage extends Page

// ... route and router definitions omitted for brevity ...

val pageSplitter = SplitRender[Page, HtmlElement](router.$currentPage)
  .collectSignal[AppPage] { $appPage => renderAppPage($appPage) }
  .collectStatic(LoginPage) { div("Login page") }
 
def renderAppPage($appPage: Signal[AppPage]): Div = {
  val appPageSplitter = SplitRender[AppPage, HtmlElement]($appPage)
    .collectSignal[UserPage] { $userPage => renderUserPage($userPage) }
    .collectSignal[NotePage] { $notePage => renderNotePage($notePage) }
  div(
    h2("App header"),
    child <-- appPageSplitter.$view
  )
}
 
def renderUserPage($userPage: Signal[UserPage]): Div = {
  div(
    "User page ",
    child.text <-- $userPage.map(user => user.userId)
  )
}
 
def renderNotePage($notePage: Signal[NotePage]): Div = {
  div(
    "Note page. workspaceid: ",
    child.text <-- $notePage.map(note => note.workspaceId),
    ", noteid: ",
    child.text <-- $notePage.map(note => note.noteId)
  )
}
 
val app: Div = div(
  h1("Routing App"),
  child <-- splitter.$view
)
```

One reason for nesting splitters like this could be to avoid re-rendering a common App header. In this case it's just a simple `h2("App header")` element but in real life it could be complex subtree with inputs that you don't want to re-create when you're switching pages. In this last setup, `h2("App header")` will not be re-created as long as you navigate within AppPage pages. Without such nesting you would need to re-create the header when navigating from a UserPage to a NotePage (or vice versa) even though both should have the same header.

SplitRender offers several methods: `collect`, `collectSignal` and `collectStatic`, use the ones that make more sense for your pages. Mixing them is fine of course.

Note: SplitRender is a construct made only of reactive variables. It does not know anything about routing, what the current URL is, etc. You give it a signal of `A` and a way to refine that into `B`, and you get a signal of `B` with `$view`.


## Using Waypoint

URL patterns and the matching functionality is provided by the [URL DSL](https://github.com/sherpal/url-dsl) library. All the methods that you need are defined on the `com.raquo.waypoint` package object, so just import that.

```scala
import com.raquo.waypoint._
 
// this matches all routes under /hello
root / "hello"

// this matches just /hello and /hello/ (plus whatever query params)
root / "hello" / endOfSegments

// this matches urls like /user/123 into Int
root / "user" / segment[Int] / endOfSegments

// this matches urls like /workspace/123/subsection/info into (Int, String)
root / "workspace" / segment[Int] / "subsection" / segment[String] / endOfSegments

// this matches urls like /workspace/123?query=hello&mode=1
(root / "workspace" / segment[Int] / endOfSegments) ? (param[String]("query") & param[Int]("mode"))
```

How to use such patterns to build routes:

```scala
import com.raquo.waypoint._
import urldsl.vocabulary.UrlMatching

Route.static(LoginPage, root / "login" / endOfSegments)

Route[UserPage, Int](
  encode = userPage => userPage.userId,
  decode = arg => UserPage(userId = arg),
  pattern = root / "user" / segment[Int] / endOfSegments
)

Route[WorkspacePage, (Int, String)](
  encode = workspacePage => (workspacePage.id, workspacePage.subsection),
  decode = args => WorkspacePage(id = args._1, subsection = args._2),
  pattern = root / "workspace" / segment[Int] / "subsection" / segment[Sting] / endOfSegments
)

Route.withQuery[WorkspaceSearchPage, Int, (String, Int)](
  encode = page => UrlMatching(path = page.id, params = (page.subsection, page.mode)),
  decode = args => WorkspacePage(id = args.path, subsection = args.params._1, mode = args.params._2),
  pattern = (root / "workspace" / segment[Int] / endOfSegments) ? (param[String]("query") & param[Int]("mode"))
)

Route.onlyQuery[SearchPage, String](
  encode = page => page.query,
  decode = arg => SearchPage(query = arg),
  pattern = (root / "search" / endOfSegments) ? (param[String]("query"))
)
```

Each of the above results in a `Route[Page, Args]` with the precise types. You can ask these routes to parse `argsFromPage`, get `relativeUrlForPage`, `pageForAbsoluteUrl`, or `pageForRelativeUrl`. But normally you would build a Router like we did in [Rendering Views](#rendering-views), passing it a list of all the routes you created.

Then you can change the document URL with `router.pushState(newPage)` and `router.replaceState(newPage)`, as well as get URLs for pages with `router.absoluteUrlForPage` and `router.relativeUrlForPage` (e.g. if you want to put that URL in a href attribute). You can even ask the router what Page, if any, matches a given url with `router.pageForAbsoluteUrl` or `router.pageForRelativeUrl`, or react to URL changes by listening to `router.$currentPage` signal (it's a StrictSignal so its current value is always available at `router.$currentPage.now()`).


## Recipes

#### Pages carrying state not reflected in the URL

Suppose you want to "remember" vertical scroll position on a certain page, so that when you navigate away from it and then come back, it's restored. Instead of maintaining complex global variables or cookies or whatever, just include this information in the Page.

Pages are serialized into History API state records, so when the user uses browser back button to come back to a page that he's already been to, it's not just the old URL that's restored, it's the whole Page state. On the contrary, if the user reloads the page or clicks a link that causes a page load instead of the History API transition, the Page state will be parsed from the URL, and since the URL does not include scroll position in our case, that will need to be the default scroll position of zero.

```scala
Route[NotePage, (Int, Int)](
  encode = page => (page.libraryId, page.noteId), // scroll position is not written into the URL args
  decode = args => NotePage(libraryId = args._1, noteId = args._2, scrollPosition = 0), // default scroll position that will be inferred from the URL
  pattern = root / "app" / "library" / segment[Int] / "note" / segment[Int] / endOfSegments
)
```

Remember that you need to actually scroll to the scroll position when loading the page. You can probably just do this when switching from a different type of page to the type of page that remembers its scroll position. Treat scroll position as a sort of "uncontrolled input" in React terms, if that makes sense.

Lastly, normally you fire `router.pushState` to update the current page. But in case of updating current scroll position, you should instead fire `router.replaceState`, otherwise you will litter your browser history with a bunch of useless scrolling records.


## Waypoint Without Laminar

Perhaps ironically, Waypoint does not actually depend on Laminar, only on Airstream.

All you need to use Waypoint without Laminar is provide a stream of `dom.OnPopState` events, which is very easy, just copy `DomEventStream` from Laminar.


## Author

Nikita Gazarov – [@raquo](https://twitter.com/raquo)


## License & Credits

Waypoint is provided under the [MIT license](https://github.com/raquo/Waypoint/blob/master/LICENSE.md).

Waypoint uses [URL DSL](https://github.com/sherpal/url-dsl) for URL matching, also provided under the MIT license.
