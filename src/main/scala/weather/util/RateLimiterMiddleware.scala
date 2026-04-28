package weather.util

import cats.data.OptionT
import cats.effect.IO
import io.circe.Json
import org.http4s.{Header, HttpRoutes, Request}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.ci.CIString

/**
 * http4s middleware that applies a [[RateLimiter]] to any [[HttpRoutes]].
 *
 * Client identity is resolved in order:
 *   1. First value of the `X-Forwarded-For` header (proxy-aware)
 *   2. Remote address of the TCP connection
 *   3. `"unknown"` if neither is available
 *
 * Rejected requests receive `429 Too Many Requests` with a
 * `Retry-After: <seconds>` header and a JSON error body.
 */
object RateLimiterMiddleware {

  private val XForwardedFor: CIString = CIString("X-Forwarded-For")

  private def clientKey(req: Request[IO]): String =
    req.headers.headers
      .find(_.name == XForwardedFor)
      .map(_.value.split(",").head.trim)
      .orElse(req.remoteAddr.map(_.toString))
      .getOrElse("unknown")

  def apply(
      limiter: RateLimiter[String],
      retryAfterSeconds: Int = 1
  )(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes[IO] { req =>
      OptionT.liftF(limiter.acquire(clientKey(req))).flatMap { allowed =>
        if (allowed) routes(req)
        else
          OptionT.liftF(
            TooManyRequests(
              Json.obj("error" -> Json.fromString("Rate limit exceeded. Please slow down."))
            ).map(
              _.putHeaders(Header.Raw(CIString("Retry-After"), retryAfterSeconds.toString))
            )
          )
      }
    }
}

