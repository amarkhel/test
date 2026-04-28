package weather.util

import cats.effect.{IO, Ref}

/**
 * Configuration for a token-bucket rate limiter.
 *
 * @param requestsPerSecond continuous token refill rate
 * @param burstSize         maximum token capacity (max burst)
 */
final case class RateLimiterConfig(
    requestsPerSecond: Double,
    burstSize: Int
)

/**
 * Generic, concurrency-safe token-bucket rate limiter keyed by [[K]].
 *
 * State per key: (available tokens: Double, last-refill timestamp ms: Long).
 * All updates are atomic via a single [[Ref]].
 */
final class RateLimiter[K] private (
    config: RateLimiterConfig,
    buckets: Ref[IO, Map[K, (Double, Long)]]
) {
  /**
   * Try to consume one token for [[key]].
   *
   * @return `true`  – token consumed, request allowed
   *         `false` – bucket empty, request should be rejected
   */
  def acquire(key: K): IO[Boolean] =
    IO.realTimeInstant.flatMap { now =>
      val nowMs = now.toEpochMilli
      buckets.modify { map =>
        val (tokens, lastMs) = map.getOrElse(key, (config.burstSize.toDouble, nowMs))
        val elapsedSecs = math.max(0L, nowMs - lastMs).toDouble / 1000.0
        val refilled = math.min(config.burstSize.toDouble,
                                tokens + elapsedSecs * config.requestsPerSecond)
        if (refilled >= 1.0)
          (map.updated(key, (refilled - 1.0, nowMs)), true)
        else
          (map.updated(key, (refilled, nowMs)), false)
      }
    }

  /** Current token count for a key (for testing / observability). */
  def tokens(key: K): IO[Double] =
    IO.realTimeInstant.flatMap { now =>
      val nowMs = now.toEpochMilli
      buckets.get.map { map =>
        map.get(key).fold(config.burstSize.toDouble) { case (t, lastMs) =>
          val elapsed = math.max(0L, nowMs - lastMs).toDouble / 1000.0
          math.min(config.burstSize.toDouble, t + elapsed * config.requestsPerSecond)
        }
      }
    }
}

object RateLimiter {
  def create[K](config: RateLimiterConfig): IO[RateLimiter[K]] =
    Ref.of[IO, Map[K, (Double, Long)]](Map.empty)
      .map(new RateLimiter(config, _))
}

