package weather

import munit.FunSuite
import weather.util.NwsCoverageValidator

class NwsCoverageValidatorSpec extends FunSuite {
  test("isGloballyValid: accepts normal lat/lon") {
    assert(NwsCoverageValidator.isGloballyValid(39.7456, -97.0892))
  }

  test("isGloballyValid: rejects out-of-range latitude") {
    assert(!NwsCoverageValidator.isGloballyValid(95.0, -97.0))
  }

  test("isWithinNwsCoverage: accepts representative US/territory points") {
    assert(NwsCoverageValidator.isWithinNwsCoverage(39.7392, -104.9903)) // Denver
    assert(NwsCoverageValidator.isWithinNwsCoverage(61.2181, -149.9003)) // Anchorage
    assert(NwsCoverageValidator.isWithinNwsCoverage(21.3069, -157.8583)) // Honolulu
    assert(NwsCoverageValidator.isWithinNwsCoverage(18.4655, -66.1057))  // San Juan
  }

  test("isWithinNwsCoverage: rejects non-US coordinates") {
    assert(!NwsCoverageValidator.isWithinNwsCoverage(51.5074, -0.1278)) // London
    assert(!NwsCoverageValidator.isWithinNwsCoverage(48.8566, 2.3522))  // Paris
  }
}

