package weather

import munit.CatsEffectSuite
import org.http4s.Status

class WeatherServiceSpec extends CatsEffectSuite with WeatherServiceTestBase {

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

  test("validateCoords: accepts Alaska and Puerto Rico coordinates") {
    WeatherService.validateCoords(61.2181, -149.9003) >>
      WeatherService.validateCoords(18.4655, -66.1057)
  }

  test("validateCoords: rejects global boundary values outside NWS coverage") {
    interceptIO[WeatherError.UnsupportedCoordinates](
      WeatherService.validateCoords(90.0, 180.0)
    ) >> interceptIO[WeatherError.UnsupportedCoordinates](
      WeatherService.validateCoords(-90.0, -180.0)
    )
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

  test("validateCoords: rejects coordinates outside NWS coverage") {
    interceptIO[WeatherError.UnsupportedCoordinates](
      WeatherService.validateCoords(51.5074, -0.1278)
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

  test("fetch: raises UnsupportedCoordinates for non-US coordinates") {
    interceptIO[WeatherError.UnsupportedCoordinates](
      WeatherService.fetch(51.5074, -0.1278, mockClient())
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
}

