package weather

import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s._
import org.http4s.HttpRoutes
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.Logger
import weather.util.{CircuitBreaker, CircuitBreakerConfig, RateLimiter, RateLimiterConfig, RateLimiterMiddleware}

object Main extends IOApp.Simple {

  def run: IO[Unit] = program.use(_ => IO.println("Weather server running on http://localhost:8080") *> IO.never)

  // ---------------------------------------------------------------------------
  // Cache factory — returns a managed Resource so Redis connections are closed
  // ---------------------------------------------------------------------------
  private def buildCache: Resource[IO, WeatherCache] =
    Config.cacheType match {
      case "redis" =>
        RedisWeatherCache.resource(
          Config.redisUri,
          Config.pointsCacheTtl,
          Config.forecastCacheTtl
        )
      case "memory" =>
        Resource.eval(
          WeatherCache.inMemory(
            pointsTtl          = Config.pointsCacheTtl,
            forecastTtl        = Config.forecastCacheTtl,
            pointsMaxEntries   = Config.pointsCacheMaxEntries,
            forecastMaxEntries = Config.forecastCacheMaxEntries
          )
        )
      case _ =>
        Resource.pure[IO, WeatherCache](WeatherCache.noop)
    }

  // ---------------------------------------------------------------------------
  // Full application wired together as a single Resource
  // ---------------------------------------------------------------------------
  private def program: Resource[IO, Unit] =
    for {
      rateLimiter    <- Resource.eval(RateLimiter.create[String](RateLimiterConfig(
                          requestsPerSecond = Config.rateLimitRequestsPerSecond,
                          burstSize         = Config.rateLimitBurstSize
                        )))
      circuitBreaker <- Resource.eval(CircuitBreaker.create(CircuitBreakerConfig(
                          failureThreshold = Config.circuitBreakerFailureThreshold,
                          resetTimeout     = Config.circuitBreakerResetTimeout,
                          tripOn = {
                            case _: WeatherError.UpstreamUnavailable => true
                            case _: java.io.IOException              => true
                            case _                                   => false
                          }
                        )))
      client         <- EmberClientBuilder.default[IO].build
      cache          <- buildCache
      _              <- {
                          val cbOption   = if (Config.circuitBreakerEnabled) Some(circuitBreaker) else None
                          val baseRoutes = Routes.weatherRoutes(client, cbOption, cache)
                          val routes: HttpRoutes[IO] =
                            if (Config.rateLimitEnabled)
                              RateLimiterMiddleware(rateLimiter, Config.rateLimitRetryAfterSeconds)(baseRoutes)
                            else
                              baseRoutes
                          val httpApp = Logger.httpApp(logHeaders = false, logBody = false)(routes.orNotFound)
                          EmberServerBuilder
                            .default[IO]
                            .withHost(ipv4"0.0.0.0")
                            .withPort(port"8080")
                            .withHttpApp(httpApp)
                            .build
                        }
    } yield ()
}
