package app.orchard.tv.wallpaper

import android.content.Context
import android.content.res.Configuration
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 手机页「壁纸与屏保」那一格最下面的屏保设置: 类型、航拍画质、开始时间、返回键。
 *
 * 和 PhonePrefs 一样, 文案直接用电视的 strings.xml, 手机页照着画; 写值走电视同一套 choose*。
 * 这四个只在手机页的这一格出现, 所以单独发一份, 不进个性化面板的那份 schema。
 */
object SaverPanel {

    private fun ctx(context: Context, lang: String): Context {
        val tag = when (lang) {
            "zh" -> "zh-CN"; "ru" -> "ru-RU"; "uk" -> "uk-UA"; "kk" -> "kk-KZ"; else -> "en-US"
        }
        val c = Configuration(context.resources.configuration)
        c.setLocale(Locale.forLanguageTag(tag))
        c.setLayoutDirection(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(c)
    }

    private fun item(
        key: String, label: String, hint: String?, value: String,
        options: List<Pair<String, String>>,
    ): JSONObject {
        val o = JSONObject().put("key", key).put("kind", "seg").put("label", label).put("value", value)
        if (!hint.isNullOrBlank()) o.put("hint", hint)
        o.put("options", JSONArray().apply { options.forEach { (v, l) -> put(JSONObject().put("value", v).put("label", l)) } })
        return o
    }

    fun schema(context: Context, lang: String): JSONObject {
        val c = ctx(context, lang)
        fun s(id: Int, vararg args: Any): String = c.getString(id, *args)
        val mode = Wallpapers.screensaver
        val kinds = listOf(
            ScreensaverMode.Aerial to R.string.ss_aerial,
            ScreensaverMode.Photos to R.string.ss_photos,
            ScreensaverMode.Clock to R.string.ss_clock,
            ScreensaverMode.Videos to R.string.ss_videos,
        ) + if (mode == ScreensaverMode.System) listOf(ScreensaverMode.System to R.string.ss_system) else emptyList()
        val qualities = AerialFormats.supported(context)
        val qualityOptions = listOf(
            AerialQuality.Auto to R.string.ss_quality_auto,
            AerialQuality.Hd to R.string.ss_quality_hd,
            AerialQuality.Uhd to R.string.ss_quality_uhd,
            AerialQuality.DolbyVision to R.string.ss_quality_dv,
        ).filter { it.first in qualities || it.first == AerialQuality.Auto || it.first == AerialQuality.Hd }

        val items = JSONArray()
        items.put(
            item("saverKind", s(R.string.ss_kind), null, mode.key, kinds.map { it.first.key to s(it.second) }),
        )
        items.put(
            item("aerialQuality", s(R.string.ss_quality), s(R.string.ss_quality_hint),
                AerialSettings.aerialQuality.key, qualityOptions.map { it.first.key to s(it.second) }),
        )
        items.put(
            item("saverMinutes", s(R.string.ss_delay), s(R.string.ss_delay_hint),
                AerialSettings.screensaverMinutes.toString(),
                AerialSettings.screensaverChoices.map { m ->
                    m.toString() to if (m == AerialSettings.NEVER) s(R.string.ss_never) else s(R.string.ss_minutes, m)
                }),
        )
        items.put(
            item("backStartsScreensaver", s(R.string.ss_back), s(R.string.ss_back_choice_hint),
                if (AerialSettings.backStartsScreensaver) "1" else "0",
                listOf("1" to s(R.string.ss_back_on), "0" to s(R.string.ss_back_off)),
            ),
        )
        return JSONObject().put("items", items)
    }

    /** 写一条; 认不出来的键返回 false。 */
    fun set(context: Context, key: String, value: String): Boolean = when (key) {
        "saverKind" -> {
            val mode = ScreensaverMode.of(value)
            if (mode == Wallpapers.screensaver) false else { Wallpapers.chooseScreensaver(mode); true }
        }
        "aerialQuality" -> {
            AerialSettings.chooseAerialQuality(AerialQuality.of(value)); true
        }
        "saverMinutes" -> {
            val minutes = value.toIntOrNull() ?: -1
            if (minutes < 0) false else {
                val pick = AerialSettings.screensaverChoices.firstOrNull { it == minutes }
                    ?: AerialSettings.screensaverChoices.minByOrNull { kotlin.math.abs(it - minutes) } ?: minutes
                AerialSettings.chooseScreensaverMinutes(pick); true
            }
        }
        "backStartsScreensaver" -> {
            AerialSettings.chooseBackStartsScreensaver(value == "1"); true
        }
        else -> false
    }
}
