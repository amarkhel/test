package weather

import cats.effect.{IO, IOApp}
import com.comcast.ip4s._
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.Logger

object Main extends IOApp.Simple {

  def run: IO[Unit] =
    EmberClientBuilder.default[IO].build.use { client =>
      val httpApp = Logger.httpApp(logHeaders = false, logBody = false)(
        Routes.weatherRoutes(client).orNotFound
      )

      EmberServerBuilder
        .default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(port"8080")
        .withHttpApp(httpApp)
        .build
        .use { _ =>
          IO.println("Weather server running on http://localhost:8080") *> IO.never
        }
    }
}
