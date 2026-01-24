# Waypoint

[![Build status](https://github.com/raquo/Waypoint/actions/workflows/test.yml/badge.svg)](https://github.com/raquo/Waypoint/actions/workflows/test.yml)
[![Chat on https://discord.gg/JTrUxhq7sj](https://img.shields.io/badge/chat-on%20discord-7289da.svg)](https://discord.gg/JTrUxhq7sj)
[![Maven Central](https://img.shields.io/maven-central/v/com.raquo/waypoint_sjs1_3.svg)](https://search.maven.org/artifact/com.raquo/waypoint_sjs1_3)

Waypoint is an efficient Router for [Laminar](https://github.com/raquo/Laminar) using [@sherpal](https://github.com/sherpal)'s [URL DSL](https://github.com/sherpal/url-dsl) library for URL matching and the browser's [History API](https://developer.mozilla.org/en-US/docs/Web/API/History) for managing URL transitions.

Unlike Laminar itself, Waypoint is quite opinionated, focusing on a specific approach to representing routes that I think works great. [Frontroute](https://github.com/tulz-app/frontroute) is another routing alternative.

Waypoint can be used with other Scala.js libraries too, not just Laminar. More on that at the bottom of this document.

Waypoint docs are not as exhaustive as Laminar's, but we have examples, and Waypoint is very, very small, so this shouldn't be a big deal. Just make sure you understand how the browser's History API works.

    "com.raquo" %%% "waypoint" % "10.0.0-M1"   // Depends on Laminar 17.2.0 & URL DSL 0.6.2



## Routing Basics

Different libraries use different language to describe the routing problem. The following are high level concepts to get us on the same page (ha!), not specific Scala types.

A **URL** is a well formed web address. The router deals only with URLs from the same origin (i.e. schema + domain + port) because the History API is unable to manage UI state across origins without a page reload.

A **View** is the content that should be rendered based on current **Page**. Typically it's a Laminar `ReactiveElement`.

A **Page** represents a specific UI State that a **Route** (and therefore a **Router**) can have. It is typically a case class with parameters matching a given Route, such as `UserPage(userId: Int)`, or simply `LoginPage`.

A **Pattern** is a construct that can extract a tuple of data from **URLs** and compile a URL from a tuple of data. For example, `root / "user" / segment[Int]`. In Waypoint, patterns are provided by the [URL DSL](https://github.com/sherpal/url-dsl) library.

A **Route** is a class that defines how a class of **Page**s corresponds to a **Pattern**, and how to convert between the two. For example, `Route.static(LoginPage, root / "login")`. Routes may be partial, i.e. match only a subset of the Pages in the class.

A **Router** is a class that provides methods to a) set the current **Page** and b) listen to changes in current **Page**. Because a router manages the browser's History API, you typically instantiate only one router per `dom.window`.



## Rendering Views

So how do **Views** fit into all of the above? We need to render certain views based on the current page reported by the router. Here's our setup:

```scala
import com.raquo.waypoint._
import upickle.default._
import org.scalajs.dom


sealed abstract class Page(val title: String)
case class UserPage(userId: Int) extends Page("User")
case object LoginPage extends Page("Login")

implicit val UserPageRW: ReadWriter[UserPage] = macroRW
implicit val rw: ReadWriter[Page] = macroRW

val userRoute = Route(
  encode = userPage => userPage.userId,
  decode = arg => UserPage(userId = arg),
  pattern = root / "user" / segment[Int]
)
val loginRoute = Route.static(LoginPage, root / "login")

object router extends Router[Page](
  routes = List(userRoute, loginRoute),
  getPageTitle = page => page.title, // (document title, displayed in the browser history, and in the tab next to favicon)
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
  child <-- router.currentPageSignal.map(renderPage)
)

render(
  dom.document.getElementById("app-container"), // make sure you add such a container element to your HTML.
  app
)
```

This works, you just need to call `router.pushState(page)` or `router.replaceState(page)` somewhere to trigger the URL changes, and the view will update to show which page was selected.

However, as you know, this rendering is not efficient in Laminar by design. Every time `router.currentPageSignal` is updated, `renderPage` is called, creating a whole new element. Not a big deal at all in this toy example, but in the real world it would be re-creating your whole app's DOM tree on every URL change. That is simply unacceptable.

You can improve the efficiency of this using Airstream's [`split` operator](https://laminar.dev/documentation#performant-children-rendering--split), but this will prove cumbersome if your app has many different Page types. Waypoint provides a convenient but somewhat opinionated helper to solve this problem:

```scala
val splitter = SplitRender[Page, HtmlElement](router.currentPageSignal)
  .collectSignal[UserPage] { userPageSignal => renderUserPage(userPageSignal) }
  .collectStatic(LoginPage) { div("Login page") }
 
def renderUserPage(userPageSignal: Signal[UserPage]): Div = {
  div(
    "User page ",
    child.text <-- userPageSignal.map(user => user.userId)
  )
}
 
val app: Div = div(
  h1("Routing App"),
  child <-- splitter.signal
)
``` 

This is essentially a specialized version of the Airstream's [`split` operator](https://laminar.dev/documentation#performant-children-rendering--split). The big idea is the same: provide a helper that lets you provide an efficient `Signal[A] => HtmlElement` instead of the inefficient `Signal[A] => Signal[HtmlElement]`. The difference is that the split operator groups together models by key, **which is a value**, whereas SplitRender groups together models by **subtype** and refines them to a subtype much like a `currentPageSignal.collect { case p: UserPage => p }` would if `collect` method existed on Signals.

You should read the linked `split` docs to understand the general splitting pattern, as I will only cover this specialized case very lightly.

In the previous, "naive" example, we were creating a new div element every time we navigated to a new user page, even if we're switching from one user page to a different user's page. But in that latter case, the DOM structure is already there, it would be much more efficient to just update the data in the DOM to a different user's values.

And this is exactly what `SplitRender.collectSignal` lets you do: it provides you a refined `Signal[UserPage]` instead of `Signal[Page]`, and it's trivial to build a single div that uses that `userPageSignal` like we do.



## Page Hierarchy

SplitRender's `collect` and `collectSignal` use Scala's [ClassTag](https://medium.com/@sinisalouc/overcoming-type-erasure-in-scala-8f2422070d20) to refine the general `Page` type into more specialized `UserPage`. You need to understand the limitations of ClassTag: it is only able to differentiate top level types, so in general your page types should not have type params, or if they do, you should know the limitations on matching those types with ClassTag.

To make the best use of SplitRender, you should make a base `Page` trait and have each of your pages as a distinct subclass. Static pages that carry no arguments can be `object`s, you can use SplitRender's `collectStatic` method to match them, it uses standard `==` value equality instead of `ClassTag`.

As your application grows you will likely have more than one level to your Page hierarchy. For example, you could have:

```scala
import com.raquo.waypoint._
 
sealed trait Page
sealed trait AppPage extends Page
sealed case class UserPage(userId: Int) extends AppPage
sealed case class NotePage(workspaceId: Int, noteId: Int) extends AppPage
case object LoginPage extends Page

// ... route and router definitions omitted for brevity ...

val pageSplitter = SplitRender[Page, HtmlElement](router.currentPageSignal)
  .collectSignal[AppPage] { appPageSignal => renderAppPage(appPageSignal) }
  .collectStatic(LoginPage) { div("Login page") }
 
def renderAppPage(appPageSignal: Signal[AppPage]): Div = {
  val appPageSplitter = SplitRender[AppPage, HtmlElement](appPageSignal)
    .collectSignal[UserPage] { userPageSignal => renderUserPage(userPageSignal) }
    .collectSignal[NotePage] { notePageSignal => renderNotePage(notePageSignal) }
  div(
    h2("App header"),
    child <-- appPageSplitter.signal
  )
}
 
def renderUserPage(userPageSignal: Signal[UserPage]): Div = {
  div(
    "User page ",
    child.text <-- userPageSignal.map(user => user.userId)
  )
}
 
def renderNotePage(notePageSignal: Signal[NotePage]): Div = {
  div(
    "Note page. workspaceid: ",
    child.text <-- notePageSignal.map(note => note.workspaceId),
    ", noteid: ",
    child.text <-- notePageSignal.map(note => note.noteId)
  )
}
 
val app: Div = div(
  h1("Routing App"),
  child <-- pageSplitter.signal
)
```

One reason for nesting splitters like this could be to avoid re-rendering a common App header. In this case it's just a simple `h2("App header")` element but in real life it could be complex subtree with inputs that you don't want to re-create when you're switching pages. In this last setup, `h2("App header")` will not be re-created as long as you navigate within AppPage pages. Without such nesting you would need to re-create the header when navigating from a UserPage to a NotePage (or vice versa) even though both should have the same header.

SplitRender offers several methods: `collect`, `collectSignal` and `collectStatic`, use the ones that make more sense for your pages. Mixing them is fine of course.

Note: SplitRender is a construct made only of reactive variables. It does not know anything about routing, what the current URL is, etc. You give it a signal of `A` and a way to refine that into `B`, and you get a signal of `B` with `signal`.



## Using Waypoint

URL patterns and the matching functionality are provided by the [URL DSL](https://github.com/sherpal/url-dsl) library. All the methods that you need are defined on the `com.raquo.waypoint` package object, so just import that:

```scala
import com.raquo.waypoint._

// Migration note: as of Waypoint v10.0.0-M2, `/ endOfSegments` is implied, and you need to use `ignoreRemainingSegments` when you want a looser match (see below).

// this matches just /hello and /hello/ (plus whatever query params)
root / "hello"

// this matches /hello as well as any route "under" it, e.g. /hello/world
root / "hello" / ignoreRemainingSegments

// this matches urls like /user/123 into Int
root / "user" / segment[Int]

// this matches urls like /workspace/123/subsection/info into (Int, String)
root / "workspace" / segment[Int] / "subsection" / segment[String]

// this matches urls like:
//   /workspace/123?query=hello
//   /workspace/123?query=hello&mode=1 
// note the `.?` invocation on the `mode` param: this means it's optional, modelled as Option[Int]
(root / "workspace" / segment[Int]) ? (param[String]("query") & param[Int]("mode").?)
```

How to use such patterns to build routes:

```scala
import com.raquo.waypoint._
import urldsl.vocabulary.UrlMatching

Route.static(LoginPage, root / "login")

Route[UserPage, Int](
  encode = userPage => userPage.userId,
  decode = arg => UserPage(userId = arg),
  pattern = root / "user" / segment[Int]
)

Route[WorkspacePage, (Int, String)](
  encode = workspacePage => (workspacePage.id, workspacePage.subsection),
  decode = args => WorkspacePage(id = args._1, subsection = args._2),
  pattern = root / "workspace" / segment[Int] / "subsection" / segment[Sting]
)

Route.withQuery[WorkspaceSearchPage, Int, (String, Int)](
  encode = page => UrlMatching(path = page.id, params = (page.subsection, page.mode)),
  decode = args => WorkspacePage(id = args.path, subsection = args.params._1, mode = args.params._2),
  pattern = (root / "workspace" / segment[Int]) ? (param[String]("query") & param[Int]("mode"))
)

Route.onlyQuery[SearchPage, String](
  encode = page => page.query,
  decode = arg => SearchPage(query = arg),
  pattern = (root / "search") ? (param[String]("query"))
)

Route.withFragment[BigLegalPage, String, String](
  encode = page => FragmentPatternArgs(path = page.page, query = (), fragment = page.section),
  decode = args => BigLegalPage(page = args.path, section = args.fragment),
  pattern = (root / "legal" / segment[String]) withFragment fragment[String]
)
```

Each of the above results in a `Route[Page, Args]` with the precise types. You can ask these routes to parse `argsFromPage`, get `relativeUrlForPage`, `pageForAbsoluteUrl`, or `pageForRelativeUrl`. But normally you would build a Router like we did in [Rendering Views](#rendering-views), passing it a list of all the routes you created.

Then you can change the document URL with `router.pushState(newPage)` and `router.replaceState(newPage)`, as well as get URLs for pages with `router.absoluteUrlForPage` and `router.relativeUrlForPage` (e.g. if you want to put that URL in a href attribute). You can even ask the router what Page, if any, matches a given url with `router.pageForAbsoluteUrl` or `router.pageForRelativeUrl`, or react to URL changes by listening to `router.currentPageSignal` (it's a StrictSignal so its current value is always available at `router.currentPageSignal.now()`).



## Routing Extras


### Updating Document Title

Whenever the current page changes, Waypoint automatically updates the page title using the `getPageTitle: Page => String` function of the Router's constructor. But, what if your `Page` type does not contain enough information to set a meaningful title? For example, you may want to render `PersonPage(personId: String)`, but don't yet have the actual `Person(id: String, name: String)` instance that this page should render?

Suppose that when your Laminar code renders `PersonPage`, it makes a network request to fetch the matching `Person` from the backend. Once you receive that data, you can call `router.replacePageTitle(person.name)` to update the document title. This will update the title of the current history record, without creating a new one (so in some sense, it's like a title-only `replaceState`).

This is equivalent to calling the `dom.document.title = newTitle` JS API.

As an alternative to this, you may be tempted to add `person.name` to your Page type: `PersonPage(personId: String, personName: String)` – however, that approach is not recommended, as it presents several problems:
- You won't be able to navigate to `PersonPage` without knowing `person.name` ahead of time
- You would need to write `person.name` into the URL (that anyone can change manually) – and on a full page load, you would have to read and use that untrusted input from there as well, which is generally undesirable.


### Partial Routes

Waypoint's API design is centered around matching pages by ClassTag, but under the hood we just use partial functions. So, you can create routes like this too, and yes, they can actually be partial, i.e. cover only a subset of the page type. For example, here is a route that matches pages only for big numbers.  

```scala
Route.applyPF[DocsPage, Int](
  matchEncode = { case DocsPage(NumPage(i)) if i > 100 => i },
  decode = { case arg if arg > 100 => DocsPage(NumPage(arg)) },
  pattern = root / "docs" / "num" / segment[Int]
)
```

When using partial matchers you need to be careful that the "partiality" of `matchEncode` and `decode` is symmetric, otherwise you might end up with a route that can parse a page from a URL but can't encode that same page into a URL (or vice versa).


### ContextRouteBuilder 

Imagine your web app has many routes with the same query params. For example, your Laminar web app might have a documentation section where every route is expected to have a "lang" query param to indicate the selected "language" and a "version" param to indicate the product version (unrealistic doc standards, I know).

You could add these two params to every page type that needs them and to every route for those page types, but that could be annoying. So instead, you can create a ContextRouteBuilder which will let you specify the necessary types / conversions / patterns only once.

To achieve this, ContextRouteBuilder introduces the concept of Bundle, which is basically a... bundle of (Page, Context), where Page is the usual Waypoint Page, and Context is a data structure that contains all the shared query params, such as "language" and "version" in our example.

See example usage in tests, specifically ContextRouteBuilderSpec.


### Base Path and Fragment Routes

If you want your single page application navigation to work on fragments, i.e. use URLs like `example.com/#/foo/bar` instead of `example.com/foo/bar`, you can do this by providing `basePath = Route.fragmentBasePath` to your Route constructor.

Typically you will want to do this for every one of your Routes, but the Waypoint API is flexible to allow only a subset of Routes to be fragment routes.

Why would you want this? Using path segments like `/foo/bar` without fragment routes requires the cooperation of the web server. Not needing that is useful when:

* Developing locally without a web server, by looking at a `file://` URL (in this case, use **`Router.localFragmentBasePath`** instead of `Route.fragmentBasePath`)
* Using a simple static site host like Github pages that doesn't provide a catch-all feature
* Embedding live examples of router applications with [mdoc](https://github.com/scalameta/mdoc)

When hosting your static site you might not be able to have a server respond to any route you want, you might be limited to just one url per html file on viewing a `file://` URL in the browser without a web server

As the name implies, aside from fragments, a Route's `basePath` can contain any path. It could be `/foo/bar/index.html#` or even just `/foo/bar`. If set, it must have a leading slash.

For the record, `basePath` is included in the route's `relativeUrl` as far as Waypoint's public methods are concerned.

Note that URL DSL offers a basic way to match the fragment string in the URL (everything after `#`) – see one of the examples above. Waypoint's `basePath` feature is different in that it lets you use standard non-fragment matching of URL DSL segments, query params, etc. on the fragment string.


## Error Handling


### Failing to Match Routes

If none of the routes match on initial page load, you can still render a page using `routeFallback` constructor param. The URL will not be updated, but `router.currentPageSignal` will emit the resulting page. If routeFallback throws, which is the default behaviour, `router.currentPageSignal` will be put into error state. If you want to throw for logging purposes, but don't want this to happen, return the current page and throw inside a setTimeout.

Similarly, if during user's navigation we encounter a History API state record that `deserializePage` throws on, you can handle it using `deserializeFallback`. It throws too by default, with the same effect as `routeFallback`.

`router.currentPageSignal` can also be in an errored state if the initial URL does not match the origin. Both of those are taken from `dom.window.location` by default so this shouldn't be an issue.


### Rendering Error Pages

`routeFallback` is useful to catch complete garbage URLs that match none of the routes, but you also want more refined control over invalid parameters.

For example, imagine you're matching a route like `/user/<userId>`. When the matching happens you don't know whether a user with `<userId>` actually exists, you will only know that after an asynchronous delay. So, your `userRoute` should successfully match this path, rendering an element that makes an AJAX request for this user's info on mount. Some milliseconds later when the AJAX response comes in you can either proceed to render the user or trigger a failure if AJAX resulted in an error. More specifically:

* You could call `router.forcePage(NotFoundPage(title, comment))` to force the router to emit the provided page without updating the URL (although document title will be updated unless the page's title is not empty). This pattern is useful to show a full page error screen without redirecting the user to a different URL or messing with the navigation history.

* You could call `router.pushState` or `router.replaceState` to render a different page with a redirect. Sometimes this is required, but for UX purposes you should avoid redirecting users to useless URLs like `/404` because this prevents the user from manually fixing the URL in the address bar. A `pushState` redirect can also interfere with the user's ability to use the browser back button.
  
So, `router.forcePage` is often the preferred way.




## Recipes

#### Resetting scroll position

Typically, when you navigate from one web page to another, the scroll position of the page is reset, such that if you scroll to the middle of the page, then click a link, the browser scrolls to the top of the next page, rather than showing its middle. This is the general expectation when navigating between unrelated pages, however this is not desirable in every navigation.

By default, Waypoint page transitions don't reset sroll position, however you can easily achieve this in the following way:

```scala
object router extends Router[Page](
  routes = ...,
  getPageTitle = ...
  serializePage = ...,
  deserializePage = ...
) {
  
  currentPageSignal.foreach { page =>
    dom.window.scrollTo(x = 0, y = 0) // Reset scroll position
  }(owner)
}
```

We put this logic inside `JsRouter` so that it can easily access the Router's `owner`. This is just one example of implementing simple, global, page transition callback.

When you use the browser "back" button to navigate, the browser will automatically restore the last scroll position on the target page. This default behaviour overrides your `dom.window.scrollTo` instruction. If you don't want this, you can [disable this globally](https://developer.mozilla.org/en-US/docs/Web/API/History/scrollRestoration) using:

```scala
// Call this once, around the time when you initialize Router
dom.window.history.scrollRestoration = dom.ScrollRestoration.manual 
```

Note that all of this is about the scroll position of the entire document / window. If you need similar logic for a different scrollable area, you will need to implement it yourself for now. See below for an example.


#### Pages carrying state not reflected in the URL

As contemplated above, suppose you want to "remember" vertical scroll position for a scrollable area on a certain page, so that when you navigate away from that page and then come back, it's restored. The browsers do this automatically for the scroll position of the document itself, but not for scroll positions of any scrollable areas inside the page.

To accomplish this, you can just include this information in the `Page`.

Pages are serialized into History API state records, so when the user uses browser back button to come back to a page that they've already been to, it's not just the old URL that's restored, it's the whole Page state. So the previously recorded scroll position will be restored.

In contrast, if the user **reloads** the page or clicks a link that causes a full page load instead of the History API transition, the Page state will be parsed from the URL only, and since the URL does not include scroll position in our case, that will need to be the default scroll position of zero. Like so:

```scala
Route[NotePage, (Int, Int)](
  encode = page => (page.libraryId, page.noteId), // scroll position is not written into the URL args
  decode = args => NotePage(libraryId = args._1, noteId = args._2, scrollPosition = 0), // default scroll position that will be inferred from the URL
  pattern = root / "app" / "library" / segment[Int] / "note" / segment[Int]
)
```

In addition to this, your code needs to actually scroll to the desired scroll position when loading the page. Use the [element.scrollTo](https://developer.mozilla.org/en-US/docs/Web/API/Element/scrollTo) method. You can probably just do this when switching from a different type of page to the type of page that remembers its scroll position.

Lastly, normally you fire `router.pushState` to update the current page. But when you're **only** updating current scroll position, you should instead fire `router.replaceState`, otherwise you will litter your browser history with a bunch of useless scrolling records.


#### Configuring your web server

Unless you're using `Route.fragmentBasePath` (see above) to put all your routes in the fragment of the URL, like `/#/foo/bar`, you need to ensure that your web server responds to all your Waypoint routes with the HTML file that will load your Scala.js bundle and thus the Waypoint application. Basically, if your frontend thinks that it's responsible for handling the route `example.com/foo/bar`, you better make sure that your backend will load that frontend if the user enters `example.com/foo/bar` in the address bar.

The easiest way to accomplish this is to put all or most of your Waypoint routes behind a prefix like `/app`. You can use `basePath` for this, or just start all your routes at `val appRoot = root / "app"`. Then on the backend you will tell the server to match anything under `/app` to your frontend.

You can keep a few pages like `/login` outside of the `app` world, but then you'll need to hardcode them in your backend routes as well.

Using a catch-all route on the backend is an option too, but that is likely to interfere with 404 error behaviour, so it's not recommended without a prefix like `/app`.

Note that you can put Waypoint's `Route` in `shared`, so if you were so inclined, you could write an integration with your web server framework to evaluate Waypoint's routes on the backend. See the implementation of `Router#pageForAbsoluteUrl`, it's pretty simple.


#### Responding to link clicks

Normally when navigating on the internet, users click on `<a href="url">` links, and the browser navigates them to `url`. This results in the target page being loaded from scratch, which is fine for content sites but is slow and inefficient for interactive, single page application (SPA) sites.

So when the user clicks a link in a single page application, you want to hijack that click – prevent the default browser action, and instead use Waypoint to `pushState` the new page. This will update the URL but instead of loading a new page from the web server, `router.currentPageSignal` will emit a new page, and your application will respond to that without network overhead.

In Laminar that can be achieved with a simple set of modifiers:

```scala
a(
  onClick.preventDefault --> (_ => router.pushState(page)),
  href := router.absoluteUrlForPage(page)
)
```

However, this is not good enough. For instance, what if the user ctrl-clicks, wanting to open the page in a new tab? Our code above will break the user's expectations, updating the content of the current page instead of opening a new tab. We need to be smarter. Long story short, `router.navigateTo(page)` does what we expect. Usage:

```scala
a(
  router.navigateTo(libraryPage), // also sets href attribute
  "Library"
)
button(
  router.navigateTo(logoutPage),
  "Log out"
)
```

When the element is clicked, `navigateTo` triggers Waypoint navigation to the provided page. You can even have it do `replaceState` instead of `pushState` by providing the optional `replaceState = true` argument.

When used on `a` link elements, `navigateTo`:
 - Also sets the `href` attribute to the page's absolute URL
 - Ignores clicks when the user is holding a modifier key like Ctrl/Shift/etc. while clicking
   - In that case, the browser's default link action (e.g. open in new tab) will happen instead

Waypoint also offers a version on version of navigateTo that accepts a `Signal[Page]` instead of `Page` – for cases when the page you want to navigate to changes over time.

I encourage you to look at the implementation of the `navigateTo` method to learn how to create such custom Laminar modifiers yourself.


#### Firefox and file:// URLs

Chrome and Safari return `"file://"` as the `location.origin` of `file://` URLs, which is what Waypoint expects, whereas Firefox returns `"null"` (as a string).

If a Waypoint error message pointed you to this section of the docs, that means you're giving Waypoint a `"null"` origin where a `protocol://` origin is expected, most likely because you're using Firefox with `file://` URLs (if you ever see this error message for any other reason, please let me know).

To work around this, you should use something like this:

```scala
def canonicalOrigin = if (dom.document.location.protocol == "file:") "file://" else dom.document.location.origin
```

or just use `router.origin` which is derived from `dom.document.location` in the same manner (unless you overrode the Router's `origin` constructor param).

Unfortunately I don't think I can fix this in Waypoint in a safe way (messing with URLs can be risky), but I also don't expect this to be a significant problem given the very limited scope of the problem (just the two public methods – `pageForAbsoluteUrl` and `pageForRelativeUrl`) and the easy workaround.


### Canonicalizing the URLs

When initializing the router on page load, we parse the initial URL and emit the corresponding Page. Note that unlike `pushState(page)` or `replaceState(page)`, we do not update the URL to the initial page's canonical URL, the original URL stays. This is helpful because the URL might contain query params that you don't want to include in your pages but also don't want to remove.

However, as soon as you navigate to a different page by means of `pushState(page)` or `replaceState(page)`, the URL will be updated to the canonical URL of the next page, with any extraneous query params removed.

If you want the initial URL canonicalized (as was done automatically prior to Waypoint 0.4.0), call `router.replaceState(router.currentPageSignal.now())` on page load.

If you need to manage large numbers of common query params, consider using [ContextRouteBuilder](#contextroutebuilder)




## Waypoint Without Laminar

Waypoint does not actually depend on Laminar, only on Airstream. You can use it with other Scala.js UI libraries too.

To use Waypoint without Laminar, you will need to provide a stream of `dom.OnPopState` events, which is very easy even without Laminar, just make one using Airstream's `DomEventStream`.

Of course, if you want to react to URL changes, you will also need a way to consume the observables provided by Waypoint, such as `router.currentPageSignal`. Doing this properly will require some basic knowledge of Airstream. If you use a different streaming library, it should be possible to interop between the two. I can help you do that if you agree to open source the resulting integration (does not need to be a polished library, just sample code / gist is fine).



## Author

Nikita Gazarov – [@raquo](https://twitter.com/raquo)

[Sponsor my open source work](https://github.com/sponsors/raquo).



## License & Credits

Waypoint is provided under the [MIT license](https://github.com/raquo/Waypoint/blob/master/LICENSE.md).

Waypoint uses [URL DSL](https://github.com/sherpal/url-dsl) for URL matching, also provided under the MIT license.
