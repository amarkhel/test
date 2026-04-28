package weather

import cats.effect.{IO, Ref}
import cats.implicits._
import munit.CatsEffectSuite
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Tests for the [[WeatherCache]] interface.
  *
  * In-memory tests always run.
  * Redis tests run when Docker is available (via Testcontainers).
  */
class WeatherCacheSpec extends CatsEffectSuite {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private val samplePoints   = PointsResponse("https://api.weather.gov/gridpoints/TOP/31,80/forecast")
  private val sampleForecast = ForecastResponse(List(
    ForecastPeriod("Today",   72, "F", "Sunny"),
    ForecastPeriod("Tonight", 55, "F", "Clear")
  ))

  private final class RedisContainer(image: DockerImageName)
      extends GenericContainer[RedisContainer](image)

  /** Builds a counter-wrapped fetch effect: (fetchIO, countIO). */
  private def countedFetch[A](result: A): IO[(IO[A], IO[Int])] =
    Ref.of[IO, Int](0).map { ref =>
      (ref.update(_ + 1).as(result), ref.get)
    }

  private def uniqueKey(): String = s"test-${java.util.UUID.randomUUID()}"

  private def dockerCheckMessage(err: Throwable): String = {
    val base = Option(err.getMessage).filter(_.nonEmpty).getOrElse(err.getClass.getSimpleName)
    if (base.toLowerCase.contains("client version") && base.toLowerCase.contains("too old"))
      s"Docker API mismatch for Testcontainers ($base). Unset DOCKER_API_VERSION or upgrade Docker."
    else
      s"Docker daemon is unavailable for Testcontainers Redis tests ($base)"
  }

  /** Runs a test block with a Testcontainers Redis instance.
    * Marks the test Ignored when Docker is unavailable.
    */
  private def withRedisContainerOrSkip[A](
      pointsTtl:   FiniteDuration = 5.minutes,
      forecastTtl: FiniteDuration = 5.minutes
  )(test: WeatherCache => IO[A]): IO[A] = {
    val dockerAvailable =
      try Right(DockerClientFactory.instance().isDockerAvailable)
      catch {
        case NonFatal(e) => Left(dockerCheckMessage(e))
      }

    dockerAvailable match {
      case Left(reason) =>
        IO {
          assume(false, reason)
          throw new IllegalStateException("unreachable")
        }
      case Right(false) =>
        IO {
          assume(false, "Docker daemon is unavailable for Testcontainers Redis tests")
          throw new IllegalStateException("unreachable")
        }
      case Right(true) =>
        IO.blocking {
          val c = new RedisContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
          c.start()
          c
        }.bracket { container =>
          val redisUri = s"redis://${container.getHost}:${container.getMappedPort(6379)}"
          RedisWeatherCache.resource(redisUri, pointsTtl, forecastTtl).use(test)
        } { container =>
          IO.blocking(container.stop()).handleErrorWith(_ => IO.unit)
        }
    }
  }

  // ---------------------------------------------------------------------------
  // Shared behavioural suite — currently applied to in-memory cache
  // ---------------------------------------------------------------------------

  private def runCacheSuite(label: String, makeCache: IO[WeatherCache]): Unit = {

    test(s"$label: points cache hit avoids re-fetching") {
      for {
        cache         <- makeCache
        p             <- countedFetch(samplePoints)
        (fetch, cnt)   = p
        key            = uniqueKey()
        _             <- cache.getOrFetchPoints(key)(fetch)
        _             <- cache.getOrFetchPoints(key)(fetch)
        count         <- cnt
      } yield assertEquals(count, 1)
    }

    test(s"$label: forecast cache hit avoids re-fetching") {
      for {
        cache         <- makeCache
        p             <- countedFetch(sampleForecast)
        (fetch, cnt)   = p
        key            = uniqueKey()
        _             <- cache.getOrFetchForecast(key)(fetch)
        r             <- cache.getOrFetchForecast(key)(fetch)
        count         <- cnt
      } yield {
        assertEquals(count, 1)
        assertEquals(r.periods.head.temperature, 72)
      }
    }

    test(s"$label: different keys each trigger their own fetch") {
      for {
        cache         <- makeCache
        p             <- countedFetch(samplePoints)
        (fetch, cnt)   = p
        _             <- cache.getOrFetchPoints(uniqueKey())(fetch)
        _             <- cache.getOrFetchPoints(uniqueKey())(fetch)
        count         <- cnt
      } yield assertEquals(count, 2)
    }

    test(s"$label: fetch errors are NOT cached - next call retries upstream") {
      for {
        cache       <- makeCache
        failRef     <- Ref.of[IO, Int](0)
        successRef  <- Ref.of[IO, Int](0)
        key          = uniqueKey()
        fetchFail    = failRef.update(_ + 1) *> IO.raiseError[PointsResponse](new RuntimeException("boom"))
        fetchOk      = successRef.update(_ + 1).as(samplePoints)
        _           <- cache.getOrFetchPoints(key)(fetchFail).attempt
        _           <- cache.getOrFetchPoints(key)(fetchOk)
        fails       <- failRef.get
        successes   <- successRef.get
      } yield {
        assertEquals(fails,     1)
        assertEquals(successes, 1)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // In-memory cache suite (always runs)
  // ---------------------------------------------------------------------------

  runCacheSuite("InMemory", WeatherCache.inMemory(1.hour, 1.hour, 100, 100))

  test("InMemory: TTL expiry triggers fresh fetch") {
    for {
      cache         <- WeatherCache.inMemory(50.millis, 50.millis, 100, 100)
      p             <- countedFetch(samplePoints)
      (fetch, cnt)   = p
      key            = uniqueKey()
      _             <- cache.getOrFetchPoints(key)(fetch)
      _             <- IO.sleep(120.millis)
      _             <- cache.getOrFetchPoints(key)(fetch)
      count         <- cnt
    } yield assertEquals(count, 2)
  }

  test("InMemory: max-entries eviction removes least-recently-touched entry") {
    for {
      cache        <- WeatherCache.inMemory(1.hour, 1.hour, 2, 2)
      p1           <- countedFetch(PointsResponse("url-A"))
      (f1, cnt1)    = p1
      p2           <- countedFetch(PointsResponse("url-B"))
      (f2, _)       = p2
      p3           <- countedFetch(PointsResponse("url-C"))
      (f3, _)       = p3
      keyA          = uniqueKey()
      keyB          = uniqueKey()
      keyC          = uniqueKey()
      _            <- cache.getOrFetchPoints(keyA)(f1)
      _            <- IO.sleep(2.millis)
      _            <- cache.getOrFetchPoints(keyB)(f2)
      _            <- IO.sleep(2.millis)
      _            <- cache.getOrFetchPoints(keyC)(f3)
      _            <- cache.getOrFetchPoints(keyA)(f1)
      c1           <- cnt1
    } yield assertEquals(c1, 2)
  }

  test("InMemory: concurrent misses are single-flight (only one upstream call)") {
    for {
      cache         <- WeatherCache.inMemory(1.hour, 1.hour, 100, 100)
      p             <- countedFetch(samplePoints)
      (fetch, cnt)   = p
      key            = uniqueKey()
      _             <- List.fill(10)(cache.getOrFetchPoints(key)(fetch)).parSequence
      count         <- cnt
    } yield assertEquals(count, 1)
  }

  // ---------------------------------------------------------------------------
  // Redis cache suite (via Testcontainers)
  // ---------------------------------------------------------------------------

  test("Redis: points cache hit avoids re-fetching") {
    withRedisContainerOrSkip() { cache =>
      for {
        p             <- countedFetch(samplePoints)
        (fetch, cnt)   = p
        key            = uniqueKey()
        _             <- cache.getOrFetchPoints(key)(fetch)
        _             <- cache.getOrFetchPoints(key)(fetch)
        count         <- cnt
      } yield assertEquals(count, 1)
    }
  }

  test("Redis: forecast cache hit avoids re-fetching") {
    withRedisContainerOrSkip() { cache =>
      for {
        p             <- countedFetch(sampleForecast)
        (fetch, cnt)   = p
        key            = uniqueKey()
        _             <- cache.getOrFetchForecast(key)(fetch)
        r             <- cache.getOrFetchForecast(key)(fetch)
        count         <- cnt
      } yield {
        assertEquals(count, 1)
        assertEquals(r.periods.head.temperature, 72)
      }
    }
  }

  test("Redis: fetch errors are NOT cached - next call retries upstream") {
    withRedisContainerOrSkip() { cache =>
      for {
        failRef     <- Ref.of[IO, Int](0)
        successRef  <- Ref.of[IO, Int](0)
        key          = uniqueKey()
        fetchFail    = failRef.update(_ + 1) *> IO.raiseError[PointsResponse](new RuntimeException("boom"))
        fetchOk      = successRef.update(_ + 1).as(samplePoints)
        _           <- cache.getOrFetchPoints(key)(fetchFail).attempt
        _           <- cache.getOrFetchPoints(key)(fetchOk)
        fails       <- failRef.get
        successes   <- successRef.get
      } yield {
        assertEquals(fails,     1)
        assertEquals(successes, 1)
      }
    }
  }

  test("Redis: TTL expiry triggers fresh fetch") {
    withRedisContainerOrSkip(pointsTtl = 200.millis, forecastTtl = 200.millis) { cache =>
      for {
        p             <- countedFetch(samplePoints)
        (fetch, cnt)   = p
        key            = uniqueKey()
        _             <- cache.getOrFetchPoints(key)(fetch)
        _             <- IO.sleep(350.millis)
        _             <- cache.getOrFetchPoints(key)(fetch)
        count         <- cnt
      } yield assertEquals(count, 2)
    }
  }

  test("Redis: value survives a cache round-trip with correct content") {
    withRedisContainerOrSkip() { cache =>
      for {
        key <- IO.pure(uniqueKey())
        _   <- cache.getOrFetchForecast(key)(IO.pure(sampleForecast))
        r   <- cache.getOrFetchForecast(key)(IO.raiseError(new RuntimeException("should not be called")))
      } yield {
        assertEquals(r.periods.length, 2)
        assertEquals(r.periods.head.name, "Today")
        assertEquals(r.periods.head.temperature, 72)
      }
    }
  }
}

