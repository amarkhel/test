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

  // Rate limiting (token bucket, per client IP)
  val rateLimitEnabled: Boolean = true
  val rateLimitRequestsPerSecond: Double = 10.0
  val rateLimitBurstSize: Int = 20
  val rateLimitRetryAfterSeconds: Int = 1

  // Circuit breaker for outbound NWS calls
  val circuitBreakerEnabled: Boolean = true
  val circuitBreakerFailureThreshold: Int = 5   // failures before opening
  val circuitBreakerResetTimeout: FiniteDuration = 30.seconds
}

