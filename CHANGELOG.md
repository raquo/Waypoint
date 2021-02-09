# Changelog

Breaking changes in **bold**.

_You can now [sponsor](https://github.com/sponsors/raquo) Laminar / Airstream / Waypoint development!_

#### v0.3.0 – Feb 2021

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
