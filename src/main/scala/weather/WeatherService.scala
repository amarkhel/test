package weather

import cats.effect.IO
import io.circe.Decoder
import io.circe.parser.decode
import org.http4s.client.Client
import org.http4s.{Request, Status, Uri}
import org.typelevel.ci.CIString
import weather.util.{CircuitBreaker, HttpHeaderParsers, NwsCoverageValidator, RetryPolicy, RetryUtils}

import scala.concurrent.duration._

/** Calls the NWS API in two steps:
  *   1. GET /points/{lat},{lon}  -> resolve grid + get forecast URL
  *   2. GET {forecastUrl}        -> get periods, find "Today"
  */
object WeatherService {

  private val retryAfterHeaderName: CIString = CIString("Retry-After")

  private val retryPolicy: RetryPolicy = RetryPolicy(
    maxAttempts = Config.retryMaxAttempts,
    initialDelay = Config.retryInitialDelay,
    backoffMultiplier = Config.retryBackoffMultiplier,
    maxDelay = Config.retryMaxDelay,
    jitterEnabled = Config.retryJitterEnabled,
    jitterRatio = Config.retryJitterRatio
  )

  private def isRetryable(error: Throwable): Boolean =
    error match {
      case _: WeatherError.UpstreamUnavailable => true
      case _: WeatherError.UpstreamRateLimited => Config.retryOnHttp429
      case _: java.io.IOException              => true
      case _                                   => false
    }

  private def retryDelayFrom(error: Throwable): Option[FiniteDuration] =
    error match {
      case WeatherError.UpstreamRateLimited(Some(d), _) => Some(d)
      case _                                            => None
    }


  /** Fail fast when coordinates are invalid or outside NWS geographic coverage. */
  def validateCoords(lat: Double, lon: Double): IO[Unit] =
    if (!NwsCoverageValidator.isGloballyValid(lat, lon))
      IO.raiseError(WeatherError.InvalidCoordinates(lat, lon))
    else if (!NwsCoverageValidator.isWithinNwsCoverage(lat, lon))
      IO.raiseError(WeatherError.UnsupportedCoordinates(lat, lon))
    else IO.unit

  /** Temperature buckets (Fahrenheit):
    *   cold     <= 50 F  (~10 C)
    *   hot      >= 85 F  (~29 C)
    *   moderate everything in between
    */
  def categorise(tempF: Int): String = tempF match {
    case t if t >= 85  => "hot"
    case t if t <= 50  => "cold"
    case _             => "moderate"
  }

  /** Return the "Today" period when found, otherwise the first period.
    * Raises [[WeatherError.EmptyForecast]] instead of throwing on an empty list.
    */
  def selectPeriod(periods: List[ForecastPeriod]): IO[ForecastPeriod] =
    periods
      .find(_.name.equalsIgnoreCase("today"))
      .orElse(periods.headOption)
      .fold(IO.raiseError[ForecastPeriod](WeatherError.EmptyForecast()))(IO.pure)

  /** Fetch a URL and map NWS HTTP error statuses to typed [[WeatherError]] values. */
  private def fetchBody(uri: Uri, client: Client[IO]): IO[String] =
    client.run(Request[IO](uri = uri)).use { resp =>
      resp.status match {
        case Status.Ok =>
          resp.as[String]

        case Status.NotFound =>
          resp.as[String].flatMap { body =>
            IO.raiseError(WeatherError.UpstreamNotFound(body.take(300)))
          }

        case Status.TooManyRequests =>
          resp.as[String].flatMap { body =>
            val retryAfter = resp.headers.headers
              .find(_.name == retryAfterHeaderName)
              .flatMap(h => HttpHeaderParsers.parseRetryAfter(h.value))
            IO.raiseError(WeatherError.UpstreamRateLimited(retryAfter, body.take(300)))
          }

        case s if s.code >= 500 =>
          IO.raiseError(WeatherError.UpstreamUnavailable(s"NWS returned HTTP ${s.code}"))

        case s =>
          resp.as[String].flatMap { body =>
            IO.raiseError(WeatherError.UpstreamUnavailable(
              s"Unexpected HTTP ${s.code}: ${body.take(300)}"
            ))
          }
      }
    }

  private def parseUri(kind: String, raw: String): IO[Uri] =
    IO.fromEither(
      Uri.fromString(raw)
        .left.map(e => WeatherError.DecodeFailure(s"Invalid $kind URI: ${e.getMessage}"))
    )

  private def decodeJson[A: Decoder](label: String, body: String): IO[A] =
    IO.fromEither(
      decode[A](body)
        .left.map(e => WeatherError.DecodeFailure(s"$label: ${e.getMessage}"))
    )

  private def fetchPage[A: Decoder](
      uriKind: String,
      rawUri: String,
      decodeLabel: String,
      client: Client[IO],
      circuitBreaker: Option[CircuitBreaker] = None
  ): IO[A] =
    for {
      uri  <- parseUri(uriKind, rawUri)
      body <- {
                val fetching = RetryUtils.withRetry(
                  effect = fetchBody(uri, client),
                  policy = retryPolicy,
                  isRetryable = isRetryable,
                  retryDelayFromError = retryDelayFrom
                )
                circuitBreaker.fold(fetching)(_.protect(fetching))
              }
      data <- decodeJson[A](decodeLabel, body)
    } yield data

  private def formatCoord(value: Double, scale: Int = 4): String =
    BigDecimal(value).setScale(scale, BigDecimal.RoundingMode.HALF_UP).toString

  def fetch(
      lat: Double,
      lon: Double,
      client: Client[IO],
      circuitBreaker: Option[CircuitBreaker] = None
  ): IO[WeatherResult] = {
    val latStr = formatCoord(lat)
    val lonStr = formatCoord(lon)

    for {
      // Guard: validate coordinate ranges before any network call
      _           <- validateCoords(lat, lon)

      // Step 1: resolve coordinates to a forecast grid URL
      pointsResp  <- fetchPage[PointsResponse](
                       uriKind = "points",
                       rawUri = s"${Config.nwsBaseUrl}/points/$latStr,$lonStr",
                       decodeLabel = "Points response",
                       client = client,
                       circuitBreaker = circuitBreaker
                     )

      // Step 2: fetch the forecast periods
      forecastResp <- fetchPage[ForecastResponse](
                        uriKind = "forecast",
                        rawUri = pointsResp.forecastUrl,
                        decodeLabel = "Forecast response",
                        client = client,
                        circuitBreaker = circuitBreaker
                      )

      // Find "Today"; fall back to the first period (e.g. "Tonight" after 6 PM)
      period       <- selectPeriod(forecastResp.periods)

    } yield WeatherResult(
      location            = s"$latStr, $lonStr",
      shortForecast       = period.shortForecast,
      temperature         = period.temperature,
      temperatureUnit     = period.temperatureUnit,
      temperatureCategory = categorise(period.temperature)
    )
  }
}
