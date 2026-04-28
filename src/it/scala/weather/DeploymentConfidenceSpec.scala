package weather

import munit.FunSuite
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

class DeploymentConfidenceSpec extends FunSuite {
  private val runContainerChecks: Boolean =
    sys.env.get("RUN_CONTAINER_TESTS").exists(_.equalsIgnoreCase("true"))

  private val imageName: String =
    sys.env.getOrElse("WEATHER_IMAGE", "weather-server:latest")

  private final class WeatherContainer(image: DockerImageName)
      extends GenericContainer[WeatherContainer](image)

  test("container image starts and responds on /weather") {
    assume(
      runContainerChecks,
      "Set RUN_CONTAINER_TESTS=true to enable Testcontainers deployment checks"
    )
    assume(
      DockerClientFactory.instance().isDockerAvailable,
      "Docker daemon is unavailable for Testcontainers"
    )

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

