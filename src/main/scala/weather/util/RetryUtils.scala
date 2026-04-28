package weather.util

import cats.effect.IO

import scala.concurrent.duration._
import java.util.concurrent.ThreadLocalRandom

final case class RetryPolicy(
    maxAttempts: Int,
    initialDelay: FiniteDuration,
    backoffMultiplier: Double,
    maxDelay: FiniteDuration,
    jitterEnabled: Boolean,
    jitterRatio: Double
)

object RetryUtils {
  private def jittered(delay: FiniteDuration, policy: RetryPolicy): IO[FiniteDuration] =
    if (!policy.jitterEnabled || policy.jitterRatio <= 0d) IO.pure(delay)
    else IO.delay {
      val ratio = policy.jitterRatio
      val min = math.max(0d, 1d - ratio)
      val max = 1d + ratio
      val factor = ThreadLocalRandom.current().nextDouble(min, max)
      math.max(1L, math.ceil(delay.toMillis * factor).toLong).millis
    }

  private def nextDelay(current: FiniteDuration, policy: RetryPolicy): IO[FiniteDuration] = {
    val scaled = math.ceil(current.toMillis * policy.backoffMultiplier).toLong.millis
    val capped = if (scaled > policy.maxDelay) policy.maxDelay else scaled
    jittered(capped, policy).map(d => if (d > policy.maxDelay) policy.maxDelay else d)
  }

  def withRetry[A](
      effect: => IO[A],
      policy: RetryPolicy,
      isRetryable: Throwable => Boolean,
      retryDelayFromError: Throwable => Option[FiniteDuration] = _ => None
  ): IO[A] = {
    val maxAttempts = math.max(policy.maxAttempts, 1)

    def loop(attemptsLeft: Int, delay: FiniteDuration): IO[A] =
      effect.handleErrorWith { error =>
        if (attemptsLeft <= 1 || !isRetryable(error)) IO.raiseError(error)
        else {
          val retryAfter = retryDelayFromError(error).getOrElse(delay)
          IO.sleep(retryAfter) *> nextDelay(delay, policy).flatMap(next => loop(attemptsLeft - 1, next))
        }
      }

    loop(maxAttempts, policy.initialDelay)
  }
}

