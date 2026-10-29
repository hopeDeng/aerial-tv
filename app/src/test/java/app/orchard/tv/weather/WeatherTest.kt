package app.orchard.tv.weather

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Open-Meteo readings, including the fields added for the arclauncher-style weather block. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WeatherTest {
    @Test fun `today and tomorrow come from the daily arrays`() {
        val w = Weather.weatherFrom(
            JSONObject(
                """{
                  "current":{"temperature_2m":21.4,"weather_code":3,"is_day":1,"relative_humidity_2m":66},
                  "daily":{"weather_code":[3,61],"temperature_2m_max":[24.2,20.1],"temperature_2m_min":[15.8,12.4]}
                }""",
            ),
        )
        assertEquals(21, w.celsius)
        assertEquals(WeatherKind.Cloudy, w.kind)
        assertEquals(24, w.high)
        assertEquals(16, w.low)
        assertEquals(66, w.humidity)
        assertEquals(WeatherKind.Rain, w.tomorrow?.kind)
        assertEquals(20, w.tomorrow?.high)
        assertEquals(12, w.tomorrow?.low)
    }

    @Test fun `an old reading without humidity or tomorrow still parses`() {
        val w = Weather.weatherFrom(
            JSONObject(
                """{
                  "current":{"temperature_2m":-3.6,"weather_code":0,"is_day":0},
                  "daily":{"temperature_2m_max":[1.0],"temperature_2m_min":[-5.0]}
                }""",
            ),
        )
        assertEquals(-4, w.celsius)
        assertEquals(1, w.high)
        assertEquals(-5, w.low)
        assertNull(w.humidity)
        assertNull(w.tomorrow)
    }
}
