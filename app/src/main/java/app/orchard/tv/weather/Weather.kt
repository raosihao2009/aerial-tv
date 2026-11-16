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
                       val dayKind: WeatherKind = kind, val nightKind: WeatherKind = kind,
                       val week: String = "", val date: String = "")

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
        lastRefreshKey = dayKey()
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
            val kind = runCatching { WeatherKind.valueOf(prefs.getString("kind", null) ?: "") }.getOrNull()
            if (kind != null) {
                val cachedHigh = prefs.getInt("high", NONE).takeIf { it != NONE }
                val cachedLow = prefs.getInt("low", NONE).takeIf { it != NONE }
                val cachedFc = forecastFromJson(prefs.getString("fc_json", null))
                // 缓存预报同样按日期前移对齐; 老批次里昨天那条跳过, 后面几天仍是有效预报。
                val cachedAligned = alignToToday(cachedFc, todayKey())
                val fc = cachedAligned.ifEmpty { legacyCachedForecast() }
                now = WeatherNow(
                    prefs.getInt("celsius", 0), kind, prefs.getBoolean("day", true),
                    high = cachedAligned.firstOrNull()?.high ?: cachedHigh,
                    low = cachedAligned.firstOrNull()?.low ?: cachedLow,
                    forecast = fc,
                )
            }
        }
    }

    private var failed = false
    /** 上次成功刷新所在的完整日期键(yyyy-MM-dd), 用于识别跨天那一刻; 空串表示尚未记录。 */
    private var lastRefreshKey = ""

    private fun dayKey(): String {
        val c = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    fun refreshIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val nowMs = System.currentTimeMillis()
        val since = nowMs - lastAttempt
        if (failed && (networkCameUp || since >= RETRY_MS)) { refresh(); return }
        val cal = java.util.Calendar.getInstance()
        val minute = cal.get(java.util.Calendar.MINUTE)
        // 跨天那一刻强制刷新一次; 平时只在 00/10/20/30/40/50 分、且距上次满 10 分钟才刷。
        val today = dayKey()
        val crossedDay = lastRefreshKey.isNotEmpty() && today != lastRefreshKey
        if (crossedDay || (minute % 10 == 0 && since >= REFRESH_MS)) {
            refresh()
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
                lastRefreshKey = dayKey()
                now = reading
                Log.i(TAG, "天气刷新成功: 实况 ${reading.celsius}°, 预报 ${reading.forecast.joinToString { it.date.ifEmpty { it.week } }}")
                prefs.edit {
                    putLong("fetched_at", System.currentTimeMillis())
                    putInt("celsius", reading.celsius)
                    putString("kind", reading.kind.name)
                    putBoolean("day", reading.day)
                    putInt("high", reading.high ?: NONE)
                    putInt("low", reading.low ?: NONE)
                    putString("fc_json", forecastToJson(reading.forecast))
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
                            add(ForecastDay(dayKind, high, low, display, dayKind = dayKind,
                                nightKind = nightKind, week = d.optString("fj"), date = d.optString("fi")))
                        }
                    }
                }
            }
        }

        // Save city name from response
        if (cityName.isNotBlank() && !prefs.getBoolean("chosen", false)) {
            prefs.edit { putString("place", cityName) }
        }

        val aligned = alignToToday(forecast, todayKey())
        return WeatherNow(
            celsius = temp,
            kind = skKind,
            day = isDay,
            high = aligned.firstOrNull()?.high,
            low = aligned.firstOrNull()?.low,
            forecast = aligned,
        )
    }

    /**
     * Names the place (from the phone page) and fetches its weather at once.
     * Uses weather.com.cn city search to convert name to city code.
     */
    suspend fun choose(name: String): Boolean {
        val userInput = name.trim()
        if (userInput.isEmpty()) return false
        val hit = withContext(Dispatchers.IO) {
            // 先走本地城市表(assets/cities-cn.tsv)查到经纬度, 再用中国天气网的站点接口换成城市代码。
            // 这条链路和实时天气同一个数据源, 认得县级市与自治县(怀化市、新晃侗族自治县都查得到)。
            val local = runCatching { Cities.find(app, userInput) }.getOrNull()
            val byStation = local?.let { c ->
                runCatching {
                    stationAt(c.lat, c.lon)?.let { (code, official) ->
                        Triple(code, if (userInput != c.name) userInput else (official ?: c.name), c.name)
                    }
                }.onFailure { Log.w(TAG, "station lookup failed for $userInput: ${it.message}", it) }.getOrNull()
            }
            // 本地表里没有的名字, 才退回旧的模糊搜索接口。
            byStation ?: searchByName(userInput)
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

    /**
     * 旧的城市模糊搜索, 只作为本地城市表没命中时的兜底:
     * 结果里混着景点(代码以 A 结尾), 因此只接受 9 位纯数字的站点代码, 并优先名字完全一致的条目。
     */
    private fun searchByName(userInput: String): Triple<String, String, String>? {
        val tries = listOf(
            userInput,
            userInput.replace(Regex("(市|区|县|旗|州|盟)$"), ""),
            userInput.replace(Regex("((\\p{IsHan}{1,3}族)+|各族)*自治(县|州|区|旗)$"), ""),
        ).filter { it.isNotBlank() }.distinct()
        for (n in tries) {
            val arr = runCatching {
                val q = URLEncoder.encode(n, "UTF-8")
                val text = get("https://toy1.weather.com.cn/search?cityname=$q", "https://www.weather.com.cn/")
                // JSONP: ([{"ref":"code~pinyin~中文名~..."}])
                JSONArray(text.substringAfter("(").substringBefore(")"))
            }.onFailure { Log.w(TAG, "could not look up $n: ${it.message}", it) }.getOrNull() ?: continue
            val stations = (0 until arr.length())
                .map { arr.getJSONObject(it).optString("ref").split("~") }
                .filter { it.size >= 3 && STATION_CODE.matches(it[0]) }
            if (stations.isEmpty()) continue
            val best = stations.firstOrNull { it[2] == n || it[2] == userInput } ?: stations.first()
            return Triple(best[0], userInput, best[2])
        }
        return null
    }

    /** 站点代码: 9 位纯数字, 景点条目是 101xxxxxxAx 之类, 不能当地址用。 */
    private val STATION_CODE = Regex("\\d{9}")

    /** Choose location by GPS coordinates (from phone browser). Returns true on success. */
    /** Set a custom display name for the current city (does not change weather data). */
    fun setCustomPlaceName(name: String) {
        prefs.edit {
            if (name.isBlank()) remove("place_custom") else putString("place_custom", name)
        }
        place = prefs.getString("place_custom", null) ?: prefs.getString("place", null)
    }

    /** 经纬度 → 中国天气网站点: (areaid, 站名). Null when the service knows nothing there. */
    private fun stationAt(lat: Double, lon: Double): Pair<String, String?>? = runCatching {
        val params = URLEncoder.encode("""{"method":"stationinfo","lat":$lat,"lng":$lon}""", "UTF-8")
        val text = get("https://d4.weather.com.cn/geong/v1/api?params=$params", "https://www.weather.com.cn/")
        val json = JSONObject(text)
        if (json.optString("status") != "success") return@runCatching null
        val station = json.optJSONObject("data")?.optJSONObject("station") ?: return@runCatching null
        val code = station.optString("areaid")
        if (code.isBlank()) null else code to station.optString("namecn").takeIf { it.isNotBlank() }
    }.onFailure { Log.w(TAG, "station lookup failed: ${it.message}", it) }.getOrNull()

    suspend fun chooseByLocation(lat: Double, lon: Double): Boolean {
        val hit = withContext(Dispatchers.IO) {
            stationAt(lat, lon)?.let { (code, name) -> Triple(code, name ?: code, name ?: code) }
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
        chosen = false
        scope.launch(Dispatchers.IO) {
            val located = locateByIp()
            withContext(Dispatchers.Main) {
                if (located != null) {
                    prefs.edit {
                        putString("city_code", located.first)
                        putString("place", located.second ?: located.first)
                        putBoolean("chosen", false)
                        remove("place_custom")
                    }
                    place = located.second ?: located.first
                } else {
                    prefs.edit {
                        putString("city_code", "101010100")
                        putString("place", "北京")
                        putBoolean("chosen", false)
                        remove("place_custom")
                    }
                    place = "北京"
                }
                refresh()
            }
        }
    }

    /** Auto-locate by IP using weather.com.cn geo service. Returns (cityCode, cityName). */
    private fun locateByIp(): Pair<String, String?>? {
        return runCatching {
            val text = get("https://wgeo.weather.com.cn/ip/", "https://www.weather.com.cn/")
            val id = Regex("var id=\"(.*?)\"").find(text)?.groupValues?.get(1) ?: return null
            val addr = Regex("var addr=\"(.*?)\"").find(text)?.groupValues?.get(1)
            // addr format: "省,市,区" — use district if available, else city
            val parts = addr?.split(",")
            val district = parts?.getOrNull(2)?.takeIf { it.isNotBlank() }
            val city = parts?.getOrNull(1)?.takeIf { it.isNotBlank() }
            id to (district ?: city)
        }.onFailure { Log.w(TAG, "IP locate failed", it) }.getOrNull()
    }

    /** 今天的 (月, 日), 与接口 fi 字段同口径比较。 */
    private fun todayKey(): Pair<Int, Int> {
        val c = java.util.Calendar.getInstance()
        return (c.get(java.util.Calendar.MONTH) + 1) to c.get(java.util.Calendar.DAY_OF_MONTH)
    }

    /** 解析接口日期 "11/15" (JSON 里写作 11\/15) 为 (月, 日); 无法解析返回 null。 */
    private fun parseDayKey(raw: String): Pair<Int, Int>? {
        val parts = raw.replace("\\/", "/").split("/")
        if (parts.size < 2) return null
        val m = parts[0].toIntOrNull()
        val d = parts[1].toIntOrNull()
        if (m == null || d == null) return null
        return m to d
    }

    /** 条目日期是否 >= 今天; 解析不出日期时按不参与对齐处理。 */
    private fun sameOrAfter(raw: String, today: Pair<Int, Int>): Boolean {
        val k = parseDayKey(raw) ?: return false
        return !dateBefore(k, today)
    }

    /** a 的日期是否早于 b; 12 月与 1 月相邻, 月份差超过 6 个月时按跨年处理。 */
    private fun dateBefore(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean {
        if (a.first > b.first + 6) return true   // a 在 12 月、b 在 1 月: a 属上一年
        if (b.first > a.first + 6) return false  // a 在 1 月、b 在 12 月: a 属下一年
        return a.first < b.first || (a.first == b.first && a.second < b.second)
    }

    /**
     * 把接口预报按日期对齐到今天: 从第一条日期 >= 今天的条目开始向后取三天。
     * 凌晨旧批次尚未换日时, 数组头一条是昨天, 跳过它、后面几天前移当今天/明天/后天;
     * 早晨新批次发布后第一条就是今天, 自然回到下标 0。整组都早于今天才返回空;
     * 日期全部缺失时(老缓存/接口变更)退回取前三条。
     */
    private fun alignToToday(raw: List<ForecastDay>, today: Pair<Int, Int>): List<ForecastDay> {
        if (raw.isEmpty()) return emptyList()
        val start = raw.indexOfFirst { sameOrAfter(it.date, today) }
        if (start >= 0) return raw.subList(start, minOf(start + 3, raw.size))
        return if (raw.all { parseDayKey(it.date) == null }) raw.take(3) else emptyList()
    }

    private fun forecastToJson(list: List<ForecastDay>): String {
        val arr = JSONArray()
        for (f in list) {
            val o = JSONObject()
            o.put("kind", f.kind.name)
            o.put("high", f.high)
            o.put("low", f.low)
            o.put("display", f.display)
            o.put("dayKind", f.dayKind.name)
            o.put("nightKind", f.nightKind.name)
            o.put("week", f.week)
            o.put("date", f.date)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun forecastFromJson(s: String?): List<ForecastDay> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val kind = WeatherKind.valueOf(o.getString("kind"))
                    val dayKind = runCatching { WeatherKind.valueOf(o.optString("dayKind")) }.getOrDefault(kind)
                    val nightKind = runCatching { WeatherKind.valueOf(o.optString("nightKind")) }.getOrDefault(kind)
                    add(ForecastDay(kind, o.getInt("high"), o.getInt("low"), o.optString("display"),
                        dayKind = dayKind, nightKind = nightKind,
                        week = o.optString("week"), date = o.optString("date")))
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 老版本只存了第一条预报, 升级过渡期兜底读取。 */
    private fun legacyCachedForecast(): List<ForecastDay> {
        val fcKind = runCatching { WeatherKind.valueOf(prefs.getString("fc_kind", null) ?: "") }.getOrNull()
        val fcHigh = prefs.getInt("fc_high", NONE).takeIf { it != NONE }
        val fcLow = prefs.getInt("fc_low", NONE).takeIf { it != NONE }
        if (fcKind == null || fcHigh == null || fcLow == null) return emptyList()
        return listOf(ForecastDay(fcKind, fcHigh, fcLow, prefs.getString("fc_display", null) ?: "",
            week = prefs.getString("fc_week", null) ?: ""))
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
