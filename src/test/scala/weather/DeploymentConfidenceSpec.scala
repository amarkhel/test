package weather

import munit.FunSuite
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.control.NonFatal

/** Optional deployment confidence check against a built Docker image.
  *
  * This test is disabled by default. Enable by setting:
  *   RUN_CONTAINER_TESTS=true
  * and build an image tagged by WEATHER_IMAGE (default: weather-server:latest).
  */
class DeploymentConfidenceSpec extends FunSuite {

  private val runContainerChecks: Boolean =
    sys.env.get("RUN_CONTAINER_TESTS").exists(_.equalsIgnoreCase("true"))

  private val imageName: String =
    sys.env.getOrElse("WEATHER_IMAGE", "weather-server:latest")

  private def dockerCheckMessage(err: Throwable): String = {
    val base = Option(err.getMessage).filter(_.nonEmpty).getOrElse(err.getClass.getSimpleName)
    if (base.toLowerCase.contains("client version") && base.toLowerCase.contains("too old"))
      s"Docker API mismatch for Testcontainers ($base). Unset DOCKER_API_VERSION or upgrade Docker."
    else
      s"Docker daemon is unavailable for Testcontainers ($base)"
  }

  private final class WeatherContainer(image: DockerImageName)
      extends GenericContainer[WeatherContainer](image)

  test("container image starts and responds on /weather") {
    assume(
      runContainerChecks,
      "Set RUN_CONTAINER_TESTS=true to enable Testcontainers deployment checks"
    )

    val dockerAvailable =
      try Right(DockerClientFactory.instance().isDockerAvailable)
      catch {
        case NonFatal(e) => Left(dockerCheckMessage(e))
      }

    dockerAvailable match {
      case Left(reason)  => assume(false, reason)
      case Right(value)  => assume(value, "Docker daemon is unavailable for Testcontainers")
    }

    val container = new WeatherContainer(DockerImageName.parse(imageName))
      .withExposedPorts(8080)
      .waitingFor(
        Wait.forHttp("/weather")
          .forStatusCode(400)
          .withStartupTimeout(Duration.ofMinutes(3))
      )

    container.start()
    try {
      val baseUri = URI.create(s"http://${container.getHost}:${container.getMappedPort(8080)}")
      val req = HttpRequest
        .newBuilder(baseUri.resolve("/weather"))
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build()

      val resp = HttpClient
        .newHttpClient()
        .send(req, HttpResponse.BodyHandlers.ofString())

      assertEquals(resp.statusCode(), 400)
      assert(resp.body().contains("Required query parameters"), clues(resp.body()))
    } finally {
      container.stop()
    }
  }
}
