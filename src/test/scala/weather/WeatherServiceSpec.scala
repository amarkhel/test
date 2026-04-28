package weather

import cats.effect.IO
import cats.effect.Ref
import cats.implicits._
import munit.CatsEffectSuite
import org.http4s.{HttpApp, Response}
import org.http4s.client.Client
import org.http4s.Status

import scala.concurrent.duration._

class WeatherServiceSpec extends CatsEffectSuite with WeatherServiceTestBase {

  private def countingClient(
      pointsStatus: Status   = Status.Ok,
      forecastStatus: Status = Status.Ok,
      pointsBody: String     = goodPointsJson,
      forecastBody: String   = goodForecastJson
  ): IO[(Client[IO], IO[(Int, Int)])] =
    for {
      pointsCount <- Ref.of[IO, Int](0)
      forecastCount <- Ref.of[IO, Int](0)
      client = Client.fromHttpApp(HttpApp[IO] { req =>
        val isPoints = req.uri.path.renderString.startsWith("/points/")
        val body = if (isPoints) pointsBody else forecastBody
        val status = if (isPoints) pointsStatus else forecastStatus
        val countUpdate = if (isPoints) pointsCount.update(_ + 1) else forecastCount.update(_ + 1)
        countUpdate.as(Response[IO](status).withEntity(body))
      })
      counts = (pointsCount.get, forecastCount.get).mapN((_, _))
    } yield (client, counts)

  test("categorise: hot when temperature >= 85") {
    assertEquals(WeatherService.categorise(85), "hot")
    assertEquals(WeatherService.categorise(100), "hot")
    assertEquals(WeatherService.categorise(110), "hot")
  }

  test("categorise: cold when temperature <= 50") {
    assertEquals(WeatherService.categorise(50), "cold")
    assertEquals(WeatherService.categorise(32), "cold")
    assertEquals(WeatherService.categorise(0), "cold")
  }

  test("categorise: moderate for values strictly between 50 and 85") {
    assertEquals(WeatherService.categorise(51), "moderate")
    assertEquals(WeatherService.categorise(72), "moderate")
    assertEquals(WeatherService.categorise(84), "moderate")
  }

  // -----------------------------------------------------------------------
  // selectPeriod
  // -----------------------------------------------------------------------

  private val today   = ForecastPeriod("Today",   75, "F", "Sunny")
  private val tonight = ForecastPeriod("Tonight", 55, "F", "Clear")
  private val monday  = ForecastPeriod("Monday",  68, "F", "Cloudy")

  test("selectPeriod: picks 'Today' when present") {
    WeatherService.selectPeriod(List(tonight, today, monday))
      .map(p => assertEquals(p, today))
  }

  test("selectPeriod: comparison is case-insensitive") {
    val mixedCase = ForecastPeriod("TODAY", 80, "F", "Hot")
    WeatherService.selectPeriod(List(mixedCase, tonight))
      .map(p => assertEquals(p, mixedCase))
  }

  test("selectPeriod: falls back to first period when no 'Today' is found") {
    WeatherService.selectPeriod(List(tonight, monday))
      .map(p => assertEquals(p, tonight))
  }

  test("selectPeriod: raises EmptyForecast on an empty list") {
    interceptIO[WeatherError.EmptyForecast](
      WeatherService.selectPeriod(List.empty)
    )
  }

  // -----------------------------------------------------------------------
  // validateCoords
  // -----------------------------------------------------------------------

  test("validateCoords: accepts valid coordinates") {
    WeatherService.validateCoords(39.7456, -97.0892)
  }

  test("validateCoords: accepts boundary values") {
    WeatherService.validateCoords(90.0, 180.0) >>
      WeatherService.validateCoords(-90.0, -180.0)
  }

  test("validateCoords: rejects lat > 90") {
    interceptIO[WeatherError.InvalidCoordinates](
      WeatherService.validateCoords(91.0, 0.0)
    )
  }

  test("validateCoords: rejects lat < -90") {
    interceptIO[WeatherError.InvalidCoordinates](
      WeatherService.validateCoords(-91.0, 0.0)
    )
  }

  test("validateCoords: rejects lon > 180") {
    interceptIO[WeatherError.InvalidCoordinates](
      WeatherService.validateCoords(0.0, 181.0)
    )
  }

  test("validateCoords: rejects lon < -180") {
    interceptIO[WeatherError.InvalidCoordinates](
      WeatherService.validateCoords(0.0, -181.0)
    )
  }

  // -----------------------------------------------------------------------
  // fetch - mock Client
  // -----------------------------------------------------------------------


  test("fetch: returns WeatherResult on a successful round-trip") {
    WeatherService.fetch(39.7456, -97.0892, mockClient()).map { result =>
      assertEquals(result.temperature, 72)
      assertEquals(result.temperatureUnit, "F")
      assertEquals(result.shortForecast, "Sunny")
      assertEquals(result.temperatureCategory, "moderate")
      assert(result.location.contains("39.7456"))
    }
  }

  test("fetch: raises InvalidCoordinates for out-of-range lat") {
    interceptIO[WeatherError.InvalidCoordinates](
      WeatherService.fetch(200.0, 0.0, mockClient())
    )
  }

  test("fetch: raises UpstreamNotFound when NWS /points returns 404") {
    interceptIO[WeatherError.UpstreamNotFound](
      WeatherService.fetch(39.7456, -97.0892,
        mockClient(pointsStatus = Status.NotFound, pointsBody = "not found"))
    )
  }

  test("fetch: raises UpstreamUnavailable when NWS /points returns 503") {
    interceptIO[WeatherError.UpstreamUnavailable](
      WeatherService.fetch(39.7456, -97.0892,
        mockClient(pointsStatus = Status.ServiceUnavailable, pointsBody = "down"))
    )
  }

  test("fetch: raises UpstreamUnavailable when NWS forecast returns 500") {
    interceptIO[WeatherError.UpstreamUnavailable](
      WeatherService.fetch(39.7456, -97.0892,
        mockClient(forecastStatus = Status.InternalServerError, forecastBody = "oops"))
    )
  }

  test("fetch: raises DecodeFailure when /points body is not valid JSON") {
    interceptIO[WeatherError.DecodeFailure](
      WeatherService.fetch(39.7456, -97.0892,
        mockClient(pointsBody = "not-json"))
    )
  }

  test("fetch: raises DecodeFailure when forecast body is not valid JSON") {
    interceptIO[WeatherError.DecodeFailure](
      WeatherService.fetch(39.7456, -97.0892,
        mockClient(forecastBody = "not-json"))
    )
  }

  test("fetch: raises EmptyForecast when NWS returns zero periods") {
    interceptIO[WeatherError.EmptyForecast](
      WeatherService.fetch(39.7456, -97.0892,
        mockClient(forecastBody = emptyPeriodsJson))
    )
  }

  test("fetch: falls back to first period when no 'Today' period exists") {
    val onlyTonightJson =
      """|{"properties":{"periods":[
         |  {"name":"Tonight","temperature":55,"temperatureUnit":"F","shortForecast":"Clear"}
         |]}}""".stripMargin
    WeatherService.fetch(39.7456, -97.0892,
      mockClient(forecastBody = onlyTonightJson)).map { result =>
      assertEquals(result.shortForecast, "Clear")
      assertEquals(result.temperature, 55)
    }
  }

  test("fetch cache: repeated request for same location hits NWS only once per endpoint") {
    for {
      cache <- WeatherCache.inMemory(1.hour, 1.hour, 100, 100)
      pair <- countingClient()
      client = pair._1
      countsIO = pair._2
      _ <- WeatherService.fetch(39.7456, -97.0892, client, cache)
      _ <- WeatherService.fetch(39.7456, -97.0892, client, cache)
      counts <- countsIO
    } yield assertEquals(counts, (1, 1))
  }

  test("fetch cache: expired entry triggers a fresh upstream fetch") {
    for {
      cache <- WeatherCache.inMemory(25.millis, 25.millis, 100, 100)
      pair <- countingClient()
      client = pair._1
      countsIO = pair._2
      _ <- WeatherService.fetch(39.7456, -97.0892, client, cache)
      _ <- IO.sleep(60.millis)
      _ <- WeatherService.fetch(39.7456, -97.0892, client, cache)
      counts <- countsIO
    } yield assertEquals(counts, (2, 2))
  }

  test("fetch cache: concurrent misses are single-flight per cache key") {
    for {
      cache <- WeatherCache.inMemory(1.hour, 1.hour, 100, 100)
      pair <- countingClient()
      client = pair._1
      countsIO = pair._2
      _ <- List.fill(8)(WeatherService.fetch(39.7456, -97.0892, client, cache)).parSequence
      counts <- countsIO
    } yield assertEquals(counts, (1, 1))
  }
}

