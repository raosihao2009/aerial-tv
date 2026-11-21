package app.orchard.tv.wallpaper

import android.content.Context
import android.content.res.Configuration
import app.orchard.tv.R
import app.orchard.tv.apps.AppCatalog
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.ClockHours
import app.orchard.tv.settings.Effects
import app.orchard.tv.settings.KidsTime
import app.orchard.tv.settings.Restricted
import app.orchard.tv.settings.RestrictedKind
import app.orchard.tv.settings.UiSounds
import app.orchard.tv.system.HomeStatus
import app.orchard.tv.system.Sounds
import app.orchard.tv.system.SystemProbe
import app.orchard.tv.update.Updates
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 电视上那份个性化面板里能改的东西, 原样搬到手机端「更多」页。
 *
 * 面板本身在电视上是 Compose 画的, 手机上没法复用; 这里只把"有哪些项、现在是什么值、
 * 选中后写哪一条设置"变成一份 JSON, 手机页照着画一遍。文案直接用电视的 strings.xml,
 * 所以两边措辞、五种语言永远一致, 不需要在网页里再翻译一遍。
 *
 * 壁纸、屏保、隐藏的卡片不在这里: 手机端别的标签页已经有了。
 */
object PhonePrefs {

    /** 按手机页选的语言取一份资源上下文: 页面上写的是 zh/ru/uk/kk, 这里换成对应的 locale。 */
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
        key: String, kind: String, label: String, hint: String?, value: String,
        options: List<Pair<String, String>> = emptyList(),
        min: Int? = null, max: Int? = null,
    ): JSONObject {
        val o = JSONObject().put("key", key).put("kind", kind).put("label", label).put("value", value)
        if (!hint.isNullOrBlank()) o.put("hint", hint)
        if (options.isNotEmpty()) {
            o.put("options", JSONArray().apply { options.forEach { (v, l) -> put(JSONObject().put("value", v).put("label", l)) } })
        }
        min?.let { o.put("min", it) }
        max?.let { o.put("max", it) }
        return o
    }

    fun schema(context: Context, lang: String): JSONObject {
        val c = ctx(context, lang)
        fun s(id: Int, vararg args: Any): String = c.getString(id, *args)
        val groups = JSONArray()

        // ---- 主屏幕 ----
        groups.put(JSONObject().put("title", s(R.string.per_home)).put("items", JSONArray().apply {
            put(
                item(
                    "homeColumns", "seg", s(R.string.st_columns), s(R.string.st_columns_hint),
                    AerialSettings.homeColumns.toString(),
                    AerialSettings.HOME_COLUMNS.map { it.toString() to it.toString() },
                ),
            )
            put(
                item(
                    "showAllNames", "seg", s(R.string.st_names), s(R.string.st_names_hint),
                    if (AerialSettings.showAllNames) "1" else "0",
                    listOf("0" to s(R.string.st_names_focused), "1" to s(R.string.st_names_all)),
                ),
            )
            put(
                item(
                    "homePlate", "seg", s(R.string.st_plate), s(R.string.st_plate_hint),
                    if (AerialSettings.homePlate) "1" else "0",
                    listOf("1" to s(R.string.st_plate_glass), "0" to s(R.string.st_plate_none)),
                ),
            )
            put(
                item(
                    "peekNextRow", "seg", s(R.string.st_peek), s(R.string.st_peek_hint),
                    if (AerialSettings.peekNextRow) "1" else "0",
                    listOf("1" to s(R.string.st_peek_show), "0" to s(R.string.st_peek_hide)),
                ),
            )
            put(
                item(
                    "oneRowHome", "seg", s(R.string.st_layout), s(R.string.st_home_layout_hint),
                    if (AerialSettings.oneRowHome) "1" else "0",
                    listOf("1" to s(R.string.st_home_one_row), "0" to s(R.string.st_home_standard)),
                ),
            )
            put(
                item(
                    "homeLocked", "seg", s(R.string.st_home_lock), s(R.string.st_home_lock_hint),
                    if (AerialSettings.homeLocked) "1" else "0",
                    listOf("1" to s(R.string.st_on), "0" to s(R.string.st_off)),
                ),
            )
        }))

        // ---- 直播 ----
        groups.put(JSONObject().put("title", s(R.string.per_live)).put("items", JSONArray().apply {
            put(
                item(
                    "livePreviewEnabled", "seg", s(R.string.st_live_preview), s(R.string.st_live_preview_hint),
                    if (AerialSettings.livePreviewEnabled) "1" else "0",
                    listOf("1" to s(R.string.st_on), "0" to s(R.string.st_off)),
                ),
            )
            put(
                item(
                    "jankSensitivity", "seg", s(R.string.st_live_jank), s(R.string.st_live_jank_hint),
                    AerialSettings.jankSensitivity.name.lowercase(),
                    listOf(
                        "off" to s(R.string.jank_off), "low" to s(R.string.jank_low),
                        "medium" to s(R.string.jank_medium), "high" to s(R.string.jank_high),
                    ),
                ),
            )
        }))

        // ---- 外观与声音 ----
        groups.put(JSONObject().put("title", s(R.string.per_look)).put("items", JSONArray().apply {
            put(
                item(
                    "effects", "seg", s(R.string.st_effects),
                    s(if (AerialSettings.effects == Effects.Auto && AerialSettings.lowEnd) R.string.st_effects_auto_lite_hint else R.string.st_effects_hint),
                    AerialSettings.effects.name.lowercase(),
                    listOf(
                        "auto" to s(R.string.st_effects_auto), "full" to s(R.string.st_effects_full),
                        "lite" to s(R.string.st_effects_lite),
                    ),
                ),
            )
            put(
                item(
                    "splashVeilEnabled", "seg", s(R.string.st_splash_veil), s(R.string.st_splash_veil_hint),
                    if (AerialSettings.splashVeilEnabled) "1" else "0",
                    listOf("1" to s(R.string.st_on), "0" to s(R.string.st_off)),
                ),
            )
            put(
                item(
                    "uiSounds", "seg", s(R.string.st_sounds),
                    s(if (AerialSettings.uiSounds == UiSounds.Tv) R.string.st_sounds_tv_hint else R.string.st_sounds_hint),
                    AerialSettings.uiSounds.name.lowercase(),
                    listOf(
                        "tv" to s(R.string.st_sounds_tv), "aerial" to s(R.string.st_sounds_aerial),
                        "off" to s(R.string.st_off),
                    ),
                ),
            )
            put(
                item(
                    "keyVolume", "num", s(R.string.st_key_volume), s(R.string.st_key_volume_hint),
                    AerialSettings.keyVolume.toString(), min = 0, max = 100,
                ),
            )
        }))

        // ---- 天气与时钟 ----
        groups.put(JSONObject().put("title", s(R.string.wp_weather)).put("items", JSONArray().apply {
            put(
                item(
                    "clockHours", "seg", s(R.string.st_clock_hours), s(R.string.st_clock_hours_hint),
                    if (AerialSettings.clockHours == ClockHours.H12) "12" else "24",
                    listOf("24" to s(R.string.st_clock_hours_24), "12" to s(R.string.st_clock_hours_12)),
                ),
            )
        }))

        // ---- 家庭守护: 家长密码 + 儿童与长辈模式(整屏那一页的全部规则) ----
        val rules = Restricted.rules
        groups.put(JSONObject().put("title", s(R.string.per_family)).put("items", JSONArray().apply {
            put(
                item(
                    "pin", "seg", s(R.string.st_pin), s(R.string.st_pin_hint),
                    if (AerialSettings.pinSet) "1" else "0",
                    listOf("1" to s(R.string.st_pin_on), "0" to s(R.string.st_pin_off)),
                ),
            )
            put(
                item(
                    "kidsEnabled", "seg", s(R.string.st_kids), s(R.string.st_kids_hint),
                    if (Restricted.enabled) "1" else "0",
                    listOf("1" to s(R.string.st_on), "0" to s(R.string.st_off)),
                ),
            )
            put(
                item(
                    "kidsKind", "seg", s(R.string.kids_kind),
                    s(if (Restricted.kind == RestrictedKind.Kids) R.string.kids_kind_kids_hint else R.string.kids_kind_elders_hint),
                    Restricted.kind.key,
                    listOf(
                        RestrictedKind.Kids.key to s(R.string.kids_kind_kids),
                        RestrictedKind.Elders.key to s(R.string.kids_kind_elders),
                    ),
                ),
            )
            put(
                item(
                    "kidsBlock", "seg", s(R.string.kids_block), s(R.string.kids_block_hint),
                    if (Restricted.blockOthers) "1" else "0",
                    listOf("1" to s(R.string.kids_block_on), "0" to s(R.string.kids_block_off)),
                ),
            )
            put(
                item(
                    "kidsWeekday", "minutes", s(R.string.kids_weekday), s(R.string.kids_minutes_hint),
                    rules.weekdayMinutes.toString(), min = 0, max = 1440,
                ),
            )
            put(
                item(
                    "kidsWeekend", "minutes", s(R.string.kids_weekend), s(R.string.kids_minutes_hint),
                    rules.weekendMinutes.toString(), min = 0, max = 1440,
                ),
            )
            put(
                item(
                    "kidsLatest", "seg", s(R.string.kids_latest),
                    c.getString(R.string.kids_latest_hint, KidsTime.clock(rules.startMinute)),
                    rules.endMinute.toString(),
                    listOf(
                        0 to s(R.string.kids_no_limit),
                        20 * 60 to KidsTime.clock(20 * 60),
                        21 * 60 to KidsTime.clock(21 * 60),
                        22 * 60 to KidsTime.clock(22 * 60),
                        23 * 60 to KidsTime.clock(23 * 60),
                    ).map { (v, l) -> v.toString() to l },
                ),
            )
            put(
                item(
                    "kidsBreak", "seg", s(R.string.kids_break),
                    c.getString(R.string.kids_break_hint, rules.breakMinutes),
                    rules.breakEvery.toString(),
                    listOf(0, 30, 45, 60).map { v -> v.toString() to (if (v == 0) s(R.string.st_off) else v.toString()) },
                ),
            )
            put(
                item(
                    "kidsAllowed", "list", s(R.string.kids_apps, Restricted.allowed.size),
                    s(R.string.kids_apps_hint),
                    Restricted.allowed.joinToString("\n"),
                    AppCatalog.kidsCandidates.map { it.key to it.label },
                ),
            )
        }))

        // ---- 关于 ----
        val home = SystemProbe.homeStatus(context)
        groups.put(JSONObject().put("title", s(R.string.cc_about)).put("items", JSONArray().apply {
            put(
                item(
                    "version", "info", s(R.string.cc_about_version, Updates.current),
                    if (Updates.upToDate) s(R.string.cc_about_latest, Updates.current) else null, "",
                ),
            )
            put(
                item(
                    "update", "action", s(R.string.cc_about_update), updateHint(c),
                    s(R.string.cc_about_check), emptyList(),
                ),
            )
            put(
                item(
                    "homeStatus", "info", s(R.string.cc_home_label),
                    when (home) {
                        HomeStatus.Default -> s(R.string.cc_home_default)
                        HomeStatus.Guarded -> s(R.string.cc_home_guarded)
                        HomeStatus.NotDefault -> s(R.string.cc_home_not_default)
                        else -> ""
                    },
                    "",
                ),
            )
        }))

        return JSONObject().put("groups", groups)
    }

    private fun updateHint(c: Context): String? {
        val install = Updates.install
        return when {
            install is Updates.Install.Downloading ->
                if (install.fraction <= 0f) c.getString(R.string.cc_update_connecting)
                else c.getString(R.string.cc_update_downloading, (install.fraction * 100).toInt())
            install is Updates.Install.Waiting -> c.getString(R.string.cc_update_waiting)
            install is Updates.Install.Opened -> c.getString(R.string.cc_update_opened)
            install is Updates.Install.Failed -> c.getString(
                when (install.reason) {
                    Updates.Failure.Download -> R.string.cc_update_failed_download
                    Updates.Failure.Blocked -> R.string.cc_update_failed_blocked
                    Updates.Failure.NotOfficial -> R.string.cc_update_failed_signature
                    Updates.Failure.NotNewer -> R.string.cc_update_failed_older
                },
            )
            Updates.checking -> c.getString(R.string.cc_update_checking)
            Updates.available != null -> c.getString(R.string.cc_update_now, Updates.available)
            Updates.upToDate -> c.getString(R.string.cc_about_latest, Updates.current)
            else -> null
        }
    }

    /**
     * 手机页按下的那一项: 写进对应的设置, 和电视面板上按它是同一条代码路径。
     * 返回 false 表示这个值电视不接受(比如密码没设却想关掉, 或者时间规则不成立)。
     */
    fun set(key: String, value: String): Boolean {
        val on = value == "1"
        fun int(): Int? = value.trim().toIntOrNull()
        fun enumOf(vararg all: Enum<*>, name: String): Enum<*>? =
            all.firstOrNull { it.name.equals(name, ignoreCase = true) }

        return when (key) {
            "homeColumns" -> int()?.takeIf { it in AerialSettings.HOME_COLUMNS }?.let {
                AerialSettings.chooseHomeColumns(it); true
            } ?: false
            "showAllNames" -> { AerialSettings.chooseShowAllNames(on); true }
            "homePlate" -> { AerialSettings.chooseHomePlate(on); true }
            "peekNextRow" -> { AerialSettings.choosePeekNextRow(on); true }
            "oneRowHome" -> { AerialSettings.chooseOneRowHome(on); true }
            "homeLocked" -> { AerialSettings.chooseHomeLocked(on); true }
            "livePreviewEnabled" -> { AerialSettings.chooseLivePreview(on); true }
            "jankSensitivity" -> {
                val v = enumOf(AerialSettings.JankSensitivity.Off, AerialSettings.JankSensitivity.Low,
                    AerialSettings.JankSensitivity.Medium, AerialSettings.JankSensitivity.High, name = value)
                if (v == null) false else { AerialSettings.chooseJankSensitivity(v as AerialSettings.JankSensitivity); true }
            }
            "effects" -> {
                val v = enumOf(Effects.Auto, Effects.Full, Effects.Lite, name = value)
                if (v == null) false else { AerialSettings.chooseEffects(v as Effects); true }
            }
            "splashVeilEnabled" -> { AerialSettings.chooseSplashVeil(on); true }
            "uiSounds" -> {
                val v = enumOf(UiSounds.Tv, UiSounds.Aerial, UiSounds.Off, name = value)
                if (v == null) false else {
                    AerialSettings.chooseUiSounds(v as UiSounds)
                    Sounds.prepare()
                    true
                }
            }
            "keyVolume" -> int()?.takeIf { it in 0..100 }?.let { AerialSettings.chooseKeyVolume(it); true } ?: false
            "clockHours" -> {
                AerialSettings.chooseClockHours(if (value == "12") ClockHours.H12 else ClockHours.H24)
                true
            }
            "update" -> { Updates.checkIfStale(force = true); true }
            // 家长密码: "set:1234" 立一个新密码, "off:1234" 用现有密码关掉(顺带关掉模式)。
            "pin" -> {
                val pin = value.substringAfter(':', "")
                when {
                    !pin.matches(Regex("\\d{4,8}")) -> false
                    value.startsWith("off:") && !AerialSettings.pinMatches(pin) -> false
                    value.startsWith("off:") -> {
                        AerialSettings.choosePin(null)
                        Restricted.chooseEnabled(false)
                        true
                    }
                    else -> { AerialSettings.choosePin(pin); true }
                }
            }
            "kidsEnabled" -> {
                if (on && Restricted.allowed.isEmpty()) false else {
                    Restricted.chooseEnabled(on)
                    if (on) Restricted.start()
                    true
                }
            }
            "kidsKind" -> {
                if (RestrictedKind.entries.none { it.key == value }) return false
                val kind = RestrictedKind.of(value)
                Restricted.chooseKind(kind); true
            }
            "kidsBlock" -> { Restricted.chooseBlockOthers(on); true }
            "kidsWeekday" -> int()?.takeIf { it in 0..1440 }?.let { m ->
                val r = Restricted.rules.copy(weekdayMinutes = m)
                if (r.valid) { Restricted.chooseRules(r); true } else false
            } ?: false
            "kidsWeekend" -> int()?.takeIf { it in 0..1440 }?.let { m ->
                val r = Restricted.rules.copy(weekendMinutes = m)
                if (r.valid) { Restricted.chooseRules(r); true } else false
            } ?: false
            "kidsLatest" -> int()?.takeIf { it in 0..(24 * 60) }?.let { m ->
                val next = Restricted.rules.copy(endMinute = m)
                val ok = if (next.valid) next else next.copy(startMinute = KidsTime.DEFAULT_START)
                Restricted.chooseRules(ok); true
            } ?: false
            "kidsBreak" -> int()?.takeIf { it in 0..(24 * 60) }?.let { m ->
                val r = Restricted.rules.copy(breakEvery = m)
                if (r.valid) { Restricted.chooseRules(r); true } else false
            } ?: false
            "kidsAllowed" -> {
                val known = AppCatalog.kidsCandidates.map { it.key }.toSet()
                val list = value.split('\n').map { it.trim() }.filter { it in known }.distinct()
                if (list.isEmpty()) false else { Restricted.chooseAllowed(list); true }
            }
            else -> false
        }
    }
}
