# Changelog

Breaking changes in **bold**.

_You can now [sponsor](https://github.com/sponsors/raquo) Laminar / Airstream / Waypoint development!_

#### v0.4.0 – May 2021

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
  * See [URL-DSL migration notes](https://github.com/sherpal/url-dsl#moving-from-020-to-03x)
  * Note: in Waypoint, the required imports stay the same.
* Build: Update `sbt` to `1.4.7`

#### v0.2.0 – Aug 2020

* **API: Waypoint does not depend on Laminar anymore**
  * Add `$popStateEvent` to `Router` constructor
  * _Migration: You should provide `$popStateEvent = windowEvents.onPopState`_ if using Laminar

#### v0.1.0 – Mar 2020

Initial release!
