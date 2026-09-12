import org.scalajs.jsenv.selenium.SeleniumJSEnv
import com.sun.net.httpserver.SimpleFileServer
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}

object Selenium {

  val jsEnv: SeleniumJSEnv = {
    val _ = Selenium.silencedSeleniumLoggers
    val materializeUrl = Selenium.seleniumMaterializeUrl // starts the server, memoized for the sbt session
    new SeleniumJSEnv(
      driverFactory = Selenium.trackedChromeDriverFactory,
      config = {
        SeleniumJSEnv.Config().withMaterializeInServer(
          Selenium.seleniumMaterializeDir.getPath,
          materializeUrl
        )
      }
    )
  }

  // Test bundles must be served over http://, not file://: Chrome refuses
  // History API calls (pushState / replaceState) on file:// origins, which
  // SeleniumJSEnv's default (temp-file) materialization would use.
  lazy val seleniumMaterializeDir: java.io.File = {
    val d = new java.io.File("js/target/selenium-materialize").getAbsoluteFile
    sbt.io.IO.createDirectory(d)
    d
  }

  // Note: Evaluating this val starts the server.
  // Port 0 => the OS assigns a free port to avoid conflicts with multiple sessions.
  lazy val seleniumHttpServer = {
    val server = SimpleFileServer.createFileServer(
      new java.net.InetSocketAddress("localhost", 0),
      seleniumMaterializeDir.toPath,
      SimpleFileServer.OutputLevel.NONE
    )
    server.start()
    server
  }

  // Note: Evaluating this val starts the server.
  lazy val seleniumMaterializeUrl = s"http://localhost:${seleniumHttpServer.getAddress.getPort}/"

  // Silence specific Selenium Chrome DevTools Protocol warnings we don't care about
  lazy val silencedSeleniumLoggers: Seq[java.util.logging.Logger] = {
    val cdpVersionWarningFilter: java.util.logging.Filter = { record =>
      val msg = record.getMessage
      msg == null || !(
        msg.startsWith("Unable to find CDP implementation matching") ||
          msg.startsWith("Unable to find version of CDP to use for")
        )
    }
    val loggers = Seq(
      java.util.logging.Logger.getLogger("org.openqa.selenium.devtools.CdpVersionFinder"),
      java.util.logging.Logger.getLogger("org.openqa.selenium.chromium.ChromiumDriver")
    )
    loggers.foreach(_.setFilter(cdpVersionWarningFilter))
    loggers
  }

  // SeleniumRun quits its ChromeDriver asynchronously (SeleniumRun.future.andThen,
  // on a single-thread executor). When sbt is invoked from the CLI it exits as soon
  // as the test task reports results — often before that async quit() runs — so the
  // chromedriver (a child of the sbt JVM) and its headless Chrome reparent to PID 1
  // and pile up until a run fails at startup. We can't reach that async path, but we
  // own the driver factory, so we track every driver we hand out and quit them all
  // from a JVM shutdown hook. quit() on an already-quit driver just throws, which we
  // swallow, so this is a harmless backstop over the normal cleanup, not a replacement.
  lazy val trackedChromeDriverFactory: () => ChromeDriver = {
    val liveDrivers = java.util.Collections.newSetFromMap(
      new java.util.concurrent.ConcurrentHashMap[ChromeDriver, java.lang.Boolean]()
    )
    java.lang.Runtime.getRuntime.addShutdownHook(new Thread("waypoint-quit-chromedrivers") {
      override def run(): Unit = {
        val it = liveDrivers.iterator()
        while (it.hasNext) {
          try {
            it.next().quit()
          } catch {
            case _: Throwable => ()
          }
        }
      }
    })
    () => {
      val driver = new ChromeDriver(
        new ChromeOptions().addArguments(
          "--headless=new",
          "--no-sandbox",
          "--disable-dev-shm-usage",
          // The navigation specs drive the History API far faster than a human ever would. Chrome's
          // IPC-flooding protection caps history changes (pushState / replaceState / back / forward /
          // location.hash) at ~10 per second per frame, and beyond that silently drops them — so a
          // spec that navigates a lot (BasePathSpec) exhausts the budget and a later spec's
          // `location.hash = ...` becomes a no-op that fires no popstate, timing the test out. This
          // throttle is a real page-hang mitigation, irrelevant to a test harness driving one page,
          // so we turn it off here (test-only; never shipped to users).
          "--disable-ipc-flooding-protection"
        )
      )
      liveDrivers.add(driver)
      driver
    }
  }
}
