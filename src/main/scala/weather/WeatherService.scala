package weather

import cats.data.EitherT
import cats.effect.IO
import io.circe.Decoder
import io.circe.parser.decode
import org.http4s.client.Client
import org.http4s.{Request, Status, Uri}

/** Calls the NWS API in two steps:
  *   1. GET /points/{lat},{lon}  -> resolve grid + get forecast URL
  *   2. GET {forecastUrl}        -> get periods, find "Today"
  */
object WeatherService {

  private type AppResult[A] = EitherT[IO, WeatherError, A]

  private def fromEither[A](result: Either[WeatherError, A]): AppResult[A] =
    EitherT.fromEither[IO](result)

  private def fromIOEither[A](result: IO[Either[WeatherError, A]]): AppResult[A] =
    EitherT(result)

  /** Fail fast if coordinates are outside the valid global ranges. */
  def validateCoords(lat: Double, lon: Double): Either[WeatherError, Unit] =
    if (lat < -90 || lat > 90 || lon < -180 || lon > 180)
      Left(WeatherError.InvalidCoordinates(lat, lon))
    else Right(())

  /** Temperature buckets (Fahrenheit):
    *   cold     <= 50 F  (~10 C)
    *   hot      >= 85 F  (~29 C)
    *   moderate everything in between
    */
  def categorise(tempF: Int): TemperatureCategory = tempF match {
    case t if t >= 85  => TemperatureCategory.Hot
    case t if t <= 50  => TemperatureCategory.Cold
    case _             => TemperatureCategory.Moderate
  }

  /** Return the "Today" period when found, otherwise the first period. */
  def selectPeriod(periods: List[ForecastPeriod]): Either[WeatherError, ForecastPeriod] =
    periods
      .find(_.name.equalsIgnoreCase("today"))
      .orElse(periods.headOption)
      .toRight(WeatherError.EmptyForecast())

  /** Fetch a URL and map NWS HTTP error statuses to typed [[WeatherError]] values. */
  private def fetchBody(uri: Uri, client: Client[IO]): IO[Either[WeatherError, String]] =
    client.run(Request[IO](uri = uri)).use { resp =>
      resp.status match {
        case Status.Ok =>
          resp.as[String].map(Right(_))

        case Status.NotFound =>
          resp.as[String].map(body => Left(WeatherError.UpstreamNotFound(body.take(300))))

        case s if s.code >= 400 && s.code < 500 =>
          resp.as[String].map { body =>
            Left(WeatherError.UpstreamClientError(s"HTTP ${s.code}: ${body.take(300)}"))
          }

        case s if s.code >= 500 =>
          IO.pure(Left(WeatherError.UpstreamUnavailable(s"NWS returned HTTP ${s.code}")))

        case s =>
          resp.as[String].map { body =>
            Left(WeatherError.UpstreamUnavailable(s"Unexpected HTTP ${s.code}: ${body.take(300)}"))
          }
      }
    }.handleError(e => Left(WeatherError.UpstreamUnavailable(e.getMessage)))

  private def parseUri(kind: String, raw: String): Either[WeatherError, Uri] =
      Uri.fromString(raw)
        .left.map(e => WeatherError.DecodeFailure(s"Invalid $kind URI: ${e.getMessage}"))

  private def decodeJson[A: Decoder](label: String, body: String): Either[WeatherError, A] =
      decode[A](body)
        .left.map(e => WeatherError.DecodeFailure(s"$label: ${e.getMessage}"))

  private def fetchPage[A: Decoder](
      uriKind: String,
      rawUri: String,
      decodeLabel: String,
      client: Client[IO]
  ): AppResult[A] =
    for {
      uri  <- fromEither(parseUri(uriKind, rawUri))
      body <- fromIOEither(fetchBody(uri, client))
      data <- fromEither(decodeJson[A](decodeLabel, body))
    } yield data

  private def formatCoord(value: Double, scale: Int = 4): String =
    BigDecimal(value).setScale(scale, BigDecimal.RoundingMode.HALF_UP).toString

  def fetch(lat: Double, lon: Double, client: Client[IO]): IO[Either[WeatherError, WeatherResult]] = {
    val latStr = formatCoord(lat)
    val lonStr = formatCoord(lon)

    (for {
      // Guard: validate coordinate ranges before any network call
      _           <- fromEither(validateCoords(lat, lon))

      // Step 1: resolve coordinates to a forecast grid URL
      pointsResp  <- fetchPage[PointsResponse](
                       uriKind = "points",
                       rawUri = s"${Config.nwsBaseUrl}/points/$latStr,$lonStr",
                       decodeLabel = "Points response",
                       client = client
                     )

      // Step 2: fetch the forecast periods
      forecastResp <- fetchPage[ForecastResponse](
                        uriKind = "forecast",
                        rawUri = pointsResp.forecastUrl,
                        decodeLabel = "Forecast response",
                        client = client
                      )

      // Find "Today"; fall back to the first period (e.g. "Tonight" after 6 PM)
      period       <- fromEither(selectPeriod(forecastResp.periods))

    } yield WeatherResult(
      location            = s"$latStr, $lonStr",
      shortForecast       = period.shortForecast,
      temperature         = period.temperature,
      temperatureUnit     = period.temperatureUnit,
      temperatureCategory = categorise(period.temperature)
    )).value
  }
}
