# Changelog

Breaking changes in **bold**.

_You can now [sponsor](https://github.com/sponsors/raquo) Laminar / Airstream / Waypoint development!_

#### Unreleased 01 Feb 2021
* Update [`url-dsl`](https://github.com/sherpal/url-dsl) to `0.3.2`
* Update `Airstream` and `Laminar` to `0.12.0-M1`
* Update `sbt` to `1.4.7`

#### v0.2.0 – Aug 2020

* **API: Waypoint does not depend on Laminar anymore**
  * Add `$popStateEvent` to `Router` constructor
  * _Migration: You should provide `$popStateEvent = windowEvents.onPopState`_ if using Laminar

#### v0.1.0 – Mar 2020

Initial release!
