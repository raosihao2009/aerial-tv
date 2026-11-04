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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Drizzle, LightRain, ModerateRain, HeavyRain, ViolentRain, LightSnow, ModerateSnow, HeavySnow, Thunder }

/** One day of the forecast. */
data class ForecastDay(val kind: WeatherKind, val high: Int, val low: Int, val display: String = "",
                       val dayKind: WeatherKind = kind, val nightKind: WeatherKind = kind)

/** [high] and [low] are today's, shown under the large clock; null in a reading saved by an older version. */
data class WeatherNow(val celsius: Int, val kind: WeatherKind, val day: Boolean, val high: Int? = null, val low: Int? = null, val forecast: List<ForecastDay> = emptyList())

/**
 * The weather beside the home clock, from QWeather (和风天气): CMA official data, county-level
 * precision in China. The place is the one the owner names on the phone page, or else a guess from
 * the network's public address.
 *
 * Fetched every 10 minutes; nothing runs while the TV is idle.
 * Compose state, written on the main thread only.
 */
object Weather {
    private const val TAG = "AerialWeather"
    private const val PREFS = "weather"
    private const val QW_KEY = "2d9160494e2e4b1abdf23de36d9072b1"
    private const val QW_HOST = "pa4gkmwbku.re.qweatherapi.com"
    private const val REFRESH_MS = 10 * 60 * 1000L
    private const val RETRY_MS = 1 * 60 * 1000L
    private const val RELOCATE_MS = 24 * 60 * 60 * 1000L
    /** A reading older than this is not shown at all. */
    private const val STALE_MS = 60 * 60 * 1000L
    /** Stands for "no high or low" in the prefs. */
    private const val NONE = Int.MIN_VALUE

    var now by mutableStateOf<WeatherNow?>(null); private set

    /** Where the weather is for, as a name to show; null until known. */
    var place by mutableStateOf<String?>(null); private set

    /** True when the owner named the place; false while it is guessed from the network. */
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
        place = prefs.getString("place", null)
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

    /** 每10分钟整点对齐更新（:00 :10 :20 :30 :40 :50） */
    fun refreshIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val now = System.currentTimeMillis()
        val since = now - lastAttempt
        // 失败时2分钟重试
        if (failed && (networkCameUp || since >= RETRY_MS)) { refresh(); return }
        // 距离上次更新超过10分钟，且已到最近的10分钟整点
        if (since >= REFRESH_MS) {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = now
            val minute = cal.get(java.util.Calendar.MINUTE)
            val second = cal.get(java.util.Calendar.SECOND)
            // 已过最近的10分钟整点
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
                    // Cache today's forecast so it shows instantly on next boot
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

    /** Off the main thread. */
    private fun fetch(): WeatherNow {
        var lat = prefs.getFloat("lat", Float.NaN)
        var lon = prefs.getFloat("lon", Float.NaN)
        val guessedLongAgo = System.currentTimeMillis() - prefs.getLong("located_at", 0) > RELOCATE_MS
        if (prefs.getBoolean("chosen", false) && !lat.isNaN() && !prefs.getBoolean("checked_against_cities", false)) {
            correctChosenPlace(lat, lon)?.let { lat = it.lat.toFloat(); lon = it.lon.toFloat() }
        }
        if (!prefs.getBoolean("chosen", false) && (lat.isNaN() || guessedLongAgo)) {
            val guess = locate()
            if (guess != null) {
                lat = guess.lat
                lon = guess.lon
                val city = guess.city
                val zh = city?.takeIf { guess.cn && Locale.getDefault().language == "zh" }
                    ?.let {
                        (Cities.near(app, it, lat.toDouble(), lon.toDouble(), km = 80.0, ascii = true)
                            ?: Cities.mainNear(app, lat.toDouble(), lon.toDouble(), km = 20.0))?.name
                    }
                prefs.edit {
                    putFloat("lat", lat); putFloat("lon", lon)
                    putString("place", zh ?: city)
                    putLong("located_at", System.currentTimeMillis())
                }
            }
        }
        // QWeather uses lon,lat order; new accounts use api.qweatherapi.com
        val loc = "$lon,$lat"
        val nowJson = JSONObject(get("https://$QW_HOST/v7/weather/now?location=$loc&key=$QW_KEY"))
        val now = nowJson.getJSONObject("now")
        val temp = now.getString("temp").toIntOrNull() ?: 0
        val text = now.optString("text")
        val isDay = now.optString("icon").toIntOrNull()?.let { it < 100 } ?: true

        val forecastJson = JSONObject(get("https://$QW_HOST/v7/weather/3d?location=$loc&key=$QW_KEY"))
        val daily = forecastJson.optJSONArray("daily")
        val forecast = buildList {
            if (daily != null) {
                for (i in 0 until daily.length()) {
                    val d = daily.getJSONObject(i)
                    val h = d.optString("tempMax").toIntOrNull() ?: continue
                    val l = d.optString("tempMin").toIntOrNull() ?: continue
                    val dayText = d.optString("textDay")
                    val nightText = d.optString("textNight")
                    val display = if (dayText == nightText) dayText else "${dayText}转${nightText}"
                    add(ForecastDay(kindOfText(dayText), h, l, display,
                        dayKind = kindOfText(dayText), nightKind = kindOfText(nightText)))
                }
            }
        }
        return WeatherNow(
            celsius = temp,
            kind = kindOfText(text),
            day = isDay,
            high = forecast.firstOrNull()?.high,
            low = forecast.firstOrNull()?.low,
            forecast = forecast,
        )
    }

    /**
     * Where the connection is: ipwho.is first, ipinfo.io after it. ipinfo.io puts China Mobile's
     * pooled addresses in the wrong province (a Foshan, Guangdong line came out as Jiaxing,
     * Zhejiang), where ipwho.is names the city; both are keyless and answer over HTTPS.
     */
    private class Guess(val lat: Float, val lon: Float, val city: String?, val cn: Boolean)

    private fun locate(): Guess? {
        // Over IPv6 the databases place China Mobile's prefixes in another city of the province
        // (a Foshan line came out as Jiangmen), so the IPv4 address is asked for and looked up.
        val v4 = runCatching { get("https://ipv4.icanhazip.com").trim() }.getOrNull()
            ?.takeIf { Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(it) }
        runCatching {
            val ip = JSONObject(get(if (v4 != null) "https://ipwho.is/$v4" else "https://ipwho.is/"))
            if (ip.optBoolean("success", true)) {
                return Guess(
                    ip.getDouble("latitude").toFloat(),
                    ip.getDouble("longitude").toFloat(),
                    ip.optString("city").ifBlank { null },
                    ip.optString("country_code").equals("CN", ignoreCase = true),
                )
            }
        }.onFailure { Log.w(TAG, "ipwho.is", it) }
        runCatching {
            // ipinfo.io: no key, and its "loc" is "lat,lon".
            val ip = JSONObject(get("https://ipinfo.io/json"))
            val loc = ip.getString("loc").split(',')
            return Guess(
                loc[0].toFloat(),
                loc[1].toFloat(),
                ip.optString("city").ifBlank { null },
                ip.optString("country") == "CN",
            )
        }.onFailure { Log.w(TAG, "ipinfo.io", it) }
        return null
    }

    /**
     * A place chosen before 1.29 came from Open-Meteo's geocoder, which took 安庆 for a village in
     * Jiangsu. If a listed city of that name is not where the chosen point is, the city is meant.
     * Checked once.
     */
    private fun correctChosenPlace(lat: Float, lon: Float): Cities.City? {
        val name = prefs.getString("place", null)
        val city = name?.let { runCatching { Cities.find(app, it) }.getOrNull() }
            ?.takeIf { Cities.near(app, it.name, lat.toDouble(), lon.toDouble(), km = 30.0) == null }
        prefs.edit {
            putBoolean("checked_against_cities", true)
            if (city != null) {
                Log.i(TAG, "chosen place $name moved to the city of that name")
                putFloat("lat", city.lat.toFloat()); putFloat("lon", city.lon.toFloat())
            }
        }
        return city
    }

    /**
     * Names the place (from the phone page) and fetches its weather at once. Chinese names are
     * looked up in Aerial's own list first, others (pinyin, places abroad) in Open-Meteo's geocoder.
     * False when there is no such place.
     */
    suspend fun choose(name: String): Boolean {
        val hit = withContext(Dispatchers.IO) {
            runCatching { Cities.find(app, name) }.onFailure { Log.w(TAG, "could not read the city list", it) }.getOrNull()
                ?.let { Triple(it.lat, it.lon, it.name) }
                ?: runCatching {
                    val q = URLEncoder.encode(name.trim(), "UTF-8")
                    val results = JSONObject(get("https://geoapi.qweather.com/v2/city/lookup?location=$q&key=$QW_KEY&range=cn"))
                        .optJSONArray("location")
                    val all = (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }
                    all.firstOrNull()?.let { Triple(it.getDouble("lat"), it.getDouble("lon"), it.optString("name").ifBlank { name.trim() }) }
                }.onFailure { Log.w(TAG, "could not look up $name", it) }.getOrNull()
        } ?: return false
        prefs.edit {
            putFloat("lat", hit.first.toFloat())
            putFloat("lon", hit.second.toFloat())
            putString("place", hit.third)
            putBoolean("chosen", true)
            putBoolean("checked_against_cities", true)
        }
        chosen = true
        place = prefs.getString("place", null)
        refresh()
        return true
    }

    /** Back to guessing the place from the network. */
    fun useNetworkLocation() {
        prefs.edit { putBoolean("chosen", false); remove("lat"); remove("lon"); remove("located_at") }
        chosen = false
        refresh()
    }

    /** Map QWeather Chinese weather text to our WeatherKind. */
    private fun kindOfText(text: String): WeatherKind = when {
        text.contains("晴") -> WeatherKind.Clear
        text.contains("多云") -> WeatherKind.PartlyCloudy
        text.contains("阴") -> WeatherKind.Cloudy
        text.contains("雾") || text.contains("霾") -> WeatherKind.Fog
        text.contains("雷") -> WeatherKind.Thunder
        text.contains("小雪") || text.contains("阵雪") -> WeatherKind.LightSnow
        text.contains("中雪") -> WeatherKind.ModerateSnow
        text.contains("大雪") || text.contains("暴雪") -> WeatherKind.HeavySnow
        text.contains("雨夹雪") -> WeatherKind.LightSnow
        text.contains("小雨") || text.contains("毛毛雨") -> WeatherKind.LightRain
        text.contains("阵雨") -> WeatherKind.ModerateRain
        text.contains("中雨") -> WeatherKind.ModerateRain
        text.contains("大雨") -> WeatherKind.HeavyRain
        text.contains("暴雨") || text.contains("大暴雨") -> WeatherKind.ViolentRain
        text.contains("雪") -> WeatherKind.LightSnow
        text.contains("雨") -> WeatherKind.ModerateRain
        else -> WeatherKind.Cloudy
    }

    /**
     * Android before 7.1 does not trust the Let's Encrypt root these hosts use; they answer the
     * same over plain HTTP (allowed for them in network_security_config.xml).
     */
    private fun get(url: String): String = try {
        read(url)
    } catch (e: SSLException) {
        read(url.replaceFirst("https://", "http://"))
    }

    private fun read(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
