package weather

import cats.effect.{IO, Resource}
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.log4cats._         // bridges Logger[IO] → Log[IO]
import io.circe.{Decoder, Encoder}
import io.circe.parser.decode
import io.circe.syntax._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.FiniteDuration

/** A [[WeatherCache]] backed by Redis.
  *
  * Values are serialised as compact JSON strings via the models' cache codecs.
  * Keys are namespaced with a prefix to avoid collisions with other apps.
  *
  * Note: this implementation does NOT include local single-flight coalescing.
  * Concurrent cache misses from the same JVM may each fan out to the upstream.
  * If thundering-herd protection within a single instance is required, layer
  * [[WeatherCache.inMemory]] in front of the Redis cache (L1 → L2).
  */
object RedisWeatherCache {

  private val pointsPrefix   = "weather:points:"
  private val forecastPrefix = "weather:forecast:"

  def resource(
      redisUri:    String,
      pointsTtl:   FiniteDuration,
      forecastTtl: FiniteDuration
  ): Resource[IO, WeatherCache] = {
    // Provide an implicit log4cats Logger so that redis4cats can log internally
    implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

    Redis[IO].utf8(redisUri).map { cmd =>
      new WeatherCache {

        def getOrFetchPoints(key: String)(fetch: IO[PointsResponse]): IO[PointsResponse] =
          getOrStore(pointsPrefix + key, fetch, pointsTtl)(
            PointsResponse.cacheEncoder,
            PointsResponse.cacheDecoder
          )

        def getOrFetchForecast(key: String)(fetch: IO[ForecastResponse]): IO[ForecastResponse] =
          getOrStore(forecastPrefix + key, fetch, forecastTtl)(
            ForecastResponse.cacheEncoder,
            ForecastResponse.cacheDecoder
          )

        private def getOrStore[A](
            redisKey: String,
            fetch:    IO[A],
            ttl:      FiniteDuration
        )(implicit enc: Encoder[A], dec: Decoder[A]): IO[A] =
          cmd.get(redisKey).flatMap {

            case Some(json) =>
              // Cache hit — deserialise and return
              IO.fromEither(
                decode[A](json)
                  .left.map(e => new RuntimeException(
                    s"Redis cache decode error for key '$redisKey': ${e.getMessage}"))
              )

            case None =>
              // Cache miss — fetch upstream then store the result
              fetch.flatTap { value =>
                cmd.setEx(redisKey, value.asJson(enc).noSpaces, ttl)
                  .handleErrorWith { e =>
                    // Non-fatal: if the write fails, just warn and continue
                    IO.println(s"[WARN] Failed to write cache key '$redisKey': ${e.getMessage}")
                  }
              }
          }
      }
    }
  }
}
