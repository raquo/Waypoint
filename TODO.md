# TODO — `preventNavigation` (navigation blocking) follow-ups

Tracking items for the uncommitted navigation-blocking feature (`preventNavigation`,
`HistoryCodec`, sequential history indices). Grouped into implementation fixes, open
questions, testing tasks, and tooling options. File/line refs are approximate and may
drift as the code changes.

---

## Implementation / robustness

- [ ] **Degrade gracefully on undecodable popState state instead of throwing.**
  `handlePopState`'s `None` branch calls `codec.deserializeFallback(ev.state)`
  (`Router.scala` ~L434), which defaults to `throwOnInvalidState`. The URL is present and
  authoritative here, so prefer `pageForAbsoluteUrl(currentUrl).getOrElse(routeFallback(...))`
  like the hash-change branch already does (~L405). Resolve the leftover
  `#TODO[block] Why HashChange?` (~L437) and document why direction is guessed.
  - Sub-concern: **migration.** A tab open across a deploy has old-format history entries
    (bare `data`, no `index|` prefix). `decodeState` returns `None` for them → currently
    throws unless the app supplied a custom `deserializeFallback`. Add a note to CHANGELOG
    migration section; the URL-fallback above also fixes the crash.

- [ ] **Decide + encode fail-open vs fail-closed policy for async guard errors.**
  `stream.take(1).foreach(resolveNavigation)` (~L477) only observes *values*. A
  synchronous exception from `preventNavigation` fails **open** (allows, ~L480), but a
  stream that *emits an error* is never observed → `pendingNavigationOpt` never clears →
  router is **blocked forever** (fails closed). Inconsistent + undocumented. Suggest
  `.recover { case _ => false }` (fail open, matching the sync path) or an explicit failure
  observer. The never-emits case is documented as intentional and is fine.

- [ ] **Harden the `suppressNextPopState` handshake (or at least document its fragility).**
  It's a single boolean (~L69, set in `revertNavigation` ~L541/L547, consumed ~L386). It
  assumes exactly one `popState` results from each `history.back()`/`forward()` with nothing
  interleaving. Breaks under rapid navigation (double back-click), a real navigation racing
  the undo, or concurrent browser navigations while a guard is pending (the `Some(_)` reject
  branch reverts immediately AND sets the flag, ~L453). Hardening idea: record the expected
  index/state being undone to, and only suppress a `popState` matching it. **This is the one
  piece structurally invisible to JSDOM — validate it in a real browser.**

- [ ] **Note that `forcePage` bypasses the guard.** If called while a guard is pending, the
  displayed page changes while `pendingNavigationOpt` still references the old `fromPage`.
  Probably fine for its "not found" use case; add a one-line doc note.

- [ ] **`fromPage = currentPageSignal.now()` can throw on the popState path** if the router
  initialized into an errored state (~L469). `pushState`/`replaceState` catch it via `Try`,
  but `handlePopState` calls `submitNavigation` directly. Low priority.

### Style / cleanup

- [ ] Remove unused imports in `Router.scala`: `scala.scalajs.js` and `scala.scalajs.js.JSON`
  (L7-8, both moved to `HistoryCodec`), and the unqualified
  `import ...NavigationKind.RouterInitiated` (L4) — every use is fully qualified.
- [ ] Fix stale comments referencing a nonexistent `internalIndex` var (field is
  `navigationIndex`): ~L461-465, L527.
- [ ] Typos in `NavigationKind.scala`: "suer clicking" → "user clicking"; "are already been
  updated" → "have already been updated".
- [ ] README/field mismatch: README's "Blocking / Confirming Navigation" section says
  `Navigation` has a `url` field, but the field is `toRelativeUrl` (`Navigation.scala`).
  Reconcile (prefer `toRelativeUrl`).

---

## Open question: does blocking work with hash-based routing? (needs investigation)

Concern: navigation blocking with hash (`#`) changes, **especially when the entire router is
fragment-based** via `Route.fragmentBasePath` (`"/#"`, `Route.scala:160`), so every route
looks like `/#/foo`.

What we believe today (verify all of this in a real browser):

- **Waypoint-initiated fragment navigation should be fine.** `pushState`/`replaceState` write
  a full URL including `#/foo` *and* set our stamped `index|data` state. So back/forward
  between Waypoint pages fires `popState` with non-null state → `decodeState` works → the
  Back/Forward direction detection and undo work regardless of fragment vs path routing.

- **The risk is any hash change NOT done through Waypoint**, which is exactly what fragment
  routing invites:
  - manual hash edit in the address bar,
  - a plain `<a href="#/foo">` that isn't a `navigateTo` binder,
  - `location.hash = ...` from third-party code,
  - in-page anchor jumps (`#section`).
  These fire `popState` with **`ev.state == null`**, which Waypoint classifies as
  `HashChange` and always treats as **forward** (destinationIndex = originIndex + 1), undoing
  with `history.back()`. If such a change was actually a *back*, the undo goes the **wrong
  direction**, and there's no index to recover the real direction from.

- **Double-fire:** a hash change fires *both* `hashchange` and `popstate`. Waypoint listens to
  `popstate` only. Need to confirm blocking + the `suppressNextPopState` handshake behave when
  both events are in play (and across browsers).

- **Heuristic collision:** the whole "`ev.state == null` ⇒ hashChange" heuristic (~L396) is
  load-bearing. In a fully fragment-based app it still holds *because Waypoint always writes
  state*, but it's worth an explicit test that a Waypoint fragment navigation is NOT
  misclassified as a null-state hashChange.

Action items:
- [ ] Build a **fragment-based** router (`basePath = Route.fragmentBasePath` on every route)
  and exercise: Waypoint push/replace, browser back/forward, manual hash edit, plain
  `#`-anchor click — each with `preventNavigation` allowing and blocking. Confirm direction
  detection and undo.
- [ ] Decide intended behavior for blocking a *manual* hash change whose real direction is
  "back" (currently mis-undone forward). Document the limitation if not fixable without an
  index.

---

## Testing

### JSDOM reality check

- `pushState`/`replaceState`, `location.href`, `history.state`: supported by JSDOM. ✓
- **`history.back()`/`forward()`/`go()` do NOT reliably dispatch `popstate`** in JSDOM, and
  hashchange→popstate sequencing is unreliable. This is the "jsdom is kinda limited" note in
  `RouterSpec`.
- Current tests correctly work around this by (a) injecting `popStateEvents` as an `EventBus`
  to synthesize popstate events with hand-crafted stamped state, and (b) spying/stubbing
  `history.back`/`forward` to count calls. This verifies router **logic** but NOT the real
  round-trip (that `forward()`/`back()` truly returns to the right entry, fires a popstate
  with the right state, and that `suppressNextPopState` swallows *that* event and not a later
  real one). Artifact of the mock: `suppressNextPopState` is left `true` at end of the
  "prevents a back navigation" test — harmless only because each test builds a fresh router.

### Unit tests to add (JSDOM-friendly, via the popState bus)

- [ ] **Core index-disambiguation test (highest value — it's why the index exists):** push
  page A, then push a second entry with the **same URL**; synthesize a popstate to the first
  entry and assert it's classified **Back**, not Forward. URLs alone can't disambiguate this.
- [ ] **Allow (not prevent) a browser Back/Forward:** assert it commits, `navigationIndex`
  updates to the destination, the page emits, and `history.back/forward` are NOT called. (Only
  the *prevent* paths are covered today.)
- [ ] **Pending flag is released:** after a prevented navigation, a *subsequent* navigation
  must succeed. Today's "rejects other navigations while pending" only allows the first.
- [ ] **Guard toggling:** prevent once, flip the guard, retry → succeeds.
- [ ] **State-format assertions:** the string written to `history.state` carries the `index|`
  prefix; index is `0` after init; increments on push; unchanged on replace.
- [ ] **`deserializeFallback` with valid index but corrupt data** → routes through fallback,
  not a throw.
- [ ] **Undecodable / old-format state on popState** → pin the decided behavior from the
  implementation task above (graceful URL fallback vs throw).
- [ ] **Reject-while-pending for a *browser* nav** (not just router-initiated) → assert
  `revertNavigation` fires (the `Some(_)` non-RouterInitiated branch).
- [ ] **Fail-open on a synchronous guard exception**, plus a test pinning the chosen policy
  for a **guard stream that errors**.
- [ ] **Fragment-routing tests** (see the hash-routing section above).

### Real-browser E2E (the part JSDOM can't reach)

The suppression handshake and true back/forward round-trips can only be validated in a real
browser. Scenarios:

- [ ] Unsaved-changes modal: browser **Back** → modal → "Stay" restores URL/page; "Leave"
  proceeds. Same for **Forward**.
- [ ] Same-URL adjacent entries back/forward (end-to-end index validation).
- [ ] Rapid double-back, and a user navigation racing a pending guard (stress
  `suppressNextPopState`).
- [ ] Hash-link (`#/foo`) navigation and manual address-bar hash edit (ties into fragment
  routing question).
- [ ] Full reload mid-session, then back into stamped entries.
- [ ] Observe the documented address-bar flicker on undo; confirm rendered page never changes.

Recommendation: treat "has at least a manual browser test page (or automated E2E)" as a
**release gate** for this feature, since the most fragile part is invisible to JSDOM.

---

## Tooling options for real-browser testing of Scala.js

Two families. See notes on which fits Waypoint.

### A. Swap the Scala.js JSEnv → run the *existing* `RouterSpec` tests in a real browser

Least-effort path: keep the tests as Scala.js, just change `jsEnv` in `build.sbt` (currently
`JSDOMNodeJSEnv`). Gives a real History API / real `popstate` / real back/forward, so the
round-trip and suppression handshake become testable with minimal test rewrites (real
back/forward is async, so tests would need to await popstate rather than emit into a bus).

- **`scalajs-env-selenium`** — official Scala.js project (`org.scala-js`), BSD-3.
  `libraryDependencies += "org.scala-js" %% "scalajs-env-selenium" % "1.1.1"` (Scala.js 1.x),
  `jsEnv := new org.scalajs.jsenv.selenium.SeleniumJSEnv(capabilities)`. Most "official" /
  maintained-by-core option. https://github.com/scala-js/scala-js-env-selenium
- **`scala-js-env-playwright`** (gmkumar2005) — community JSEnv backed by Playwright
  (Chromium/Firefox/WebKit, headless). `jsEnv := new PWEnv(browserName = "chrome", headless =
  true, ...)`; plugin dep `io.github.gmkumar2005 %% "scala-js-env-playwright"`. Nicer browser
  management than raw Selenium; community-maintained, so check recency.
  https://github.com/gmkumar2005/scala-js-env-playwright

### B. External JVM driver loads the compiled app (true user-simulation E2E)

More setup (build the app, serve it, drive from JVM tests), but models real user behavior best
and gives real `page.goBack()`/`goForward()` exercising session history + popstate.

- **Playwright for Java** (`com.microsoft.playwright`) — drive from JVM (Scala) tests; robust
  `goBack`/`goForward`/waiting. https://playwright.dev/java/
- **`weaver-playwright`** (indoorvivants) — thin integration of Playwright with the Weaver
  test framework, aimed at frontend testing.
  https://github.com/indoorvivants/weaver-playwright
- **ScalaTest Selenium DSL** (`org.scalatest.selenium.WebBrowser`) — mix the `WebBrowser`
  trait for a `click on id(...)` / `go to (...)` DSL over a `WebDriver`. Familiar if already
  on ScalaTest (Waypoint is).
- **ScalaWebTest** — Selenium-based integration testing with a pseudo-HTML matching DSL;
  heavier, more about asserting rendered HTML than driving navigation.

**Suggested approach for Waypoint:** start with **A (`scalajs-env-selenium` or
`scala-js-env-playwright`)** to get the existing spec running against a real History API with
minimal churn — that directly closes the back/forward + suppression gaps. Add a small **B**
Playwright(-Java) suite or a manual demo page only if full user-simulation (unsaved-changes
modal flow, rapid double-back) is wanted as a release gate.

Sources:
- https://github.com/scala-js/scala-js-env-selenium
- https://github.com/gmkumar2005/scala-js-env-playwright
- https://playwright.dev/java/
- https://github.com/indoorvivants/weaver-playwright
- https://www.artima.com/docs-scalatest-2.0.M5/org/scalatest/selenium/WebBrowser.html
- https://www.scalawebtest.org/
