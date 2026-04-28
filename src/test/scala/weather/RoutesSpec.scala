package weather

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.client.Client
import org.http4s.implicits._

class RoutesSpec extends CatsEffectSuite with WeatherServiceTestBase {

  // Override goodForecastJson to have only one period for route tests
  override protected val goodForecastJson: String =
    """|{
       |  "properties": {
       |    "periods": [
       |      {"name":"Today","temperature":72,"temperatureUnit":"F","shortForecast":"Sunny"}
       |    ]
       |  }
       |}""".stripMargin


  private def app(client: Client[IO]) =
    Routes.weatherRoutes(client).orNotFound

  // -----------------------------------------------------------------------
  // Success path
  // -----------------------------------------------------------------------

  test("GET /weather returns 200 with forecast JSON") {
    val req = Request[IO](Method.GET,
      Uri.unsafeFromString("/weather?lat=39.7456&lon=-97.0892"))
    app(mockClient()).run(req).flatMap { resp =>
      assertEquals(resp.status, Status.Ok)
      resp.as[String].map { body =>
        assert(body.contains("\"temperature\":72"), s"body was: $body")
        assert(body.contains("\"temperatureCategory\":\"moderate\""), s"body was: $body")
        assert(body.contains("\"shortForecast\":\"Sunny\""), s"body was: $body")
      }
    }
  }

  // -----------------------------------------------------------------------
  // Coordinate validation
  // -----------------------------------------------------------------------

  test("GET /weather returns 400 for lat out of range") {
    val req = Request[IO](Method.GET,
      Uri.unsafeFromString("/weather?lat=999.0&lon=0.0"))
    app(mockClient()).run(req).map { resp =>
      assertEquals(resp.status, Status.BadRequest)
    }
  }

  test("GET /weather returns 400 for lon out of range") {
    val req = Request[IO](Method.GET,
      Uri.unsafeFromString("/weather?lat=0.0&lon=999.0"))
    app(mockClient()).run(req).map { resp =>
      assertEquals(resp.status, Status.BadRequest)
    }
  }

  // -----------------------------------------------------------------------
  // NWS upstream errors
  // -----------------------------------------------------------------------

  test("GET /weather returns 404 when NWS /points returns 404") {
    val req = Request[IO](Method.GET,
      Uri.unsafeFromString("/weather?lat=39.7456&lon=-97.0892"))
    app(mockClient(pointsStatus = Status.NotFound, pointsBody = "not found"))
      .run(req).map { resp =>
        assertEquals(resp.status, Status.NotFound)
      }
  }

  test("GET /weather returns 503 when NWS is unavailable") {
    val req = Request[IO](Method.GET,
      Uri.unsafeFromString("/weather?lat=39.7456&lon=-97.0892"))
    app(mockClient(pointsStatus = Status.ServiceUnavailable, pointsBody = "down"))
      .run(req).map { resp =>
        assertEquals(resp.status, Status.ServiceUnavailable)
      }
  }

  test("GET /weather returns 502 when NWS /points returns non-404 4xx") {
    val req = Request[IO](Method.GET,
      Uri.unsafeFromString("/weather?lat=39.7456&lon=-97.0892"))
    app(mockClient(pointsStatus = Status.BadRequest, pointsBody = "bad req"))
      .run(req).map { resp =>
        assertEquals(resp.status, Status.BadGateway)
      }
  }

  test("GET /weather returns 502 when NWS forecast response is undecodable") {
    val req = Request[IO](Method.GET,
      Uri.unsafeFromString("/weather?lat=39.7456&lon=-97.0892"))
    app(mockClient(forecastBody = "garbage"))
      .run(req).map { resp =>
        assertEquals(resp.status, Status.BadGateway)
      }
  }

  test("GET /weather returns 502 when NWS returns zero forecast periods") {
    val emptyPeriods = """{"properties":{"periods":[]}}"""
    val req = Request[IO](Method.GET,
      Uri.unsafeFromString("/weather?lat=39.7456&lon=-97.0892"))
    app(mockClient(forecastBody = emptyPeriods))
      .run(req).map { resp =>
        assertEquals(resp.status, Status.BadGateway)
      }
  }

  // -----------------------------------------------------------------------
  // Missing / malformed query parameters
  // -----------------------------------------------------------------------

  test("GET /weather with no query params returns 400") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString("/weather"))
    app(mockClient()).run(req).map { resp =>
      assertEquals(resp.status, Status.BadRequest)
    }
  }

  test("GET /weather with only lat returns 400") {
    val req = Request[IO](Method.GET,
      Uri.unsafeFromString("/weather?lat=39.7456"))
    app(mockClient()).run(req).map { resp =>
      assertEquals(resp.status, Status.BadRequest)
    }
  }

  test("GET /weather error response body contains 'error' key") {
    val req = Request[IO](Method.GET,
      Uri.unsafeFromString("/weather?lat=999.0&lon=0.0"))
    app(mockClient()).run(req).flatMap { resp =>
      resp.as[String].map { body =>
        assert(body.contains("error"), s"body was: $body")
        assert(body.contains("Coordinates out of valid range"), s"body was: $body")
      }
    }
  }
}

