package weather

import io.circe.{Decoder, Encoder, HCursor}

case class PointsResponse(forecastUrl: String)

object PointsResponse {
  implicit val decoder: Decoder[PointsResponse] = (c: HCursor) =>
    c.downField("properties").downField("forecast").as[String].map(PointsResponse(_))
}

case class ForecastPeriod(
  name:            String,  // "Today", "Tonight", "Monday", ...
  temperature:     Int,
  temperatureUnit: String,  // "F" or "C"
  shortForecast:   String
)

object ForecastPeriod {
  implicit val decoder: Decoder[ForecastPeriod] = (c: HCursor) =>
    for {
      name  <- c.downField("name").as[String]
      temp  <- c.downField("temperature").as[Int]
      unit  <- c.downField("temperatureUnit").as[String]
      short <- c.downField("shortForecast").as[String]
    } yield ForecastPeriod(name, temp, unit, short)
}

case class ForecastResponse(periods: List[ForecastPeriod])

object ForecastResponse {
  implicit val decoder: Decoder[ForecastResponse] = (c: HCursor) =>
    c.downField("properties").downField("periods")
      .as[List[ForecastPeriod]]
      .map(ForecastResponse(_))
}

// ---------------------------------------------------------------------------
// Typed error hierarchy - each subtype maps to a distinct HTTP status in Routes
// ---------------------------------------------------------------------------
sealed abstract class WeatherError(message: String) extends Exception(message)

object WeatherError {
  /** lat/lon outside valid global ranges; return 400. */
  final case class InvalidCoordinates(lat: Double, lon: Double)
      extends WeatherError(s"Coordinates out of valid range: lat=$lat, lon=$lon")

  /** NWS returned 404 (location not covered); return 404. */
  final case class UpstreamNotFound(details: String)
      extends WeatherError(s"NWS does not cover these coordinates: $details")

  /** NWS returned a non-404 4xx response; return 502. */
  final case class UpstreamClientError(details: String)
      extends WeatherError(s"NWS rejected request: $details")

  /** NWS returned 5xx or an unexpected status; return 503. */
  final case class UpstreamUnavailable(details: String)
      extends WeatherError(s"NWS API unavailable: $details")

  /** NWS returned a valid response but with no forecast periods; return 502. */
  final case class EmptyForecast()
      extends WeatherError("NWS returned no forecast periods")

  /** JSON from NWS could not be decoded into the expected model; return 502. */
  final case class DecodeFailure(details: String)
      extends WeatherError(s"Failed to parse NWS response: $details")
}

// ---------------------------------------------------------------------------
// The JSON we return to the caller
sealed trait TemperatureCategory {
  def value: String
}

object TemperatureCategory {
  case object Hot extends TemperatureCategory {
    val value: String = "hot"
  }

  case object Moderate extends TemperatureCategory {
    val value: String = "moderate"
  }

  case object Cold extends TemperatureCategory {
    val value: String = "cold"
  }

  implicit val encoder: Encoder[TemperatureCategory] =
    Encoder.encodeString.contramap(_.value)
}

case class WeatherResult(
  location:            String,
  shortForecast:       String,
  temperature:         Int,
  temperatureUnit:     String,
  temperatureCategory: TemperatureCategory
)

object WeatherResult {
  import io.circe.generic.semiauto._
  implicit val encoder: Encoder[WeatherResult] = deriveEncoder
}
