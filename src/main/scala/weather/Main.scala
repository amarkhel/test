package weather

import cats.effect.{IO, IOApp}
import com.comcast.ip4s._
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.Logger
import weather.util.{CircuitBreaker, CircuitBreakerConfig, RateLimiter, RateLimiterConfig, RateLimiterMiddleware}

object Main extends IOApp.Simple {

  def run: IO[Unit] =
    RateLimiter.create[String](RateLimiterConfig(
      requestsPerSecond = Config.rateLimitRequestsPerSecond,
      burstSize = Config.rateLimitBurstSize
    )).flatMap { rateLimiter =>
    CircuitBreaker.create(CircuitBreakerConfig(
      failureThreshold = Config.circuitBreakerFailureThreshold,
      resetTimeout = Config.circuitBreakerResetTimeout,
      tripOn = {
        case _: WeatherError.UpstreamUnavailable => true
        case _: java.io.IOException              => true
        case _                                   => false
      }
    )).flatMap { circuitBreaker =>
    val cbOption = if (Config.circuitBreakerEnabled) Some(circuitBreaker) else None
    EmberClientBuilder.default[IO].build.use { client =>
      val baseRoutes = Routes.weatherRoutes(client, cbOption)
      val routes =
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
        .use { _ =>
          IO.println("Weather server running on http://localhost:8080") *> IO.never
        }
    }}}
}
