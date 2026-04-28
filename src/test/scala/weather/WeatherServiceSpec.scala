package weather

import munit.CatsEffectSuite
import org.http4s.Status

class WeatherServiceSpec extends CatsEffectSuite with WeatherServiceTestBase {

  test("categorise: hot when temperature >= 85") {
    assertEquals(WeatherService.categorise(85), TemperatureCategory.Hot)
    assertEquals(WeatherService.categorise(100), TemperatureCategory.Hot)
    assertEquals(WeatherService.categorise(110), TemperatureCategory.Hot)
  }

  test("categorise: cold when temperature <= 50") {
    assertEquals(WeatherService.categorise(50), TemperatureCategory.Cold)
    assertEquals(WeatherService.categorise(32), TemperatureCategory.Cold)
    assertEquals(WeatherService.categorise(0), TemperatureCategory.Cold)
  }

  test("categorise: moderate for values strictly between 50 and 85") {
    assertEquals(WeatherService.categorise(51), TemperatureCategory.Moderate)
    assertEquals(WeatherService.categorise(72), TemperatureCategory.Moderate)
    assertEquals(WeatherService.categorise(84), TemperatureCategory.Moderate)
  }

  // -----------------------------------------------------------------------
  // selectPeriod
  // -----------------------------------------------------------------------

  private val today   = ForecastPeriod("Today",   75, "F", "Sunny")
  private val tonight = ForecastPeriod("Tonight", 55, "F", "Clear")
  private val monday  = ForecastPeriod("Monday",  68, "F", "Cloudy")

  test("selectPeriod: picks 'Today' when present") {
    assertEquals(WeatherService.selectPeriod(List(tonight, today, monday)), Right(today))
  }

  test("selectPeriod: comparison is case-insensitive") {
    val mixedCase = ForecastPeriod("TODAY", 80, "F", "Hot")
    assertEquals(WeatherService.selectPeriod(List(mixedCase, tonight)), Right(mixedCase))
  }

  test("selectPeriod: falls back to first period when no 'Today' is found") {
    assertEquals(WeatherService.selectPeriod(List(tonight, monday)), Right(tonight))
  }

  test("selectPeriod: raises EmptyForecast on an empty list") {
    assertEquals(WeatherService.selectPeriod(List.empty), Left(WeatherError.EmptyForecast()))
  }

  // -----------------------------------------------------------------------
  // validateCoords
  // -----------------------------------------------------------------------

  test("validateCoords: accepts valid coordinates") {
    assertEquals(WeatherService.validateCoords(39.7456, -97.0892), Right(()))
  }

  test("validateCoords: accepts boundary values") {
    assertEquals(WeatherService.validateCoords(90.0, 180.0), Right(()))
    assertEquals(WeatherService.validateCoords(-90.0, -180.0), Right(()))
  }

  test("validateCoords: rejects lat > 90") {
    assertEquals(
      WeatherService.validateCoords(91.0, 0.0),
      Left(WeatherError.InvalidCoordinates(91.0, 0.0))
    )
  }

  test("validateCoords: rejects lat < -90") {
    assertEquals(
      WeatherService.validateCoords(-91.0, 0.0),
      Left(WeatherError.InvalidCoordinates(-91.0, 0.0))
    )
  }

  test("validateCoords: rejects lon > 180") {
    assertEquals(
      WeatherService.validateCoords(0.0, 181.0),
      Left(WeatherError.InvalidCoordinates(0.0, 181.0))
    )
  }

  test("validateCoords: rejects lon < -180") {
    assertEquals(
      WeatherService.validateCoords(0.0, -181.0),
      Left(WeatherError.InvalidCoordinates(0.0, -181.0))
    )
  }

  // -----------------------------------------------------------------------
  // fetch - mock Client
  // -----------------------------------------------------------------------


  test("fetch: returns WeatherResult on a successful round-trip") {
    WeatherService.fetch(39.7456, -97.0892, mockClient()).map { result =>
      result match {
        case Right(value) =>
          assertEquals(value.temperature, 72)
          assertEquals(value.temperatureUnit, "F")
          assertEquals(value.shortForecast, "Sunny")
          assertEquals(value.temperatureCategory, TemperatureCategory.Moderate)
          assert(value.location.contains("39.7456"))
        case Left(err) =>
          fail(s"Expected Right, got Left($err)")
      }
    }
  }

  test("fetch: returns Left(InvalidCoordinates) for out-of-range lat") {
    WeatherService.fetch(200.0, 0.0, mockClient()).map { result =>
      assertEquals(result, Left(WeatherError.InvalidCoordinates(200.0, 0.0)))
    }
  }

  test("fetch: returns Left(UpstreamNotFound) when NWS /points returns 404") {
    WeatherService.fetch(
      39.7456,
      -97.0892,
      mockClient(pointsStatus = Status.NotFound, pointsBody = "not found")
    ).map { result =>
      assert(result.isLeft)
      assert(result.left.exists(_.isInstanceOf[WeatherError.UpstreamNotFound]))
    }
  }

  test("fetch: returns Left(UpstreamUnavailable) when NWS /points returns 503") {
    WeatherService.fetch(
      39.7456,
      -97.0892,
      mockClient(pointsStatus = Status.ServiceUnavailable, pointsBody = "down")
    ).map { result =>
      assert(result.isLeft)
      assert(result.left.exists(_.isInstanceOf[WeatherError.UpstreamUnavailable]))
    }
  }

  test("fetch: returns Left(UpstreamClientError) when NWS /points returns 400") {
    WeatherService.fetch(
      39.7456,
      -97.0892,
      mockClient(pointsStatus = Status.BadRequest, pointsBody = "bad req")
    ).map { result =>
      assert(result.isLeft)
      assert(result.left.exists(_.isInstanceOf[WeatherError.UpstreamClientError]))
    }
  }

  test("fetch: returns Left(UpstreamUnavailable) when NWS forecast returns 500") {
    WeatherService.fetch(
      39.7456,
      -97.0892,
      mockClient(forecastStatus = Status.InternalServerError, forecastBody = "oops")
    ).map { result =>
      assert(result.isLeft)
      assert(result.left.exists(_.isInstanceOf[WeatherError.UpstreamUnavailable]))
    }
  }

  test("fetch: returns Left(DecodeFailure) when /points body is not valid JSON") {
    WeatherService.fetch(39.7456, -97.0892, mockClient(pointsBody = "not-json")).map { result =>
      assert(result.isLeft)
      assert(result.left.exists(_.isInstanceOf[WeatherError.DecodeFailure]))
    }
  }

  test("fetch: returns Left(DecodeFailure) when forecast body is not valid JSON") {
    WeatherService.fetch(39.7456, -97.0892, mockClient(forecastBody = "not-json")).map { result =>
      assert(result.isLeft)
      assert(result.left.exists(_.isInstanceOf[WeatherError.DecodeFailure]))
    }
  }

  test("fetch: returns Left(EmptyForecast) when NWS returns zero periods") {
    WeatherService.fetch(39.7456, -97.0892, mockClient(forecastBody = emptyPeriodsJson)).map { result =>
      assert(result.isLeft)
      assert(result.left.exists(_.isInstanceOf[WeatherError.EmptyForecast]))
    }
  }

  test("fetch: falls back to first period when no 'Today' period exists") {
    val onlyTonightJson =
      """|{"properties":{"periods":[
         |  {"name":"Tonight","temperature":55,"temperatureUnit":"F","shortForecast":"Clear"}
         |]}}""".stripMargin
    WeatherService.fetch(39.7456, -97.0892,
      mockClient(forecastBody = onlyTonightJson)).map { result =>
      result match {
        case Right(value) =>
          assertEquals(value.shortForecast, "Clear")
          assertEquals(value.temperature, 55)
        case Left(err) =>
          fail(s"Expected Right, got Left($err)")
      }
    }
  }
}

