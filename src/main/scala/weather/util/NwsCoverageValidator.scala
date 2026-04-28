package weather.util

/** Geometry-backed coordinate validation for NWS coverage. */
object NwsCoverageValidator {
  private final case class Point(lat: Double, lon: Double)
  private final case class Polygon(vertices: Vector[Point])

  private val Eps = 1e-9

  private val coveragePolygons: List[Polygon] = List(
    // Contiguous US (rough coastline-aware polygon)
    Polygon(Vector(
      Point(24.3, -124.9),
      Point(32.4, -117.0),
      Point(42.0, -124.4),
      Point(49.0, -123.0),
      Point(49.0, -95.0),
      Point(46.0, -84.0),
      Point(44.0, -67.0),
      Point(31.0, -80.5),
      Point(24.3, -81.5),
      Point(24.3, -97.0)
    )),
    // Alaska
    Polygon(Vector(
      Point(51.0, -179.9),
      Point(71.8, -168.5),
      Point(71.8, -141.0),
      Point(69.0, -130.0),
      Point(55.0, -130.0),
      Point(51.0, -150.0)
    )),
    // Hawaii
    Polygon(Vector(
      Point(18.8, -160.6),
      Point(22.5, -160.6),
      Point(22.5, -154.5),
      Point(18.8, -154.5)
    )),
    // Puerto Rico + US Virgin Islands
    Polygon(Vector(
      Point(17.5, -67.9),
      Point(18.8, -67.9),
      Point(18.8, -64.2),
      Point(17.5, -64.2)
    )),
    // Guam + Northern Mariana Islands
    Polygon(Vector(
      Point(13.0, 144.3),
      Point(20.0, 144.3),
      Point(20.0, 146.9),
      Point(13.0, 146.9)
    )),
    // American Samoa
    Polygon(Vector(
      Point(-14.7, -171.3),
      Point(-10.8, -171.3),
      Point(-10.8, -168.0),
      Point(-14.7, -168.0)
    ))
  )

  def isGloballyValid(lat: Double, lon: Double): Boolean =
    lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180

  def isWithinNwsCoverage(lat: Double, lon: Double): Boolean = {
    val point = Point(lat, lon)
    coveragePolygons.exists(contains(point, _))
  }

  private def contains(point: Point, polygon: Polygon): Boolean = {
    val points = polygon.vertices
    if (points.length < 3) false
    else {
      // Boundary points count as covered.
      val withWrap = points :+ points.head
      if (withWrap.sliding(2).exists { edge => onSegment(edge.head, edge.last, point) }) true
      else {
        var inside = false
        var i = 0
        while (i < points.length) {
          val a = points(i)
          val b = points((i + 1) % points.length)
          val intersects = ((a.lat > point.lat) != (b.lat > point.lat)) &&
            (point.lon < (b.lon - a.lon) * (point.lat - a.lat) / (b.lat - a.lat) + a.lon)
          if (intersects) inside = !inside
          i += 1
        }
        inside
      }
    }
  }

  private def onSegment(a: Point, b: Point, p: Point): Boolean = {
    val cross = (p.lat - a.lat) * (b.lon - a.lon) - (p.lon - a.lon) * (b.lat - a.lat)
    if (math.abs(cross) > Eps) false
    else {
      val withinLat = p.lat >= math.min(a.lat, b.lat) - Eps && p.lat <= math.max(a.lat, b.lat) + Eps
      val withinLon = p.lon >= math.min(a.lon, b.lon) - Eps && p.lon <= math.max(a.lon, b.lon) + Eps
      withinLat && withinLon
    }
  }
}

