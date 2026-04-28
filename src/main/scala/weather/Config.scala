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

  // Cache backend selection.
  // Env var WEATHER_CACHE_TYPE accepts: "memory" (default) | "redis" | "none"
  val cacheType: String = sys.env.getOrElse("WEATHER_CACHE_TYPE", "memory")

  // Cache /points lookups longer than forecasts because grid mapping is stable.
  val pointsCacheTtl: FiniteDuration = 6.hours
  val forecastCacheTtl: FiniteDuration = 10.minutes
  val pointsCacheMaxEntries: Int = 5000
  val forecastCacheMaxEntries: Int = 10000

  // Redis URI used when cacheType = "redis". Override with env var REDIS_URI.
  val redisUri: String = sys.env.getOrElse("REDIS_URI", "redis://localhost:6379")
}

