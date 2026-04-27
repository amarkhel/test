package weather

import cats.effect.IO
import org.http4s.{HttpApp, Response, Status}
import org.http4s.client.Client
import weather.Config.nwsBaseUrl

/** Shared test fixtures and mock client builders for weather service tests. */
trait WeatherServiceTestBase {

  protected val mockForecastUrl: String =
    s"$nwsBaseUrl/gridpoints/TOP/31,80/forecast"

  protected val goodPointsJson: String =
    s"""{"properties":{"forecast":"$mockForecastUrl"}}"""

  protected val goodForecastJson: String =
    """|{
       |  "properties": {
       |    "periods": [
       |      {"name":"Today","temperature":72,"temperatureUnit":"F","shortForecast":"Sunny"},
       |      {"name":"Tonight","temperature":55,"temperatureUnit":"F","shortForecast":"Clear"}
       |    ]
       |  }
       |}""".stripMargin

  protected val emptyPeriodsJson: String =
    """{"properties":{"periods":[]}}"""

  /** Build a mock Client that returns different status/body for the
    * /points/ call vs. the forecast URL call. */
  protected def mockClient(
      pointsStatus: Status   = Status.Ok,
      forecastStatus: Status = Status.Ok,
      pointsBody: String     = goodPointsJson,
      forecastBody: String   = goodForecastJson
  ): Client[IO] =
    Client.fromHttpApp(HttpApp[IO] { req =>
      val isPoints = req.uri.path.renderString.startsWith("/points/")
      val body     = if (isPoints) pointsBody   else forecastBody
      val status   = if (isPoints) pointsStatus else forecastStatus
      IO.pure(Response[IO](status).withEntity(body))
    })
}

