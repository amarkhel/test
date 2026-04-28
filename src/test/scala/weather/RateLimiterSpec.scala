package weather

import cats.effect.IO
import munit.CatsEffectSuite
import weather.util.{RateLimiter, RateLimiterConfig, RateLimiterMiddleware}
import org.http4s._
import org.http4s.implicits._
import cats.syntax.all._

class RateLimiterSpec extends CatsEffectSuite with WeatherServiceTestBase {

  private def makeRoutes(limiter: RateLimiter[String]) =
    RateLimiterMiddleware(limiter)(
      Routes.weatherRoutes(mockClient())
    ).orNotFound

  private val tightConfig = RateLimiterConfig(requestsPerSecond = 1.0, burstSize = 2)
  private val looseConfig = RateLimiterConfig(requestsPerSecond = 1000.0, burstSize = 100)

  // -------------------------------------------------------------------------
  // Token bucket behaviour
  // -------------------------------------------------------------------------

  test("RateLimiter: allows requests within burst size") {
    RateLimiter.create[String](looseConfig).flatMap { limiter =>
      (1 to 10).toList.traverse(_ => limiter.acquire("key1")).map { results =>
        assert(results.forall(_ == true))
      }
    }
  }

  test("RateLimiter: rejects when bucket is empty") {
    RateLimiter.create[String](tightConfig).flatMap { limiter =>
      // drain all burst tokens
      limiter.acquire("key1").replicateA(2) >>
        limiter.acquire("key1").map { result =>
          assertEquals(result, false)
        }
    }
  }

  test("RateLimiter: independently tracks different keys") {
    RateLimiter.create[String](tightConfig).flatMap { limiter =>
      limiter.acquire("key1").replicateA(2) >>
        limiter.acquire("key2").map { result =>
          assertEquals(result, true)
        }
    }
  }

  // -------------------------------------------------------------------------
  // Middleware behaviour
  // -------------------------------------------------------------------------

  test("RateLimiterMiddleware: passes allowed requests through") {
    RateLimiter.create[String](looseConfig).flatMap { limiter =>
      val app = makeRoutes(limiter)
      val req = Request[IO](Method.GET,
        Uri.unsafeFromString("/weather?lat=39.7456&lon=-97.0892"))
      app.run(req).map { resp =>
        assertEquals(resp.status, Status.Ok)
      }
    }
  }

  test("RateLimiterMiddleware: returns 429 when rate limit exceeded") {
    RateLimiter.create[String](tightConfig).flatMap { limiter =>
      val app = makeRoutes(limiter)
      val req = Request[IO](Method.GET,
        Uri.unsafeFromString("/weather?lat=39.7456&lon=-97.0892"))
      for {
        _ <- app.run(req).replicateA(2)
        resp <- app.run(req)
      } yield assertEquals(resp.status, Status.TooManyRequests)
    }
  }

  test("RateLimiterMiddleware: 429 response includes Retry-After header") {
    RateLimiter.create[String](tightConfig).flatMap { limiter =>
      val app = makeRoutes(limiter)
      val req = Request[IO](Method.GET,
        Uri.unsafeFromString("/weather?lat=39.7456&lon=-97.0892"))
      for {
        _ <- app.run(req).replicateA(2)
        resp <- app.run(req)
      } yield {
        assertEquals(resp.status, Status.TooManyRequests)
        assert(resp.headers.get(org.typelevel.ci.CIString("Retry-After")).isDefined,
          "Expected Retry-After header in 429 response")
      }
    }
  }

  test("RateLimiterMiddleware: uses X-Forwarded-For for client key") {
    RateLimiter.create[String](tightConfig).flatMap { limiter =>
      val app = makeRoutes(limiter)
      val ipA = Header.Raw(org.typelevel.ci.CIString("X-Forwarded-For"), "1.2.3.4")
      val ipB = Header.Raw(org.typelevel.ci.CIString("X-Forwarded-For"), "5.6.7.8")
      val reqA = Request[IO](Method.GET, Uri.unsafeFromString("/weather?lat=39.7456&lon=-97.0892"))
        .putHeaders(ipA)
      val reqB = Request[IO](Method.GET, Uri.unsafeFromString("/weather?lat=39.7456&lon=-97.0892"))
        .putHeaders(ipB)
      for {
        _ <- app.run(reqA).replicateA(2)
        rateLimitedA <- app.run(reqA)
        notRateLimitedB <- app.run(reqB)
      } yield {
        assertEquals(rateLimitedA.status, Status.TooManyRequests)
        assertEquals(notRateLimitedB.status, Status.Ok)
      }
    }
  }

  // Provide a traverse instance for List
  private implicit class ListOps[A](list: List[A]) {
    def traverse[B](f: A => IO[B]): IO[List[B]] =
      list.foldLeft(IO.pure(List.empty[B])) { (acc, a) =>
        acc.flatMap(bs => f(a).map(b => bs :+ b))
      }
  }
}

