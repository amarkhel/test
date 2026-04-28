package weather

import scala.concurrent.duration._

/** Application-wide constants. */
object Config {
  val nwsBaseUrl: String = "https://api.weather.gov"

  val retryMaxAttempts: Int = 3
  val retryInitialDelay: FiniteDuration = 100.millis
  val retryBackoffMultiplier: Double = 2.0
  val retryMaxDelay: FiniteDuration = 1.second
  val retryJitterEnabled: Boolean = true
  val retryJitterRatio: Double = 0.20
  val retryOnHttp429: Boolean = true
}

