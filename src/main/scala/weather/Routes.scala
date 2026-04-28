package weather

import cats.effect.IO
import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.dsl.io._

object Routes {

  object LatQueryParam extends QueryParamDecoderMatcher[Double]("lat")
  object LonQueryParam extends QueryParamDecoderMatcher[Double]("lon")

  private def errorJson(msg: String): Json =
    Json.obj("error" -> Json.fromString(msg))

  def weatherRoutes(client: Client[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] {

      // GET /weather?lat=39.7456&lon=-97.0892
      case GET -> Root / "weather" :? LatQueryParam(lat) +& LonQueryParam(lon) =>
        WeatherService.fetch(lat, lon, client)
          .flatMap {
            case Right(result)                        => Ok(result.asJson)
            case Left(e: WeatherError.InvalidCoordinates)  => BadRequest(errorJson(e.getMessage))
            case Left(e: WeatherError.UpstreamNotFound)    => NotFound(errorJson(e.getMessage))
            case Left(e: WeatherError.UpstreamClientError) => BadGateway(errorJson(e.getMessage))
            case Left(e: WeatherError.UpstreamUnavailable) => ServiceUnavailable(errorJson(e.getMessage))
            case Left(e: WeatherError.EmptyForecast)       => BadGateway(errorJson(e.getMessage))
            case Left(e: WeatherError.DecodeFailure)       => BadGateway(errorJson(e.getMessage))
          }
          .handleErrorWith(e => InternalServerError(errorJson(e.getMessage)))

      // Missing or malformed query params → 400
      case GET -> Root / "weather" =>
        BadRequest(Json.obj("error" -> Json.fromString("Required query parameters: lat (Double), lon (Double)")))
    }
}
