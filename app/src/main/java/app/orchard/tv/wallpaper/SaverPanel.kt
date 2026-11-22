package app.orchard.tv.wallpaper

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import app.orchard.tv.MainActivity
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.SystemScreensaver
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
        // 和电视面板一样: 自动与 1080p 总在, 4K / 杜比视界只有这台电视真能放时才列出来。
        val qualities = AerialFormats.supported(context)
        val qualityOptions = (
            listOf(AerialQuality.Auto to R.string.ss_quality_auto, AerialQuality.Hd to R.string.ss_quality_hd) +
                listOf(AerialQuality.Uhd to R.string.ss_quality_uhd, AerialQuality.DolbyVision to R.string.ss_quality_dv)
                    .filter { it.first in qualities }
            )

        val items = JSONArray()
        // 电视上「立即开始」是屏保类型那格左边的播放按钮, 手机页单独一张卡, 放在最上面。
        items.put(
            JSONObject().put("key", "saverStart").put("kind", "action")
                .put("label", s(R.string.ss_start)).put("value", s(R.string.ss_start)),
        )
        items.put(
            item("saverKind", s(R.string.ss_kind), null, mode.key, kinds.map { it.first.key to s(it.second) }),
        )
        items.put(
            item("aerialQuality", s(R.string.ss_quality), s(R.string.ss_quality_hint),
                AerialSettings.aerialQuality.key, qualityOptions.map { it.first.key to s(it.second) }),
        )
        // 系统屏保的开始时间由固件定, 电视上也只显示不让改, 这里同样做成只读一行。
        if (mode == ScreensaverMode.System) {
            val delay = SystemScreensaver.firmwareDelayMs(context)
            val shown = when {
                delay == null -> s(R.string.ss_delay_system)
                delay == 0L -> s(R.string.ss_delay_system_off)
                else -> s(R.string.ss_delay_system_minutes, ((delay + 30_000) / 60_000).coerceAtLeast(1).toInt())
            }
            items.put(
                JSONObject().put("key", "saverDelaySystem").put("kind", "info")
                    .put("label", s(R.string.ss_delay)).put("hint", s(R.string.ss_delay_hint)).put("value", shown),
            )
        } else {
            items.put(
                item("saverMinutes", s(R.string.ss_delay), s(R.string.ss_delay_hint),
                    AerialSettings.screensaverMinutes.toString(),
                    AerialSettings.screensaverChoices.map { m ->
                        m.toString() to if (m == AerialSettings.NEVER) s(R.string.ss_never) else s(R.string.ss_minutes, m)
                    }),
            )
        }
        items.put(
            item("backStartsScreensaver", s(R.string.ss_back), s(R.string.ss_back_choice_hint),
                if (AerialSettings.backStartsScreensaver) "1" else "0",
                listOf("1" to s(R.string.ss_back_on), "0" to s(R.string.ss_back_off)),
            ),
        )
        // 屏保画面上叠不叠时间、地名: 和电视一样只在航拍、照片两种屏保下问。
        if (mode == ScreensaverMode.Aerial || mode == ScreensaverMode.Photos) {
            items.put(
                item("screensaverClock", s(R.string.ss_show_time), s(R.string.ss_show_time_hint),
                    if (AerialSettings.screensaverClock) "1" else "0",
                    listOf("1" to s(R.string.st_on), "0" to s(R.string.st_off)),
                ),
            )
            items.put(
                item("screensaverCaption", s(R.string.ss_show_caption), s(R.string.ss_show_caption_hint),
                    if (AerialSettings.screensaverCaption) "1" else "0",
                    listOf("1" to s(R.string.st_on), "0" to s(R.string.st_off)),
                ),
            )
        }
        return JSONObject().put("items", items)
    }

    /** 写一条; 认不出来的键返回 false。 */
    fun set(context: Context, key: String, value: String): Boolean = when (key) {
        // 立即开始: 与电视面板那颗播放按钮同一个入口, 让电视自己开屏保。
        "saverStart" -> {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_PREVIEW_SCREENSAVER
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
            true
        }
        "screensaverClock" -> { AerialSettings.chooseScreensaverClock(value == "1"); true }
        "screensaverCaption" -> { AerialSettings.chooseScreensaverCaption(value == "1"); true }
        "saverDelaySystem" -> false
        "saverKind" -> {
            // 已经是这个类型就不重开屏保, 但也算存下了: 同一个值点一下不该报设置失败。
            val mode = ScreensaverMode.of(value)
            if (mode != Wallpapers.screensaver) Wallpapers.chooseScreensaver(mode)
            true
        }
        "aerialQuality" -> {
            // 自动档不在 supported 里(那里只列电视真能放的档位), 不能拿它去查; 页面给的选项本来就
            // 是筛过的, 认得出就存, 放不了由 AerialFormats.resolve 在播放时降到能放的档。
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
