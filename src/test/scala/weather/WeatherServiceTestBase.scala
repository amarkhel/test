package weather

import cats.effect.IO
import cats.effect.Ref
import org.http4s.Header
import org.http4s.{Headers, HttpApp, Response, Status}
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

  /** Build a mock Client that can return a sequence of responses per endpoint.
    * Returns the client plus a counter IO (pointsCalls, forecastCalls). */
  protected def sequenceClient(
      pointsResponses: List[(Status, String)],
      forecastResponses: List[(Status, String)]
  ): IO[(Client[IO], IO[(Int, Int)])] =
    for {
      pointsCalls <- Ref.of[IO, Int](0)
      forecastCalls <- Ref.of[IO, Int](0)
      client = Client.fromHttpApp(HttpApp[IO] { req =>
        val isPoints = req.uri.path.renderString.startsWith("/points/")
        if (isPoints)
          pointsCalls.modify { calls =>
            val idx = calls
            val fallback = (Status.Ok, goodPointsJson)
            val pair = pointsResponses.lift(idx)
              .orElse(pointsResponses.lastOption)
              .getOrElse(fallback)
            (calls + 1, pair)
          }.map { case (status, body) => Response[IO](status).withEntity(body) }
        else
          forecastCalls.modify { calls =>
            val idx = calls
            val fallback = (Status.Ok, goodForecastJson)
            val pair = forecastResponses.lift(idx)
              .orElse(forecastResponses.lastOption)
              .getOrElse(fallback)
            (calls + 1, pair)
          }.map { case (status, body) => Response[IO](status).withEntity(body) }
      })
      counter = for {
        p <- pointsCalls.get
        f <- forecastCalls.get
      } yield (p, f)
    } yield (client, counter)

  protected def sequenceClientWithHeaders(
      pointsResponses: List[(Status, String, List[Header.Raw])],
      forecastResponses: List[(Status, String, List[Header.Raw])]
  ): IO[(Client[IO], IO[(Int, Int)])] =
    for {
      pointsCalls <- Ref.of[IO, Int](0)
      forecastCalls <- Ref.of[IO, Int](0)
      client = Client.fromHttpApp(HttpApp[IO] { req =>
        val isPoints = req.uri.path.renderString.startsWith("/points/")
        if (isPoints)
          pointsCalls.modify { calls =>
            val idx = calls
            val fallback = (Status.Ok, goodPointsJson, List.empty[Header.Raw])
            val triple = pointsResponses.lift(idx)
              .orElse(pointsResponses.lastOption)
              .getOrElse(fallback)
            (calls + 1, triple)
          }.map { case (status, body, headers) =>
            Response[IO](status)
              .withHeaders(Headers(headers))
              .withEntity(body)
          }
        else
          forecastCalls.modify { calls =>
            val idx = calls
            val fallback = (Status.Ok, goodForecastJson, List.empty[Header.Raw])
            val triple = forecastResponses.lift(idx)
              .orElse(forecastResponses.lastOption)
              .getOrElse(fallback)
            (calls + 1, triple)
          }.map { case (status, body, headers) =>
            Response[IO](status)
              .withHeaders(Headers(headers))
              .withEntity(body)
          }
      })
      counter = for {
        p <- pointsCalls.get
        f <- forecastCalls.get
      } yield (p, f)
    } yield (client, counter)
}

