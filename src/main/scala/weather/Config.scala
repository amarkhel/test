package weather

import scala.concurrent.duration._

/** Application-wide constants. */
object Config {
  val nwsBaseUrl: String = "https://api.weather.gov"

  // Cache /points lookups longer than forecasts because grid mapping is stable.
  val pointsCacheTtl: FiniteDuration = 6.hours
  val forecastCacheTtl: FiniteDuration = 10.minutes

  val pointsCacheMaxEntries: Int = 5000
  val forecastCacheMaxEntries: Int = 10000
}

