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
 * logcat. The last lines are kept in memory and printed by
 *   adb shell dumpsys activity app.orchard.tv/.MainActivity
 * and, in a diagnostic build (`./gradlew -Pdiag ...`) or after
 *   adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez diag true
 * also written to Android/data/app.orchard.tv/files/aerial-diag.log, which `adb pull` can fetch.
 */
object DiagLog {
    private const val TAG = "AerialDiag"
    private const val KEEP = 600
    private const val FILE_LIMIT = 1 shl 20

    private val lines = ArrayDeque<String>()
    // Made on the first line written to the file, so a release build without diag has no thread.
    private val writer by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "AerialDiag").apply { isDaemon = true } }
    }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    /** Whether lines go to the file as well; set from ConfigReceiver, on in a diagnostic build. */
    fun enabled(context: Context): Boolean = BuildConfig.DIAG || prefs(context).getBoolean("diag", false)

    fun setEnabled(context: Context, on: Boolean) {
        setOption(context, "diag", on)
        if (on) init(context) else file = null
    }

    /**
     * Experiments for a black Home, set over ADB (ConfigReceiver) and read when Home starts:
     * mode_switch lets Home change the display's refresh rate (off in a diagnostic build, the only
     * Android 12+ display change Aerial makes); soft_layer draws Home in software, past the GPU.
     */
    fun modeSwitch(context: Context): Boolean = prefs(context).getBoolean("mode_switch", !BuildConfig.DIAG)
    fun softLayer(context: Context): Boolean = prefs(context).getBoolean("soft_layer", false)

    /**
     * An experiment for issue #654 (Redmi X50, MiTV-NSOP0, Novatek): the apps fading into the
     * screensaver each in a buffer of their own rather than with their alpha laid on every drawing.
     * Their frame and shadow are cut to the tile while they fade.
     */
    fun saverFadeOffscreen(context: Context): Boolean = prefs(context).getBoolean("saver_fade_offscreen", false)
    fun setOption(context: Context, key: String, on: Boolean) = prefs(context).edit().putBoolean(key, on).apply()
    private fun prefs(context: Context) = context.getSharedPreferences("system", Context.MODE_PRIVATE)

    /** Lines go to this file only; set on the first [init] even when the log file itself is off. */
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        if (dir == null) dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (file != null || !enabled(context)) return
        val d = dir ?: return
        file = File(d, "aerial-diag.log")
        i("log file ${file?.absolutePath}")
    }

    fun i(message: String) {
        Log.i(TAG, message)
        // SimpleDateFormat is not thread-safe; lines come from the main thread and the player's.
        val line = synchronized(lines) {
            "${stamp.format(Date())} +${SystemClock.uptimeMillis()} $message".also {
                lines.addLast(it)
                while (lines.size > KEEP) lines.removeFirst()
            }
        }
        val f = file ?: return
        writer.execute {
            runCatching {
                if (f.length() > FILE_LIMIT) f.renameTo(File(f.parentFile, "aerial-diag.old.log"))
                val fresh = !f.exists()
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
            val f = file
            val line = "${synchronized(lines) { stamp.format(Date()) }} crash on ${t.name}: ${Log.getStackTraceString(e)}"
            Log.e(TAG, line)
            if (f != null) runCatching { f.appendText(line + "\n") }
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

    /** The last [n] lines, oldest first. */
    fun recent(n: Int): List<String> = synchronized(lines) { lines.toList().takeLast(n) }

    fun dump(writer: PrintWriter) {
        writer.println("Aerial log (${device()}):")
        synchronized(lines) { lines.forEach { writer.println("  $it") } }
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

    /** 崩溃行: aerial-crash.log 全部, 加诊断日志里可能残留的 crash 行。 */
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
        val f = file
        if (f != null) {
            listOf(f, File(dirNow, "aerial-diag.old.log")).filter { it.exists() }.forEach { each ->
                each.useLines { seq ->
                    collect(seq.filter { it.contains(" crash on ") }.joinToString("\n"))
                }
            }
        }
        records.takeLast(2)
    }.getOrDefault(emptyList())

    /**
     * 手机端「导出日志文件」: 设备信息、本进程内存里的日志, 以及已落盘的诊断日志与崩溃记录。
     * 日志文件没开时后面两段为空, 头部会说明。
     */
    fun exportText(): String = buildString {
        appendLine("Aerial log (${device()}):")
        if (file == null) appendLine("(诊断日志文件未开启, 下面只含本进程内存里的日志; 崩溃记录另见 crash 段)")
        appendLine()
        synchronized(lines) { lines.forEach { appendLine("  $it") } }
        val dirNow = dir ?: return@buildString
        val crashes = crashLines(dirNow)
        if (crashes.isNotEmpty()) {
            appendLine()
            appendLine("---- Crashes (${crashes.size}) ----")
            crashes.forEach { appendLine(it) }
        }
        for (name in listOf("aerial-diag.log", "aerial-diag.old.log")) {
            val each = File(dirNow, name).takeIf { it.exists() } ?: continue
            appendLine()
            appendLine("---- $name (${each.length()} bytes) ----")
            runCatching { append(each.readText()) }
        }
    }
}
