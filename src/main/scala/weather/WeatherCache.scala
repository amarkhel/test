package weather

import cats.effect.kernel.{Clock, Deferred, Ref}
import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

trait WeatherCache {
  def getOrFetchPoints(key: String)(fetch: IO[PointsResponse]): IO[PointsResponse]
  def getOrFetchForecast(key: String)(fetch: IO[ForecastResponse]): IO[ForecastResponse]
}

object WeatherCache {
  val noop: WeatherCache = new WeatherCache {
    override def getOrFetchPoints(key: String)(fetch: IO[PointsResponse]): IO[PointsResponse] = fetch
    override def getOrFetchForecast(key: String)(fetch: IO[ForecastResponse]): IO[ForecastResponse] = fetch
  }

  def inMemory(
      pointsTtl: FiniteDuration,
      forecastTtl: FiniteDuration,
      pointsMaxEntries: Int,
      forecastMaxEntries: Int
  ): IO[WeatherCache] =
    for {
      pointsCache <- InMemoryTtlSingleFlightCache.make[PointsResponse](pointsTtl, pointsMaxEntries)
      forecastCache <- InMemoryTtlSingleFlightCache.make[ForecastResponse](forecastTtl, forecastMaxEntries)
    } yield new WeatherCache {
      override def getOrFetchPoints(key: String)(fetch: IO[PointsResponse]): IO[PointsResponse] =
        pointsCache.getOrFetch(key)(fetch)

      override def getOrFetchForecast(key: String)(fetch: IO[ForecastResponse]): IO[ForecastResponse] =
        forecastCache.getOrFetch(key)(fetch)
    }

  private final case class CacheEntry[A](value: A, expiresAtMillis: Long, touchedAtMillis: Long)
  private final case class CacheState[A](
      values: Map[String, CacheEntry[A]],
      inFlight: Map[String, Deferred[IO, Either[Throwable, A]]]
  )

  private object InMemoryTtlSingleFlightCache {
    private sealed trait Decision[A]
    private final case class Hit[A](value: A) extends Decision[A]
    private final case class Wait[A](deferred: Deferred[IO, Either[Throwable, A]]) extends Decision[A]
    private final case class Lead[A](deferred: Deferred[IO, Either[Throwable, A]]) extends Decision[A]

    def make[A](ttl: FiniteDuration, maxEntries: Int): IO[InMemoryTtlSingleFlightCache[A]] =
      for {
        _ <- IO.raiseUnless(maxEntries > 0)(new IllegalArgumentException("maxEntries must be > 0"))
        state <- Ref.of[IO, CacheState[A]](CacheState(Map.empty, Map.empty))
      } yield new InMemoryTtlSingleFlightCache[A](ttl, maxEntries, state)
  }

  private final class InMemoryTtlSingleFlightCache[A](
      ttl: FiniteDuration,
      maxEntries: Int,
      stateRef: Ref[IO, CacheState[A]]
  ) {
    import InMemoryTtlSingleFlightCache._

    def getOrFetch(key: String)(fetch: IO[A]): IO[A] =
      for {
        now <- nowMillis
        gate <- Deferred[IO, Either[Throwable, A]]
        decision <- stateRef.modify { state =>
          val active = pruneExpired(state.values, now)
          active.get(key) match {
            case Some(entry) =>
              val refreshed = active.updated(key, entry.copy(touchedAtMillis = now))
              (state.copy(values = refreshed), Hit(entry.value): Decision[A])

            case None =>
              state.inFlight.get(key) match {
                case Some(existing) =>
                  (state.copy(values = active), Wait(existing): Decision[A])
                case None =>
                  val next = state.copy(values = active, inFlight = state.inFlight.updated(key, gate))
                  (next, Lead(gate): Decision[A])
              }
          }
        }
        result <- decision match {
          case Hit(value)    => IO.pure(value)
          case Wait(waitFor) => waitFor.get.rethrow
          case Lead(leaderGate) =>
            fetch.attempt.flatTap {
              case Right(value) =>
                for {
                  completedAt <- nowMillis
                  _ <- stateRef.update { state =>
                    val active = pruneExpired(state.values, completedAt)
                    val withEntry = active.updated(
                      key,
                      CacheEntry(
                        value = value,
                        expiresAtMillis = completedAt + ttl.toMillis,
                        touchedAtMillis = completedAt
                      )
                    )
                    val trimmed = trimToMaxEntries(withEntry)
                    state.copy(values = trimmed, inFlight = state.inFlight - key)
                  }
                  _ <- leaderGate.complete(Right(value)).void
                } yield ()

              case Left(error) =>
                stateRef.update(state => state.copy(inFlight = state.inFlight - key)) *>
                  leaderGate.complete(Left(error)).void
            }.rethrow
        }
      } yield result

    private def pruneExpired(values: Map[String, CacheEntry[A]], now: Long): Map[String, CacheEntry[A]] =
      values.filter { case (_, entry) => entry.expiresAtMillis > now }

    private def trimToMaxEntries(values: Map[String, CacheEntry[A]]): Map[String, CacheEntry[A]] = {
      if (values.size <= maxEntries) values
      else {
        val dropCount = values.size - maxEntries
        val keysToDrop = values.toList.sortBy(_._2.touchedAtMillis).take(dropCount).map(_._1)
        values -- keysToDrop
      }
    }

    private def nowMillis: IO[Long] =
      Clock[IO].realTime.map(_.toMillis)
  }
}


