# Changelog

Breaking changes in **bold**.

_You can now [sponsor](https://github.com/sponsors/raquo) Laminar / Airstream / Waypoint development!_

#### v8.0.1 – Aug 2024
* Build: Update URL DSL to 0.6.2
  * Fixed a case with `listParam` where matching failed if no query parameters were provided in the URL, whereas it should have matched as `Nil`. Thanks, [@arturaz](https://github.com/arturaz)!


#### v8.0.0 – May 2024
* **Build: Update Airstream to 17.0.0, URL DSL to 0.6.1**
* Build: Fix source maps URLs
* Misc: Unrouted page exception now includes the exact faulty page in the message

#### v7.0.0 – Jul 2023
* **Build: Update Airstream to 16.0.0**


#### v6.0.0 – Mar 2023

* **Build: Update Airstream to 15.0.0, URL DSL to 0.6.0**
* Fix: `collectStatic` and `collectStaticStrict` now match pages using `==` equality instead of ClassTag. This fixes matching of Scala 3 enum values.

#### v6.0.0-M4 – Feb 2023

* **Build: Update Airstream to 15.0.0-M4**

#### v6.0.0-M3 – Jan 2023

* **Build: Update Airstream to 15.0.0-M3**

#### v6.0.0-M2 – Jan 2023

* **Build: Update Airstream to 15.0.0-M2**

#### v6.0.0-M1 – Jan 2023

* **Build: Update Airstream to 15.0.0-M1, URL-DSL to 0.5.0, etc.**
* **Naming: `$popStateEvent` -> `popStateEvents`**
* **Naming: `$currentPage` -> `currentPageSignal`**
* **Naming: `$view` -> `signal`**
* **API: Use `Tuplez.unapply`. Fixes [this issue](https://github.com/sherpal/url-dsl/issues/12).** Thanks, [@yurique](https://github.com/yurique)! 
* **API: Non-optional query params now match empty strings, e.g. `url?param=` and even `url?param`. See [this issue](https://github.com/raquo/url-dsl/pull/1). Thanks, [@i10416](https://github.com/i10416)**
* New: `collectStaticStrict` for when you want to cache the element.

#### v0.5.0 – Nov 2021

* **Build: Update Airstream to 0.14.0, bump Scala to 2.13.6 and 3.0.2, Scala.js to 1.7.1, uPickle to 1.4.2**

#### v0.4.2 – July 2021

* Fix: Work around `location.origin` being `"null"` for `file://` URLs in Firefox.

#### v0.4.1 – July 2021

* Do not use – this version is similar to v0.4.2, but there's a typo in one of the error messages

#### v0.4.0 – May 2021

* **Fix: When initializing the router, do not update the initial URL to the canonical URL of the initial page**
  * Keep the original URL with whatever extraneous query params it might have, to give any third party tools you might be using a chance to look at them
* Fix: Respond to `hashChange` events properly
* New: Base path and fragment matching
  * You can now match routes like `/#/note/123` instead of `/note/123` by providing `basePath = Route.fragmentBasePath` to Route constructors
  * You can also use hash routing on a `file://` URL now
* New: Basic route helpers to match URL fragments (text after `#`)

#### v0.3.0 – Feb 2021

* **New: Routes can match types partially now (thanks, [@pbuszka](https://github.com/pbuszka)!)**
  * New route creation methods with PF suffix
  * `route.argsFromPage` returns an Option now
* **New: Customizable error handling**
  * You can now specify fallbacks for URLs and page states that fail to match
  * You can now force-render a page without changing URL (useful for rendering full page error screens)
  * `Router` constructor arguments have changed and are now spread across two argument lists, and some arguments have defaults now
* New: ContextRouteBuilder (thanks, [@pbuszka](https://github.com/pbuszka)!)
  * Provides a convenient way to encode URL params like "lang" or "version" that are shared among a set of pages & routes
* API: Use `PatternArgs` alias for URL DSL's `UlrMatching` type
* **Build: Update `Airstream` to `0.12.0`**
* **Build: Update [`url-dsl`](https://github.com/sherpal/url-dsl) to `0.3.2`**
  * See [URL DSL migration notes](https://github.com/sherpal/url-dsl#moving-from-020-to-03x)
  * Note: in Waypoint, the required imports stay the same.
* Build: Update `sbt` to `1.4.7`

#### v0.2.0 – Aug 2020

* **API: Waypoint does not depend on Laminar anymore**
  * Add `$popStateEvent` to `Router` constructor
  * _Migration: You should provide `$popStateEvent = windowEvents.onPopState`_ if using Laminar

#### v0.1.0 – Mar 2020

Initial release!
