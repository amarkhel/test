package weather.util

import cats.effect.{IO, Ref}

import scala.concurrent.duration._

/** Three-state circuit breaker state machine. */
sealed trait CircuitState
object CircuitState {
  /** Normal operation — counting consecutive upstream failures. */
  final case class Closed(failures: Int) extends CircuitState
  /** Upstream is struggling — all requests fail fast until [[resetTimeout]] elapses. */
  final case class Open(openedAtMs: Long) extends CircuitState
  /** One probe is allowed through to check if upstream recovered. */
  case object HalfOpen extends CircuitState
}

/**
 * Raised when a call is rejected because the circuit is open.
 * Routes map this to `503 Service Unavailable`.
 */
final class CircuitBreakerOpenException(msg: String) extends Exception(msg)

/**
 * @param failureThreshold    consecutive failures in [[CircuitState.Closed]] that trip the breaker
 * @param resetTimeout        how long to stay [[CircuitState.Open]] before allowing a probe
 * @param tripOn              predicate; only errors matching it count toward the threshold
 *                            (default: all errors)
 */
final case class CircuitBreakerConfig(
    failureThreshold: Int,
    resetTimeout: FiniteDuration,
    tripOn: Throwable => Boolean = _ => true
)

/**
 * Generic, concurrency-safe circuit breaker.
 *
 * State transitions:
 *   Closed  ──(≥ failureThreshold failures)──▶ Open
 *   Open    ──(resetTimeout elapsed)──────────▶ HalfOpen
 *   HalfOpen──(success)────────────────────────▶ Closed(0)
 *   HalfOpen──(failure)────────────────────────▶ Open
 */
final class CircuitBreaker private (
    config: CircuitBreakerConfig,
    stateRef: Ref[IO, CircuitState]
) {
  /**
   * Run [[effect]] through the circuit breaker.
   * Raises [[CircuitBreakerOpenException]] when the circuit is open.
   */
  def protect[A](effect: IO[A]): IO[A] =
    IO.realTimeInstant.flatMap { now =>
      val nowMs = now.toEpochMilli
      stateRef.modify { state =>
        state match {
          case CircuitState.Closed(_) =>
            (state, true)

          case CircuitState.HalfOpen =>
            // Another probe is already in flight; reject concurrent requests.
            (state, false)

          case CircuitState.Open(openedAt) =>
            if (nowMs - openedAt >= config.resetTimeout.toMillis)
              (CircuitState.HalfOpen, true)  // first caller after timeout becomes the probe
            else
              (state, false)
        }
      }.flatMap { allowed =>
        if (!allowed)
          IO.raiseError(new CircuitBreakerOpenException(
            "Circuit breaker is open. Upstream requests are suspended temporarily."))
        else
          effect.attempt.flatMap {
            case Right(value) =>
              stateRef.update {
                case CircuitState.HalfOpen  => CircuitState.Closed(0)
                case CircuitState.Closed(_) => CircuitState.Closed(0)
                case other                  => other
              } *> IO.pure(value)

            case Left(error) =>
              IO.realTimeInstant.flatMap { failNow =>
                stateRef.update { current =>
                  if (!config.tripOn(error)) current
                  else current match {
                    case CircuitState.HalfOpen =>
                      CircuitState.Open(failNow.toEpochMilli)
                    case CircuitState.Closed(n) =>
                      if (n + 1 >= config.failureThreshold) CircuitState.Open(failNow.toEpochMilli)
                      else CircuitState.Closed(n + 1)
                    case open => open  // already open
                  }
                }
              } *> IO.raiseError(error)
          }
      }
    }

  /** Current state, useful for observability / tests. */
  def currentState: IO[CircuitState] = stateRef.get
}

object CircuitBreaker {
  def create(config: CircuitBreakerConfig): IO[CircuitBreaker] =
    Ref.of[IO, CircuitState](CircuitState.Closed(0))
      .map(new CircuitBreaker(config, _))
}

