package app.orchard.tv.system

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.orchard.tv.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A log of its own for firmwares whose logcat shows nothing from apps (Skyworth 7T631_T60,
 * Android 13: black Home, remote did nothing, no app line in logcat). Every line also goes to
 * logcat. The last 24 hours are kept in memory and printed by
 *   adb shell dumpsys activity app.orchard.tv/.MainActivity
 * and, in a diagnostic build (`./gradlew -Pdiag ...`) or after
 *   adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez diag true
 * also written to Android/data/app.orchard.tv/files/aerial-diag-YYYYMMDD.log, which `adb pull` can fetch.
 */
object DiagLog {
    private const val TAG = "AerialDiag"

    /**
     * 保留 24 小时: 早上 10 点的记录不该下午 12 点就被滚掉 —— 曾经的 600 行环形缓冲正是这样,
     * 人出门一趟回来, 想看的时段刚好已经没了。
     */
    private const val WINDOW_MS = 24L * 60 * 60 * 1000

    /** 内存里最多留这么多行; 只在日志暴涨时才会先到这条线, 正常一天差得远。 */
    private const val MAX_LINES = 20_000

    /** 单日文件上限: 到了就滚一份 .1 出来, 不让某一天把存储写满。 */
    private const val DAY_FILE_LIMIT = 32L * 1024 * 1024
    /** 直播事件日志上限: 只记关键事件, 一天差得远。 */
    private const val EVENT_FILE_LIMIT = 512L * 1024

    private class Entry(val at: Long, val text: String)

    private val lines = ArrayDeque<Entry>()
    // Made on the first line written to the file, so a release build without diag has no thread.
    private val writer by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "AerialDiag").apply { isDaemon = true } }
    }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayStamp = SimpleDateFormat("yyyyMMdd", Locale.US)
    @Volatile private var fileOn = false
    /** 本进程今天已经往日文件写过: 文件忽然不见就知道是被删了, 而不是刚创建。 */
    @Volatile private var wroteToday = false
    @Volatile private var dayKey: String? = null
    @Volatile private var dayFile: File? = null
    // 日期字符串 30 秒才重算一次: 每行都格式化一次太贵, 而 SimpleDateFormat 又不能跨线程用。
    @Volatile private var dayCached: String? = null
    @Volatile private var dayCachedAt = 0L

    /**
     * Whether lines go to the file as well. 默认开着: 「保留 24 小时」得跨进程重启才算数,
     * 只放内存的话进程一重启就归零, 那还是老样子。仍然可以用 ADB 关掉
     *   am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez diag false
     */
    fun enabled(context: Context): Boolean = BuildConfig.DIAG || prefs(context).getBoolean("diag", true)

    fun setEnabled(context: Context, on: Boolean) {
        setOption(context, "diag", on)
        fileOn = on
    }

    /**
     * Experiments for a black Home, set over ADB (ConfigReceiver) and read when Home starts:
     * mode_switch lets Home change the display's refresh rate (off in a diagnostic build, the only
     * Android 12+ display change Aerial makes); soft_layer draws Home in software, past the GPU.
     */
    fun modeSwitch(context: Context): Boolean = prefs(context).getBoolean("mode_switch", !BuildConfig.DIAG)
    fun softLayer(context: Context): Boolean = prefs(context).getBoolean("soft_layer", false)

    /**
     * An experiment for issue #654 (Redmi X50, MiTV-NSOP0, Novatek): the apps fading into
     * the screensaver each in a buffer of their own rather than with their alpha laid on every drawing.
     * Their frame and shadow are cut to the tile while they fade.
     */
    fun saverFadeOffscreen(context: Context): Boolean = prefs(context).getBoolean("saver_fade_offscreen", false)
    fun setOption(context: Context, key: String, on: Boolean) = prefs(context).edit().putBoolean(key, on).apply()
    private fun prefs(context: Context) = context.getSharedPreferences("system", Context.MODE_PRIVATE)

    /**
     * Directory of the logs; set on the first [init] even when the log file itself is off.
     *
     * 放在应用私有目录(filesDir/diag), 不再放 Android/data: 那是"外部存储", 盒子上装着的
     * 清理工具(当贝助手之类)和系统存储管理可以直接把 Android/data/<包名>/files 里的文件删掉。
     * 2026-11-20 就是这个: 上午几个小时的日志连同启动行一起没了, 盘上那份文件从 12:38:27 才开始,
     * 而同一个进程 12:38:04 就起来了 —— 中间被清空过, 我们的 24 小时轮换管不了别人删文件。
     * filesDir 是应用私有目录, 没有别的应用能碰。
     */
    @Volatile private var dir: File? = null

    /** 旧版日志所在的 Android/data 目录: 导出时一并带上, 免得升级当天丢掉还在里面的那半天。 */
    @Volatile private var legacyDir: File? = null

    fun init(context: Context) {
        if (dir == null) {
            dir = File(context.filesDir, "diag").also { runCatching { it.mkdirs() } }
            legacyDir = context.getExternalFilesDir(null)
        }
        fileOn = enabled(context)
        i("log file ${dailyFile(System.currentTimeMillis())?.absolutePath}")
    }

    /** 诊断日志所在目录; 别的模块(备份导入)往这里写独立记录, 导出日志时一并带出。 */
    fun dirOf(context: Context): File =
        dir ?: File(context.filesDir, "diag").also { dir = it; runCatching { it.mkdirs() } }

    fun i(message: String) {
        Log.i(TAG, message)
        // SimpleDateFormat is not thread-safe; lines come from the main thread and the player's.
        val now = System.currentTimeMillis()
        val line = synchronized(lines) {
            "${stamp.format(Date(now))} +${SystemClock.uptimeMillis()} $message".also { text ->
                lines.addLast(Entry(now, text))
                while (lines.size > MAX_LINES) lines.removeFirst()
                // 24 小时窗口: 过期的从头上一条条掉出去, 与行数上限谁先满足谁先删。
                while (lines.isNotEmpty() && now - lines.first().at > WINDOW_MS) lines.removeFirst()
            }
        }
        val f = dailyFile(now) ?: return
        writer.execute {
            runCatching {
                // 超过上限就滚一份旧的出来(.1), 而不是干脆不写: 停在半路比写满更难查。
                if (f.length() > DAY_FILE_LIMIT) {
                    val old = File(f.path + ".1")
                    runCatching { old.delete() }
                    runCatching { f.renameTo(old) }
                }
                val fresh = !f.exists()
                if (fresh) {
                    // 当天的文件本该一直在: 它忽然不存在, 就是被外部删掉了, 写一行说明, 免得
                    // 下次又对着一份"从半路开始"的日志猜。
                    if (wroteToday) {
                        f.appendText("${stamp.format(Date(now))} +${SystemClock.uptimeMillis()} " +
                            "---- 日志文件曾被外部删除, 已重建; 之前的内容已丢失 ----\n")
                    }
                    wroteToday = true
                }
                f.appendText(line + "\n")
                // Created 0600 on Xiaomi MiTV-MFTR0 (Android 14), which adb pull could not read.
                if (fresh) f.setReadable(true, false)
            }
        }
    }

    fun e(message: String, error: Throwable) = i("$message: ${Log.getStackTraceString(error)}")

    /** Logs whatever kills the process before it goes. */
    fun catchCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Crashes) return
        Thread.setDefaultUncaughtExceptionHandler(Crashes(previous))
    }

    private class Crashes(val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            val now = System.currentTimeMillis()
            val line = "${stamp.format(Date(now))} crash on ${t.name}: ${Log.getStackTraceString(e)}"
            Log.e(TAG, line)
            dailyFile(now)?.let { f -> runCatching { f.appendText(line + "\n"); f.setReadable(true, false) } }
            // 崩溃总得留一份: 诊断日志要手动开, 而"黑屏后重启"恰恰是没开的那次, 于是下次启动
            // 什么也看不到。这里固定写一份 crash 文件, 与诊断日志开关无关。
            dir?.let { d -> runCatching {
                val cf = File(d, "aerial-crash.log")
                if (cf.length() > 256 * 1024) cf.delete()
                cf.appendText(line + "\n")
                cf.setReadable(true, false)
            } }
            previous?.uncaughtException(t, e)
        }
    }

    fun device(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE}, ${Build.BOARD}), " +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.FINGERPRINT}, " +
            "Aerial ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** The last [n] lines of this process, oldest first. */
    fun recent(n: Int): List<String> = synchronized(lines) { lines.map { it.text }.takeLast(n) }

    /**
     * 只记"看直播时会关心的事": 重载、重连、换源、播放器出错、网络不通。
     * 一天几十行, 单独一个文件, 和每分钟好几条的诊断日志分开 —— 出门一趟回来问
     * "几点重载过", 翻这份就够了, 也不会被大日志的上限或轮换吃掉。
     */
    fun event(message: String) {
        i(message)
        val now = System.currentTimeMillis()
        val d = dir ?: return
        writer.execute {
            runCatching {
                val key = currentDay(now)
                val f = File(d, "live-events-$key.log")
                if (f.length() > EVENT_FILE_LIMIT) runCatching { f.delete() }
                val fresh = !f.exists()
                f.appendText("${stamp.format(Date(now))} $message\n")
                if (fresh) f.setReadable(true, false)
                d.listFiles { x -> x.name.startsWith("live-events-") }?.forEach { x ->
                    val day = Regex("live-events-(\\d{8}).log").find(x.name)?.groupValues?.get(1)
                    if (day != null && day != key && day != olderKey(key)) x.delete()
                }
            }
        }
    }

    /** 直播事件日志的内容, 给"显示日志文件"用。 */
    fun eventsText(): String {
        val d = dir ?: return ""
        val files = d.listFiles { f -> f.name.startsWith("live-events-") }?.sortedBy { it.name }.orEmpty()
        return buildString {
            files.forEach { each ->
                appendLine("---- ${each.name} (${each.length()} bytes) ----")
                runCatching { append(each.readText()) }
            }
        }
    }

    fun dump(writer: PrintWriter) {
        writer.println("Aerial log (${device()}):")
        synchronized(lines) { lines.forEach { writer.println("  ${it.text}") } }
        // 崩溃栈只落在文件里: 内存缓冲随进程重启清空, 于是"黑屏后重启再抓 dump"永远看不到
        // 上次为什么死。这里把文件里最近的 crash 行一并打出来。
        val dirNow = dir ?: return
        val crashes = crashLines(dirNow)
        if (crashes.isNotEmpty()) {
            writer.println("Crashes (from file, last ${crashes.size}):")
            crashes.forEach { writer.println("  $it") }
        }
    }

    /** 已落盘的崩溃记录, 供诊断报告与手机端导出共用。 */
    fun crashes(): List<String> = dir?.let { crashLines(it) } ?: emptyList()

    /** 崩溃行: aerial-crash.log 全部, 加最近两天日志里可能残留的 crash 行。 */
    private fun crashLines(dirNow: File): List<String> = runCatching {
        // 一条崩溃记录以 " crash on " 起始, 但栈本身是多行的, 必须按记录整条切分:
        // 之前直接 takeLast(5) 只留了栈的尾巴, 看不出异常类型。
        val records = ArrayList<String>()
        fun collect(text: String) {
            var cur: StringBuilder? = null
            for (line in text.lineSequence()) {
                if (line.contains(" crash on ")) {
                    cur?.let { records.add(it.toString()) }
                    cur = StringBuilder(line)
                } else {
                    cur?.append('\n')?.append(line)
                }
            }
            cur?.let { records.add(it.toString()) }
        }
        File(dirNow, "aerial-crash.log").takeIf { it.exists() }?.useLines { seq ->
            collect(seq.joinToString("\n"))
        }
        dayFiles(dirNow).forEach { each ->
            each.useLines { seq ->
                collect(seq.filter { it.contains(" crash on ") }.joinToString("\n"))
            }
        }
        records.takeLast(2)
    }.getOrDefault(emptyList())

    /**
     * 手机端「显示日志文件」/「下载日志文件」: 设备信息、最近 24 小时的日志、崩溃记录, 以及
     * 备份导入留下的 restore-trace.log。日志文件开着时直接用落盘的分天文件 —— 它跨进程,
     * 进程重启也不会把前面的记录抹掉。
     */
    fun exportText(): String = buildString {
        appendLine("Aerial log (${device()}):")
        if (!fileOn) appendLine("(诊断日志文件未开启, 下面只含本进程内存里最近 24 小时的日志; 崩溃记录另见 crash 段)")
        appendLine()
        val dirNow = dir
        val disk = if (fileOn) {
            val own = dirNow?.let { dayFiles(it) }.orEmpty()
            val legacy = legacyDir?.let { dayFiles(it) }.orEmpty()
            // 旧目录里的排在前面(更早), 同名的以私有目录那份为准。
            (legacy + own).distinctBy { it.name }.sortedBy { it.name }
        } else emptyList()
        if (disk.isEmpty()) {
            synchronized(lines) { lines.forEach { appendLine("  ${it.text}") } }
        } else {
            disk.forEach { each ->
                appendLine("---- ${each.name} (${each.length()} bytes) ----")
                runCatching { append(each.readText()) }
            }
        }
        // 直播事件单列一段: 出门一趟回来问"几点重载过", 看这段就够。
        runCatching { eventsText() }.getOrDefault("").takeIf { it.isNotBlank() }?.let {
            appendLine()
            append("$it")
        }
        val crashes = dirNow?.let { crashLines(it) }.orEmpty()
        if (crashes.isNotEmpty()) {
            appendLine()
            appendLine("---- Crashes (${crashes.size}) ----")
            crashes.forEach { appendLine(it) }
        }
        // 备份导入/导出自己落盘的记录: 导入会重启进程, 内存日志正好断在那里。
        val trace = listOfNotNull(dirNow, legacyDir).map { File(it, "restore-trace.log") }.firstOrNull { it.exists() }
        if (trace != null) {
            appendLine()
            appendLine("---- restore-trace.log (${trace.length()} bytes) ----")
            runCatching { append(trace.readText()) }
        }
    }

    /** 今天和昨天的日志文件, 老到新。 */
    private fun dayFiles(dirNow: File): List<File> = runCatching {
        dirNow.listFiles { f -> f.name.startsWith("aerial-diag-") && f.name.endsWith(".log") }
            ?.sortedBy { it.name }?.toList().orEmpty()
    }.getOrDefault(emptyList())

    /** 今天的日志文件, 一天一个; 换天时顺手把更早的那份删掉。 */
    private fun dailyFile(now: Long): File? {
        if (!fileOn) return null
        val d = dir ?: return null
        val key = currentDay(now)
        if (key == dayKey) dayFile?.let { return it }
        return synchronized(this) {
            if (key == dayKey && dayFile != null) {
                dayFile
            } else {
                File(d, "aerial-diag-$key.log").also {
                    dayKey = key
                    dayFile = it
                    pruneDays(d, key)
                }
            }
        }
    }

    private fun currentDay(now: Long): String {
        val cached = dayCached
        if (cached != null && now - dayCachedAt < 30_000L) return cached
        return synchronized(this) {
            if (dayCached != null && now - dayCachedAt < 30_000L) dayCached!!
            else dayStamp.format(Date(now)).also { dayCached = it; dayCachedAt = now }
        }
    }

    /** 只留今天和昨天, 更早的整份删掉 —— 这就是"24 小时循环"在磁盘上的形态。 */
    private fun pruneDays(d: File, today: String) {
        val keep = setOf(today, olderKey(today))
        runCatching {
            d.listFiles()?.forEach { f ->
                val name = f.name
                if (!name.startsWith("aerial-diag") || !name.endsWith(".log")) return@forEach
                val day = Regex("aerial-diag-(\\d{8})\\.log").find(name)?.groupValues?.get(1)
                // 认不出日期的是旧版留下的 aerial-diag.log / .old.log, 一并清掉。
                if (day == null || day !in keep) f.delete()
            }
        }
    }

    private fun olderKey(today: String): String = runCatching {
        java.time.LocalDate.parse(today, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
            .minusDays(1)
            .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
    }.getOrDefault(today)
}
