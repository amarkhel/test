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
2. **No caching** - every request makes two NWS API calls.
2. **Basic retry/back-off only** - transient upstream failures now use exponential retry/back-off with jitter and optional HTTP 429 retries; rate-limiting controls and circuit breakers are still not implemented.
3. **"Today" fallback** - after ~6 PM the NWS removes the "Today" period and the
   first period becomes "Tonight". The server falls back to the first available
   period rather than returning an error.
4. **Fahrenheit only** - NWS always returns °F for US locations, so this is fine
   for the stated use case. Celsius conversion is not implemented.
5. **Coordinate coverage validation implemented** - requests now return HTTP 400 for coordinates outside NWS-supported US/territory regions using reusable polygon-based validation.
6. **No Docker image** - A production deployment would include a `Dockerfile`
   and container registry (ECR, Docker Hub, etc.) for reproducible, scalable deployments.