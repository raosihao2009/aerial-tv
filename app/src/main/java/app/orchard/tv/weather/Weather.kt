package app.orchard.tv.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Haze, Drizzle, LightRain, ModerateRain, HeavyRain, ViolentRain, LightSnow, ModerateSnow, HeavySnow, SnowStorm, Sleet, FreezingRain, Hail, Thunder, ThunderHail, Dust, Sand, SandStorm, Wind, Typhoon, Tornado }

/** One day of the forecast. */
data class ForecastDay(val kind: WeatherKind, val high: Int, val low: Int, val display: String = "",
                       val dayKind: WeatherKind = kind, val nightKind: WeatherKind = kind)

/** [high] and [low] are today's, shown under the large clock; null in a reading saved by an older version. */
data class WeatherNow(val celsius: Int, val kind: WeatherKind, val day: Boolean, val high: Int? = null, val low: Int? = null, val forecast: List<ForecastDay> = emptyList())

/**
 * The weather beside the home clock, from 中国天气网 (weather.com.cn): CMA official data.
 * The place is the one the owner names on the phone page, or defaults to 南昌县.
 *
 * Fetched every 10 minutes; nothing runs while the TV is idle.
 * Compose state, written on the main thread only.
 */
object Weather {
    private const val TAG = "AerialWeather"
    private const val PREFS = "weather"
    private const val FALLBACK_CITY_CODE = "101240103"  // 南昌县兜底
    private const val REFRESH_MS = 10 * 60 * 1000L
    private const val RETRY_MS = 1 * 60 * 1000L
    /** A reading older than this is not shown at all. */
    private const val STALE_MS = 60 * 60 * 1000L
    /** Stands for "no high or low" in the prefs. */
    private const val NONE = Int.MIN_VALUE

    var now by mutableStateOf<WeatherNow?>(null); private set

    /** Where the weather is for, as a name to show; null until known. */
    var place by mutableStateOf<String?>(null); private set

    /** True when the owner named the place; false while using the default. */
    var chosen by mutableStateOf(false); private set

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L
    private var job: Job? = null

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        place = prefs.getString("place_custom", null) ?: prefs.getString("place", null)
        chosen = prefs.getBoolean("chosen", false)
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
            val kind = runCatching { WeatherKind.valueOf(prefs.getString("kind", null) ?: "") }.getOrNull()
            if (kind != null) {
                val cachedHigh = prefs.getInt("high", NONE).takeIf { it != NONE }
                val cachedLow = prefs.getInt("low", NONE).takeIf { it != NONE }
                val fc = buildList {
                    val fcKind = runCatching { WeatherKind.valueOf(prefs.getString("fc_kind", null) ?: "") }.getOrNull()
                    val fcHigh = prefs.getInt("fc_high", NONE).takeIf { it != NONE }
                    val fcLow = prefs.getInt("fc_low", NONE).takeIf { it != NONE }
                    val fcDisplay = prefs.getString("fc_display", null)
                    if (fcKind != null && fcHigh != null && fcLow != null) {
                        add(ForecastDay(fcKind, fcHigh, fcLow, fcDisplay ?: ""))
                    }
                }
                now = WeatherNow(
                    prefs.getInt("celsius", 0), kind, prefs.getBoolean("day", true),
                    high = cachedHigh, low = cachedLow, forecast = fc,
                )
            }
        }
    }

    private var failed = false

    fun refreshIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val nowMs = System.currentTimeMillis()
        val since = nowMs - lastAttempt
        if (failed && (networkCameUp || since >= RETRY_MS)) { refresh(); return }
        if (since >= REFRESH_MS) {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = nowMs
            val minute = cal.get(java.util.Calendar.MINUTE)
            val second = cal.get(java.util.Calendar.SECOND)
            val lastTick = (minute / 10) * 10
            if (minute > lastTick || (minute == lastTick && second >= 0)) {
                refresh()
            }
        }
    }

    private fun refresh() {
        lastAttempt = System.currentTimeMillis()
        job?.cancel()
        job = scope.launch {
            val reading = withContext(Dispatchers.IO) {
                runCatching { fetch() }.onFailure { Log.w(TAG, "weather unavailable", it) }.getOrNull()
            }
            prefs.getString("place", null)?.let { place = it }
            failed = reading == null
            if (reading != null) {
                now = reading
                prefs.edit {
                    putLong("fetched_at", System.currentTimeMillis())
                    putInt("celsius", reading.celsius)
                    putString("kind", reading.kind.name)
                    putBoolean("day", reading.day)
                    putInt("high", reading.high ?: NONE)
                    putInt("low", reading.low ?: NONE)
                    reading.forecast.firstOrNull()?.let { fc ->
                        putString("fc_kind", fc.kind.name)
                        putInt("fc_high", fc.high)
                        putInt("fc_low", fc.low)
                        putString("fc_display", fc.display)
                    }
                }
            }
        }
    }

    /** Off the main thread. Fetches real-time + forecast from weather.com.cn. */
    private fun fetch(): WeatherNow {
        var cityCode = prefs.getString("city_code", null)
        if (cityCode == null) {
            // Default to 南昌县; user can change via phone web
            cityCode = FALLBACK_CITY_CODE
            prefs.edit {
                putString("city_code", cityCode)
                putString("place", "南昌县")
            }
        }
        val referer = "https://www.weather.com.cn/weather/$cityCode.shtml"

        // Real-time: d1.weather.com.cn/sk_2d/{code}.html
        val skText = get("https://d1.weather.com.cn/sk_2d/$cityCode.html", referer)
        val skJson = JSONObject(skText.substringAfter("var dataSK=").substringBefore(";"))
        val temp = skJson.optString("temp").toDoubleOrNull()?.roundToInt() ?: 0
        val weatherCode = skJson.optString("weathercode")  // e.g. "d00" day, "n00" night
        val isDay = weatherCode.startsWith("d")
        val skKind = kindOfCode(weatherCode)
        val cityName = skJson.optString("cityname")

        // Forecast: d1.weather.com.cn/weather_index/{code}.html
        val fcText = get("https://d1.weather.com.cn/weather_index/$cityCode.html", referer)
        val forecast = buildList {
            // Parse var fc={"f":[{...}]} using substring (regex fails on nested braces)
            val fcStart = fcText.indexOf("var fc")
            if (fcStart >= 0) {
                val braceStart = fcText.indexOf("{", fcStart)
                val braceEnd = fcText.lastIndexOf("}")
                if (braceStart >= 0 && braceEnd > braceStart) {
                    val fcJson = JSONObject(fcText.substring(braceStart, braceEnd + 1))
                    val days = fcJson.optJSONArray("f")
                    if (days != null) {
                        for (i in 0 until minOf(days.length(), 5)) {
                            val d = days.getJSONObject(i)
                            val dayCode = d.optString("fa")
                            val nightCode = d.optString("fb")
                            val high = d.optString("fc").toIntOrNull() ?: continue
                            val low = d.optString("fd").toIntOrNull() ?: continue
                            val dayKind = kindOfCode(dayCode)
                            val nightKind = kindOfCode(nightCode)
                            val dayText = textOfCode(dayCode)
                            val nightText = textOfCode(nightCode)
                            val display = if (dayText == nightText) dayText else "${dayText}转${nightText}"
                            add(ForecastDay(dayKind, high, low, display, dayKind = dayKind, nightKind = nightKind))
                        }
                    }
                }
            }
        }

        // Save city name from response
        if (cityName.isNotBlank() && !prefs.getBoolean("chosen", false)) {
            prefs.edit { putString("place", cityName) }
        }

        return WeatherNow(
            celsius = temp,
            kind = skKind,
            day = isDay,
            high = forecast.firstOrNull()?.high,
            low = forecast.firstOrNull()?.low,
            forecast = forecast,
        )
    }

    /**
     * Names the place (from the phone page) and fetches its weather at once.
     * Uses weather.com.cn city search to convert name to city code.
     */
    suspend fun choose(name: String): Boolean {
        val userInput = name.trim()
        // Strip suffix for search (API doesn't recognize 市/区/县)
        val searchName = userInput.replace(Regex("(市|区|县|旗|州|盟)$"), "")
        val hit = withContext(Dispatchers.IO) {
            runCatching {
                val q = URLEncoder.encode(searchName, "UTF-8")
                val resultsText = get("https://toy1.weather.com.cn/search?cityname=$q", "https://www.weather.com.cn/")
                // Returns JSONP: ([{ref:"code~pinyin~中文名~..."}])
                val arrayStr = resultsText.substringAfter("(").substringBefore(")")
                val arr = JSONArray(arrayStr)
                if (arr.length() > 0) {
                    val ref = arr.getJSONObject(0).optString("ref")
                    val parts = ref.split("~")
                    if (parts.size >= 3) {
                        // Save user's input as display name (if they added suffix), otherwise use API name
                        val displayName = if (userInput != searchName) userInput else parts[2]
                        Triple(parts[0], displayName, displayName)
                    } else null
                } else null
            }.onFailure { Log.w(TAG, "could not look up $name: ${it.message}", it) }.getOrNull()
        } ?: return false
        prefs.edit {
            putString("city_code", hit.first)
            putString("place", hit.second)
            putBoolean("chosen", true)
        }
        chosen = true
        place = prefs.getString("place_custom", null) ?: prefs.getString("place", null)
        refresh()
        return true
    }

    /** Choose location by GPS coordinates (from phone browser). Returns true on success. */
    /** Set a custom display name for the current city (does not change weather data). */
    fun setCustomPlaceName(name: String) {
        prefs.edit {
            if (name.isBlank()) remove("place_custom") else putString("place_custom", name)
        }
        place = prefs.getString("place_custom", null) ?: prefs.getString("place", null)
    }

    suspend fun chooseByLocation(lat: Double, lon: Double): Boolean {
        val hit = withContext(Dispatchers.IO) {
            runCatching {
                val params = URLEncoder.encode("""{"method":"stationinfo","lat":$lat,"lng":$lon}""", "UTF-8")
                val text = get("https://d4.weather.com.cn/geong/v1/api?params=$params", "https://www.weather.com.cn/")
                val json = JSONObject(text)
                if (json.optString("status") == "success") {
                    val station = json.getJSONObject("data").getJSONObject("station")
                    val code = station.optString("areaid")
                    val name = station.optString("namecn")
                    if (code.isNotBlank()) Pair(code, name) else null
                } else null
            }.onFailure { Log.w(TAG, "reverse geocode failed", it) }.getOrNull()
        } ?: return false
        prefs.edit {
            putString("city_code", hit.first)
            putString("place", hit.second)
            putBoolean("chosen", true)
        }
        chosen = true
        place = prefs.getString("place_custom", null) ?: prefs.getString("place", null)
        refresh()
        return true
    }

    /** Back to default location. */
    fun useNetworkLocation() {
        prefs.edit {
            putBoolean("chosen", false)
            remove("city_code")
            remove("place")
        }
        chosen = false
        refresh()
    }

    /** Auto-locate by IP using weather.com.cn geo service. Returns (cityCode, cityName). */
    private fun locateByIp(): Pair<String, String?>? {
        return runCatching {
            val text = get("https://wgeo.weather.com.cn/ip/", "https://www.weather.com.cn/")
            val id = Regex("var id=\"(.*?)\"").find(text)?.groupValues?.get(1) ?: return null
            val addr = Regex("var addr=\"(.*?)\"").find(text)?.groupValues?.get(1)
            // addr format: "省,市,区" — take city part
            val cityName = addr?.split(",")?.getOrNull(1)
            id to cityName
        }.onFailure { Log.w(TAG, "IP locate failed", it) }.getOrNull()
    }

    /** Map weather.com.cn weather code to WeatherKind. */
    private fun kindOfCode(code: String): WeatherKind {
        val n = code.removePrefix("d").removePrefix("n").toIntOrNull() ?: return WeatherKind.Cloudy
        return when (n) {
            0 -> WeatherKind.Clear
            1 -> WeatherKind.PartlyCloudy
            2 -> WeatherKind.Cloudy
            3 -> WeatherKind.Drizzle
            4 -> WeatherKind.Thunder
            5 -> WeatherKind.ThunderHail
            6 -> WeatherKind.Sleet
            7 -> WeatherKind.LightRain
            8 -> WeatherKind.ModerateRain
            9 -> WeatherKind.HeavyRain
            10, 11, 12 -> WeatherKind.ViolentRain
            13 -> WeatherKind.LightSnow
            14 -> WeatherKind.LightSnow
            15 -> WeatherKind.ModerateSnow
            16 -> WeatherKind.HeavySnow
            17 -> WeatherKind.SnowStorm
            18 -> WeatherKind.Fog
            19 -> WeatherKind.FreezingRain
            20, 31, 32 -> WeatherKind.SandStorm
            21 -> WeatherKind.LightRain
            22 -> WeatherKind.ModerateRain
            23 -> WeatherKind.HeavyRain
            24, 25 -> WeatherKind.ViolentRain
            26 -> WeatherKind.LightSnow
            27 -> WeatherKind.ModerateSnow
            28 -> WeatherKind.HeavySnow
            29 -> WeatherKind.Dust
            30 -> WeatherKind.Sand
            53 -> WeatherKind.Haze
            else -> WeatherKind.Cloudy
        }
    }

    /** Chinese text for a weather code (for forecast display). */
    private fun textOfCode(code: String): String {
        val n = code.removePrefix("d").removePrefix("n").toIntOrNull() ?: return ""
        return when (n) {
            0 -> "晴"
            1 -> "多云"
            2 -> "阴"
            3 -> "阵雨"
            4 -> "雷阵雨"
            5 -> "雷阵雨伴冰雹"
            6 -> "雨夹雪"
            7 -> "小雨"
            8 -> "中雨"
            9 -> "大雨"
            10 -> "暴雨"
            11 -> "大暴雨"
            12 -> "特大暴雨"
            13 -> "阵雪"
            14 -> "小雪"
            15 -> "中雪"
            16 -> "大雪"
            17 -> "暴雪"
            18 -> "雾"
            19 -> "冻雨"
            20 -> "沙尘暴"
            21 -> "小到中雨"
            22 -> "中到大雨"
            23 -> "大到暴雨"
            24 -> "暴雨到大暴雨"
            25 -> "大暴雨到特大暴雨"
            26 -> "小到中雪"
            27 -> "中到大雪"
            28 -> "大到暴雪"
            29 -> "浮尘"
            30 -> "扬沙"
            31 -> "沙尘暴"
            32 -> "强沙尘暴"
            53 -> "霾"
            else -> ""
        }
    }

    private fun get(url: String, referer: String): String = try {
        read(url, referer)
    } catch (e: SSLException) {
        read(url.replaceFirst("https://", "http://"), referer)
    }

    private fun read(url: String, referer: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("Referer", referer)
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
