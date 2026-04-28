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

```powershell
sbt run
```

Server starts on **http://localhost:8080**.

First start downloads dependencies (~100 MB). Subsequent starts are fast.

## Build and run Docker image

```powershell
# Build the image from the local source tree
docker build -t weather-server:latest .
```

```powershell
# Run the image on port 8080
docker run --rm -p 8080:8080 weather-server:latest
```

```powershell
# Example request
curl "http://localhost:8080/weather?lat=39.7456&lon=-97.0892"
```

## Cache configuration

The service supports 3 cache modes controlled by environment variables.

### `WEATHER_CACHE_TYPE`

- `memory` (default): in-process TTL cache with single-flight protection
- `redis`: shared distributed cache via Redis
- `none`: disables caching

### `REDIS_URI`

- Used only when `WEATHER_CACHE_TYPE=redis`
- Default: `redis://localhost:6379`

### Defaults

- Points cache TTL: `6 hours`
- Forecast cache TTL: `10 minutes`
- In-memory max entries: points `5000`, forecast `10000`

### PowerShell examples

```powershell
# Default mode (in-memory)
$env:WEATHER_CACHE_TYPE = "memory"
sbt run
```

```powershell
# Redis mode
$env:WEATHER_CACHE_TYPE = "redis"
$env:REDIS_URI = "redis://localhost:6379"
sbt run
```

```powershell
# No cache
$env:WEATHER_CACHE_TYPE = "none"
sbt run
```

### Redis quickstart (Docker)

```powershell
# Start Redis in the background
# (re-runnable: removes any previous container with same name)
docker rm -f weather-redis 2>$null
docker run -d --name weather-redis -p 6379:6379 redis:7
```

```powershell
# Verify Redis is reachable
# Expected output: PONG
docker exec weather-redis redis-cli ping
```

```powershell
# Run app against Redis cache
$env:WEATHER_CACHE_TYPE = "redis"
$env:REDIS_URI = "redis://localhost:6379"
sbt run
```

```powershell
# Optional cleanup
docker stop weather-redis
docker rm weather-redis
```

## Testcontainers

The test suite includes two Testcontainers-based capabilities:

1. `WeatherCacheSpec` can run Redis cache tests against a throwaway Redis container when Docker is available.
2. `DeploymentConfidenceSpec` can validate a prebuilt application image (disabled by default).

Run only the optional deployment image check:

```powershell
# Build image first
docker build -t weather-server:latest .

# Enable deployment confidence test
$env:RUN_CONTAINER_TESTS = "true"
$env:WEATHER_IMAGE = "weather-server:latest"
mvn -q -Dtest=weather.DeploymentConfidenceSpec test
```

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
| `cold` | <= 50 F | <= 10 C |
| `moderate` | 51 - 84 F | 11 - 29 C |
| `hot` | >= 85 F | >= 29 C |

## Notes

1. **"Today" fallback** - after about 6 PM the NWS may omit the "Today" period; the server falls back to the first available period.
2. **Fahrenheit only** - NWS returns Fahrenheit for US locations; Celsius conversion is not implemented.
3. **Coverage validation enabled** - requests outside NWS-supported US/territory regions return HTTP 400.
