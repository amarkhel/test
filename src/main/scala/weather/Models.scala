package weather

import io.circe.{Decoder, Encoder, HCursor}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

import scala.concurrent.duration.FiniteDuration

case class PointsResponse(forecastUrl: String)

object PointsResponse {
  // NWS wire-format decoder (reads nested "properties.forecast")
  implicit val decoder: Decoder[PointsResponse] = (c: HCursor) =>
    c.downField("properties").downField("forecast").as[String].map(PointsResponse(_))

  // Flat codecs used for cache storage: {"forecastUrl":"..."}
  val cacheEncoder: Encoder[PointsResponse] = deriveEncoder[PointsResponse]
  val cacheDecoder: Decoder[PointsResponse] = deriveDecoder[PointsResponse]
}

case class ForecastPeriod(
  name:            String,
  temperature:     Int,
  temperatureUnit: String,
  shortForecast:   String
)

object ForecastPeriod {
  // NWS wire-format decoder
  implicit val decoder: Decoder[ForecastPeriod] = (c: HCursor) =>
    for {
      name  <- c.downField("name").as[String]
      temp  <- c.downField("temperature").as[Int]
      unit  <- c.downField("temperatureUnit").as[String]
      short <- c.downField("shortForecast").as[String]
    } yield ForecastPeriod(name, temp, unit, short)

  // Flat codecs for cache storage
  val cacheEncoder: Encoder[ForecastPeriod] = deriveEncoder[ForecastPeriod]
  val cacheDecoder: Decoder[ForecastPeriod] = deriveDecoder[ForecastPeriod]
}

case class ForecastResponse(periods: List[ForecastPeriod])

object ForecastResponse {
  // NWS wire-format decoder
  implicit val decoder: Decoder[ForecastResponse] = (c: HCursor) =>
    c.downField("properties").downField("periods")
      .as[List[ForecastPeriod]]
      .map(ForecastResponse(_))

  // Flat codecs for cache storage: {"periods":[...]}
  val cacheEncoder: Encoder[ForecastResponse] = {
    implicit val pe: Encoder[ForecastPeriod] = ForecastPeriod.cacheEncoder
    deriveEncoder[ForecastResponse]
  }
  val cacheDecoder: Decoder[ForecastResponse] = {
    implicit val pd: Decoder[ForecastPeriod] = ForecastPeriod.cacheDecoder
    deriveDecoder[ForecastResponse]
  }
}

// ---------------------------------------------------------------------------
// Typed error hierarchy - each subtype maps to a distinct HTTP status in Routes
// ---------------------------------------------------------------------------
sealed abstract class WeatherError(message: String) extends Exception(message)

object WeatherError {
  /** lat/lon outside valid global ranges; return 400. */
  final case class InvalidCoordinates(lat: Double, lon: Double)
      extends WeatherError(s"Coordinates out of valid range: lat=$lat, lon=$lon")

  /** lat/lon are globally valid but outside NWS geographic coverage; return 400. */
  final case class UnsupportedCoordinates(lat: Double, lon: Double)
      extends WeatherError(s"Coordinates are outside NWS coverage area: lat=$lat, lon=$lon")

  /** NWS returned 404 (location not covered); return 404. */
  final case class UpstreamNotFound(details: String)
      extends WeatherError(s"NWS does not cover these coordinates: $details")

  /** NWS returned 5xx or an unexpected status; return 503. */
  final case class UpstreamUnavailable(details: String)
      extends WeatherError(s"NWS API unavailable: $details")

  /** NWS returned 429 Too Many Requests; return 429. */
  final case class UpstreamRateLimited(retryAfter: Option[FiniteDuration], details: String)
      extends WeatherError(
        retryAfter.fold(s"NWS rate-limited this request: $details") { d =>
          s"NWS rate-limited this request. Retry after ${d.toSeconds}s: $details"
        }
      )

  /** NWS returned a valid response but with no forecast periods; return 502. */
  final case class EmptyForecast()
      extends WeatherError("NWS returned no forecast periods")

  /** JSON from NWS could not be decoded into the expected model; return 502. */
  final case class DecodeFailure(details: String)
      extends WeatherError(s"Failed to parse NWS response: $details")
}

// ---------------------------------------------------------------------------
// The JSON we return to the caller
case class WeatherResult(
  location:            String,
  shortForecast:       String,
  temperature:         Int,
  temperatureUnit:     String,
  temperatureCategory: String  // "hot" | "moderate" | "cold"
)

object WeatherResult {
  import io.circe.generic.semiauto._
  import io.circe.Encoder
  implicit val encoder: Encoder[WeatherResult] = deriveEncoder
}
