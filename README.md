# weather-server

A small Scala HTTP server that returns today's short forecast and temperature
characterisation for any US location, powered by the
[National Weather Service API](https://www.weather.gov/documentation/services-web-api).

## Stack

| Library | Purpose |
|---|---|
| [http4s](https://http4s.org) Ember | HTTP server + client |
| [Circe](https://circe.github.io/circe/) | JSON decoding / encoding |
| [Cats Effect 3](https://typelevel.org/cats-effect/) | Async runtime (IO) |

## Requirements

- **JDK 11+**
- **sbt 1.10+**

## Run

```bash
sbt run
```

Server starts on **http://localhost:8080**.

First start downloads dependencies (~100 MB). Subsequent starts are fast.

## Endpoint

```
GET /weather?lat={latitude}&lon={longitude}
```

### Example

```bash
# Tampa, FL
curl "http://localhost:8080/weather?lat=27.9506&lon=-82.4572"
```

### Response

```json
{
  "location": "27.9506, -82.4572",
  "shortForecast": "Partly Cloudy",
  "temperature": 82,
  "temperatureUnit": "F",
  "temperatureCategory": "moderate"
}
```

### Temperature categories

| Category | Fahrenheit | Celsius (approx) |
|---|---|---|
| `cold` | ≤ 50 °F | ≤ 10 °C |
| `moderate` | 51 – 84 °F | 11 – 29 °C |
| `hot` | ≥ 85 °F | ≥ 29 °C |

## Shortcuts / non-production notes
1. **No Docker image** - A production deployment would include a `Dockerfile` and container registry (ECR, Docker Hub, etc.) for reproducible, scalable deployments.
2. **In-memory TTL caching only** - the service caches `/points` and forecast responses per process (default: points 6h, forecast 10m). A production deployment may use a distributed cache (for example Redis) if multiple instances need shared cache state.
3. **No retry / back-off / rate-limiting / circuit breakers** - A production service would add exponential back-off with jitter.
4. **"Today" fallback** - after ~6 PM the NWS removes the "Today" period and the
   first period becomes "Tonight". The server falls back to the first available
   period rather than returning an error.
5. **Fahrenheit only** - NWS always returns °F for US locations, so this is fine
   for the stated use case. Celsius conversion is not implemented.
6. **No sophisticated coordinate validation** - A production service would return HTTP 400 for non-US coordinates (outside NWS coverage).
