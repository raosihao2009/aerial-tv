package app.orchard.tv.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.edit
import app.orchard.tv.BuildConfig
import app.orchard.tv.guard.GuardSetup
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Whether a newer release is out (issues #24, #76). Most owners stay on the version they installed
 * and report bugs fixed long ago. Aerial reads the latest release's tag from the
 * website (reachable in China, unlike GitHub); Control Center then names the new version and says
 * how to update. Nothing is downloaded or installed in the background: only when the owner picks
 * "Download and Install" does Aerial fetch the new APK and hand it to the system installer
 * ([install]).
 *
 * Home and network events check at most every three hours; opening Control Center shortens that
 * to ten minutes. Failed checks retry while Home is in front (#912).
 */
object Updates {
    private const val TAG = "AerialUpdates"
    private const val PREFS = "updates"
    /**
     * 更新源换成这个 fork 自己的 GitHub Release: `/releases/latest/download/<资产名>` 是固定地址,
     * 永远指向最新一版, 所以出包时只要在 Release 上挂好资产, 应用这边的地址不用跟着改。
     * 上游那两个地址(aerial.qust.me 和 pengchujin 的仓库)给的是 app.orchard.tv 的包, 装不上也装不动。
     */
    private const val BASE = "https://github.com/raosihao2009/aerial-tv/releases/latest/download"
    private const val VERSION_URL = "$BASE/version.txt"
    /**
     * 版本号的兜底地址: Release 资产偶尔因为 TLS 握手/解析卡住而失败(表现为"有时候检查更新失败"),
     * 再挂一份同内容文件在仓库 update 分支根部, 走 raw 直链(和 github.com 不是同一条链路)。
     * 顺序即优先级, 每条各试两次。
     */
    private const val VERSION_RAW = "https://raw.githubusercontent.com/raosihao2009/aerial-tv/update/version.txt"
    private val VERSION_SOURCES = listOf("Release" to VERSION_URL, "raw" to VERSION_RAW)
    private const val NOTES_URL = "$BASE/notes.json"
    /**
     * 镜像清单: 与 version.txt 同处放一个小文件, 每行 "<flavor> <url>"。GitHub 的 APK 在国内往往
     * 只有几十 KB/s, 6.5MB 要等好几分钟; 清单里给一个国内可达的地址, 优先从它下, 失败再回 GitHub。
     */
    private const val MIRROR_URL = "$BASE/mirror.txt"
    /** 镜像清单的另一份副本(update 分支): Release 那头偶尔读不到时还有一条国内能通的 raw 地址。 */
    private const val MIRROR_RAW = "https://raw.githubusercontent.com/raosihao2009/aerial-tv/update/mirror.txt"

    /**
     * A source that delivers less than [STALL_BYTES] in [STALL_MS] is given up for the next one
     * (issue #390). A slow but moving download (about 8 KB/s and up) still finishes.
     */
    private const val STALL_MS = 30_000L
    private const val STALL_BYTES = 256 * 1024L

    /** 下载太久没有进展就当它死了: 期间再按"下载"会重新开始, 不会一直锁在 0%。 */
    private const val STALL_GIVE_UP_MS = 60_000L
    private const val STALE_REPRESS_MS = 12_000L

    /** 下载源不回 Content-Length 时, 用来把"已下多少"折算成百分比的近似大小。 */
    private const val NOMINAL_APK_BYTES = 8_000_000L
    const val CONTROL_CENTER_MAX_AGE_MS = UpdateCheck.PANEL_MAX_AGE_MS

    /** This build's version, e.g. "1.17" (the guard build's "-guard" left off). */
    val current: String = BuildConfig.VERSION_NAME.substringBefore('-')

    /** The newer release's version, or null when this is the latest (or nothing is known yet). */
    var available by mutableStateOf<String?>(null); private set

    /**
     * What the update brings: each release after this one up to [available], newest first. Empty
     * until read, and when the website has no notes (sites deployed before notes.json).
     */
    var notes by mutableStateOf<List<ReleaseNotes>>(emptyList()); private set
    private var readingNotes = false

    /** Where an install started from Control Center has got to. */
    var install by mutableStateOf<Install>(Install.Idle); private set

    /** 下载上一次动过的时间(elapsedRealtime), 用来判断它是不是已经卡死。 */
    private var progressAt = 0L
    private var downloading: Job? = null

    /**
     * 面板底部那行文案要用的两个事实: 最近一次检查的结论, 以及最近一次成功的时间。
     * 之前没新版时面板什么都不显示, 于是"确实是最新"和"连不上更新源"看起来一模一样。
     */
    var lastResult by mutableStateOf<String?>(null); private set
    var freshAt by mutableStateOf(0L); private set

    /**
     * 检查过而且连上了更新源, 但确实没有更新的版本。
     *
     * 判据用磁盘上的"上次检查成功时间", 不是本进程的结果: 面板打开时如果刚查过(10 分钟内)会
     * 直接跳过不发请求, 那样本进程永远没有结果, 状态就一直不显示 —— 3.5 上就是这个毛病。
     */
    val upToDate: Boolean get() = available == null && !checkFailed && freshAt > 0

    /** 正在查(面板上手动点了"检查更新"时用来显示"检查中…")。 */
    var checking by mutableStateOf(false); private set

    /** 最近一次检查失败了(源不通、超时、返回内容不对)。 */
    val checkFailed: Boolean get() = lastResult?.startsWith("failed") == true

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var checker: UpdateCheck? = null

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        available = prefs.getString("latest", null)?.takeIf { newer(it, current) }
        freshAt = prefs.getLong("checked_at", 0)
        checker = UpdateCheck(
            scope = scope,
            lastSuccess = { prefs.getLong("checked_at", 0) },
            read = { withContext(Dispatchers.IO) { readVersion() } },
            save = { latest, checkedAt ->
                prefs.edit { putString("latest", latest); putLong("checked_at", checkedAt) }
                if (available != latest) mirrorUrls = null
                available = latest.takeIf { newer(it, current) }
                loadNotes()
                // 查到有新版就把镜像清单先查好: 用户点「下载并安装」时不必再等 GitHub 那几秒超时,
                // 之前正是这一段让进度条在 0% 上挂了十几二十秒。
                if (available != null) warmMirror()
            },
            log = { Log.i(TAG, it); DiagLog.i("更新检查: $it") },
            onStart = { checking = true },
            onResult = { result ->
                checking = false
                lastResult = result
                if (result.startsWith("success")) freshAt = System.currentTimeMillis()
            },
        )
        loadNotes()
        if (available != null) warmMirror()
        // A download left from an install that has since finished or been given up.
        scope.launch(Dispatchers.IO) {
            apkFile(context).takeIf { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L }?.delete()
        }
    }

    /**
     * [force]: now, however recently it last looked (ConfigReceiver's check_update). Opening Control
     * Center asks with a shorter [maxAgeMs]: that is where the viewer looks for a new version, and a
     * release out an hour ago was not shown there for hours more (issue #301).
     */
    fun checkIfStale(
        force: Boolean = false,
        maxAgeMs: Long = UpdateCheck.MAX_AGE_MS,
        networkCameUp: Boolean = false,
    ) {
        checker?.check(force, maxAgeMs, networkCameUp, when {
            force -> "forced"
            networkCameUp -> "network"
            maxAgeMs == CONTROL_CENTER_MAX_AGE_MS -> "control center"
            else -> "home"
        })
    }

    fun diagnostics(): String = if (!started) "not initialized" else
        "known=${prefs.getString("latest", null) ?: "unknown"}; notes=${notes.size}; ${checker?.diagnostics}"

    /**
     * The release notes for [available], from the copy kept with the version it was read for, else
     * from the website (once per new version; the update page asks again while it has none). The
     * file is about 30 KB. A missing or bad file leaves the page as it was, without notes.
     */
    fun loadNotes() {
        if (!started) return
        val latest = available ?: run { notes = emptyList(); return }
        val zh = Locale.getDefault().language == "zh"
        if (prefs.getString("notes_for", null) == latest) {
            prefs.getString("notes", null)?.let { json ->
                runCatching { ReleaseNotesParser.parse(json, current, latest, zh) }.getOrNull()?.let { notes = it; return }
            }
        }
        if (readingNotes) return
        readingNotes = true
        scope.launch {
            val json = runCatching { withContext(Dispatchers.IO) { read(NOTES_URL) } }
                .onFailure { Log.i(TAG, "release notes: ${it.javaClass.simpleName}") }.getOrNull()
            readingNotes = false
            val parsed = json?.let { runCatching { ReleaseNotesParser.parse(it, current, latest, zh) }.getOrNull() }
            // Only notes that already have this release: the site's file may be a deploy behind.
            if (parsed.isNullOrEmpty() || parsed.first().version != latest || available != latest) return@launch
            prefs.edit { putString("notes", json); putString("notes_for", latest) }
            notes = parsed
        }
    }

    /** Whether version [a] comes after [b], comparing dotted numbers ("1.10" after "1.9"). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /**
     * 版本号的多路读取: 正本在 Release 资产, 兜底在仓库 update 分支的 raw 直链。
     * 每条各试两次(第一次短超时快速失败, 第二次放宽), 任一拿到内容即返回; 全失败才抛。
     * 为什么值得这么多: 国内到 github.com 的连接好坏参差, 单条 8 秒超时的偶发失败,
     * 在面板上就是一句"检查更新失败"。
     */
    private fun readVersion(): String {
        var last: Exception? = null
        for ((name, url) in VERSION_SOURCES) {
            for (attempt in 0..1) {
                try {
                    val text = read(url, fast = attempt == 0)
                    if (text.isNotEmpty()) {
                        if (attempt > 0) DiagLog.i("更新检查: $name 第 ${attempt + 1} 次成功")
                        return text
                    }
                } catch (e: IOException) {
                    last = e
                    DiagLog.i("更新检查: $name 第 ${attempt + 1} 次失败 ${e.javaClass.simpleName}" +
                        (e.cause?.let { "/${it.javaClass.simpleName}" } ?: ""))
                }
            }
        }
        throw last ?: IOException("no version source answered")
    }

    private fun read(url: String, fast: Boolean = false): String {
        val c = URL(url).openConnection() as HttpURLConnection
        LegacyUpdateTls.configure(appContext, c)
        c.connectTimeout = if (fast) 6_000 else 10_000
        c.readTimeout = if (fast) 6_000 else 12_000
        c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-cache")
        return try {
            c.inputStream.use { it.readBytes().decodeToString().trim() }
        } finally {
            c.disconnect()
        }
    }

    // ---- Installing the new version from Control Center ----

    sealed interface Install {
        data object Idle : Install
        data class Downloading(val fraction: Float) : Install
        /** A session was submitted; neither a result nor a visible confirmation is known yet. */
        data object Waiting : Install
        /** The system installer is open; it asks the owner and replaces Aerial, which restarts. */
        data object Opened : Install
        /** Aerial may not install apps yet; the TV's setting for it is open. */
        data object NeedsPermission : Install
        /** Android 6 firmware requires storage access before the APK can be saved. */
        data object NeedsStoragePermission : Install
        data class Failed(val reason: Failure) : Install
    }

    enum class Failure {
        /** Neither the website nor GitHub gave a complete APK. */
        Download,
        /** The TV lets no app hand an APK to its installer, or has no installer to hand it to. */
        Blocked,
        /** This copy is not the official package or not signed with the official key. */
        NotOfficial,
        /** The downloaded APK is no newer than this one (the website not yet updated). */
        NotNewer,
    }

    /**
     * 能不能用这条更新链路。上游比的是写死的官方包名, 这个 fork 的包名是 app.orchard.tb, 那样写
     * 等于永远 false(点了下载也只会回一句"不是官方包")。改成跟着本包的 applicationId 走:
     * 只要装的确实是这份构建, 更新源给的也是它自己的包, 就允许。
     */
    fun canInstall(context: Context): Boolean = context.packageName == BuildConfig.APPLICATION_ID

    /**
     * 自编译用的判定: 它只是把自己包里的 APK 重装回自己, 不下载、不跨包名, 所以和
     * [canInstall] 的"是不是官方包"无关 —— 之前借用 canInstall, 而这个 fork 的包名是
     * app.orchard.tb, 于是永远等于 false, 自编译一次都没跑过(日志: 跳过(没有安装权限))。
     * 这里只要求系统允许安装未知来源。
     */
    fun canReinstallSelf(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /**
     * Downloads this flavor's APK of the latest release and installs it. Android 9 and later, a
     * PackageInstaller session first (installBySession), as only a session can carry the dex
     * metadata that has the system compile Aerial as it installs. Where the session fails, the system
     * installer by ACTION_VIEW, as before Android 9: TCL's verifier (docs/devices/tcl-mt5879-cn.md)
     * passes only installs that go through com.android.packageinstaller, as Dangbei's do, and an
     * ACTION_VIEW on the APK makes the installer itself the one installing. The APK must be this package, signed
     * with this copy's key and no older, or the installer would only fail with a less clear message.
     */
    fun install(context: Context) {
        // 下载卡住时以前是死锁: 状态一直停在 Downloading(0f/100%), 再按也直接被这里挡回去。
        // 现在盯着"多久没动过", 太久就当上一次已经死掉, 重新来一次。
        val now = SystemClock.elapsedRealtime()
        if (install is Install.Downloading) {
            if (now - progressAt < STALE_REPRESS_MS) return
            DiagLog.i("更新: 上一次下载 ${(now - progressAt) / 1000} 秒没有进展, 重新开始")
        }
        if (install == Install.Opened) return
        // A second press while the first session's confirmation is awaited (#1004: two sessions).
        if (awaiting != null) return
        val app = context.applicationContext
        lastApp = app
        if (!canInstall(app)) { install = Install.Failed(Failure.NotOfficial); return }
        if (LegacyUpdateStorage.needsPermission(app)) {
            install = Install.NeedsStoragePermission
            LegacyUpdateStorage.requestPermission(context)
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            // Xiaomi MiTV-MFTR0 has this page; TCL MT5879 does not (tools/install.sh grants it there).
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(settings) }.isSuccess) {
                install = Install.NeedsPermission
                return
            }
            // No page to allow it on (Xiaomi MiTV-MFTP0, Android 9, has only Xiaomi's own settings:
            // "this TV does not let apps install apps", issue #284). Firmware installers often let
            // it through or ask themselves, so the download goes ahead and the installer decides.
            Log.i(TAG, "not allowed to install and no page to allow it on; trying the installer anyway")
        }
        install = Install.Downloading(0f)
        progressAt = now
        DiagLog.i("更新: 开始下载 ${available ?: "?"}")
        downloading?.cancel()
        downloading = scope.launch {
            val file = apkFile(app)
            // 下载 60 秒一点没动就放弃: 以前这种卡住没有任何兜底, 界面就永远停在 0%。
            val watchdog = launch {
                while (isActive) {
                    kotlinx.coroutines.delay(10_000)
                    val idle = SystemClock.elapsedRealtime() - progressAt
                    if (idle >= STALL_GIVE_UP_MS) {
                        DiagLog.i("更新: 下载 ${idle / 1000} 秒没有进展, 放弃")
                        if (install is Install.Downloading) install = Install.Failed(Failure.Download)
                        break
                    }
                }
            }
            fun stopWatchdog() { watchdog.cancel() }
            val ready = runCatching {
                withContext(Dispatchers.IO) {
                    // A download kept from a moment ago, when the owner backed out of the installer.
                    verify(app, file)?.takeIf { testApk == null && it.versionName?.substringBefore('-') == available }
                        ?: downloadFrom(app, file)
                }
            }.onFailure {
                // 以前这里一出异常, 界面就永远停在"下载 100%": 现在至少有个结果。
                Log.w(TAG, "update download broke off", it)
                DiagLog.i("更新: 下载中断 ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()
            stopWatchdog()
            // Downloading 只代表"在下载"; 下载一结束就换成等待, 不再停在 100%。
            if (install is Install.Downloading) install = Install.Waiting
            DiagLog.i("更新: 下载完成, 准备交给安装会话")
            // 有些固件(当贝这台就是)会话提交后既不静默装完, 也不马上回"需要确认",
            // 界面就一直停在"正在等待系统安装…", 非要再按一次按钮才动。
            // 这里自己补一次: 6 秒没有任何会话在等, 就重新交一次, 最多两次。
            scheduleSessionKick()
            if (ready == null) {
                DiagLog.i("更新: 没有可用的 APK, 放弃")
                install = Install.Failed(Failure.Download)
                return@launch
            }
            val failure = when {
                !sameSigner(app, ready) -> Failure.NotOfficial
                versionCode(ready) < BuildConfig.VERSION_CODE -> Failure.NotNewer
                else -> null
            }
            if (failure != null) {
                Log.w(TAG, "not installing: $failure")
                DiagLog.i("更新: 不安装($failure)")
                if (failure != Failure.Download) file.delete()
                install = Install.Failed(failure)
                return@launch
            }
            DiagLog.i("更新: 校验通过, 交给安装会话 (${file.length()} 字节)")
            val opened = runCatching {
                if (Build.VERSION.SDK_INT >= 28) installBySession(app, file) else openInstaller(context, app, file)
            }.onFailure {
                Log.w(TAG, "install did not start", it)
                DiagLog.i("更新: 安装会话没起来 ${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault(false)
            if (!opened) {
                // 会话没起来就退回系统安装器, 让用户至少看到安装界面。
                if (!openInstaller(context, app, file)) install = Install.Failed(Failure.Blocked)
            }
        }
    }

    /**
     * From Android 13 the installer's ACTION_VIEW records the APK as a local file (packageSource 3),
     * and Android then restricts the app's settings (ACCESS_RESTRICTED_SETTINGS deny; seen on the
     * Android 13 TV emulator). On TCL T966D5 (Android 14) Home Guard was off after every update made
     * this way, and stayed on after one pushed by 多屏互动 (issue #723). A session that says where
     * the APK came from is not restricted; the system still asks the owner to confirm. Where the
     * firmware turns the session down, the installer's ACTION_VIEW as before (UpdateResult).
     *
     * The session also carries the new APK's dex metadata (assets/aerial-dm, see app/build.gradle.kts)
     * as aerial.dm, from Android 9: the system then compiles Aerial with its Baseline Profile as it
     * installs (Xiaomi MiTV-MFTR0: status speed-profile, reason install-dm). Without it the update ran
     * uncompiled until the next one, its main thread about twice as slow (docs/PERF-2026-10-30.md).
     * Should a firmware refuse the metadata, the APK alone once more ([withDm]).
     */
    @RequiresApi(28)
    private fun installBySession(app: Context, file: File, withDm: Boolean = true): Boolean = runCatching {
        val dm = if (withDm) dexMetadata(file) else null
        val id = commitSession(app, file, dm, Intent(app, UpdateResult::class.java).putExtra(EXTRA_WITH_DM, dm != null))
        Log.i(TAG, "install session $id committed for ${file.length()} bytes, dex metadata ${dm?.size ?: "none"}")
        DiagLog.i("更新: 会话 $id, dex 元数据 ${dm?.size ?: "无"}")
        // Hisense VIDAA_TV / MT9655 (Android 14, #1162) also accepts sessions without answering.
        // Silent updates need time to install and compile, not the short confirmation deadline.
        val timeout = if (mayInstallSilently(app)) SILENT_INSTALL_TIMEOUT_MS else ANSWER_TIMEOUT_MS
        awaitSession(app, id, timeout, "会话 $id 无应答")
        true
    }.getOrElse {
        if (withDm) {
            Log.w(TAG, "install session with dex metadata failed; without it", it)
            installBySession(app, file, withDm = false)
        } else {
            Log.w(TAG, "no install session; opening the installer", it)
            openInstaller(app, app, file)
        }
    }

    /**
     * The running APK over itself with its own dex metadata (SelfCompile): no download, the same
     * version. Without the metadata it would be pointless, so no fallback: a failure is only logged.
     * [auto]: nobody asked, so a confirmation page is not put in front of the app being watched.
     */
    @RequiresApi(28)
    internal fun reinstallSelf(app: Context, auto: Boolean) {
        val apk = File(app.applicationInfo.sourceDir)
        val dm = dexMetadata(apk) ?: return
        runCatching {
            val result = Intent(app, UpdateResult::class.java).putExtra(EXTRA_WITH_DM, true)
                .putExtra(EXTRA_SELF, if (auto) SELF_AUTO else SELF_ASKED)
            val id = commitSession(app, apk, dm, result)
            Log.i(TAG, "self compile: session $id committed, dex metadata ${dm.size}")
        }.onFailure {
            Log.w(TAG, "self compile: no session", it)
            DiagLog.i("自编译: 无会话(${it.javaClass.simpleName}: ${it.message})")
        }
    }

    /** Writes [file] (and [dm] beside it) into a new install session and commits it; returns its id. */
    @RequiresApi(28)
    private fun commitSession(app: Context, file: File, dm: ByteArray?, result: Intent): Int {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
            // Android 12 and later let an app update itself without the confirmation page, which some
            // firmwares do not have (Xiaomi MiTV-MFTR0: no CONFIRM_INSTALL activity, so the session
            // gave way to the installer's ACTION_VIEW and its dex metadata was lost). The owner has
            // just picked "Download and Install". Where the system still wants a confirmation, it
            // answers STATUS_PENDING_USER_ACTION as before.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("aerial.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                if (dm != null) session.openWrite("aerial.dm", 0, dm.size.toLong()).use { out ->
                    out.write(dm)
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                GuardSetup.noteInstallerOpened(app)
                session.commit(PendingIntent.getBroadcast(app, id, result, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
        return id
    }

    /** This APK's own dex metadata for this Android, or null (SelfCompile). */
    internal fun ownDexMetadata(context: Context): ByteArray? = dexMetadata(File(context.applicationInfo.sourceDir))

    /**
     * The dex metadata the build put in [apk] for this Android (28.dm for 9 to 11, 31.dm from 12),
     * or null: an APK from before 1.72, or one that cannot be read.
     */
    private fun dexMetadata(apk: File): ByteArray? = runCatching {
        val name = if (Build.VERSION.SDK_INT >= 31) "assets/aerial-dm/31.dm" else "assets/aerial-dm/28.dm"
        java.util.zip.ZipFile(apk).use { zip -> zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } }
    }.getOrNull()

    private const val EXTRA_WITH_DM = "app.orchard.tv.update.WITH_DM"
    private const val EXTRA_SELF = "app.orchard.tv.update.SELF"
    private const val SELF_ASKED = 1
    private const val SELF_AUTO = 2

    /**
     * The update's session while its answer or confirmation page is awaited, else null. Hisense VIDAA_TV
     * (Android 9, Amlogic T963, issue #1004) took the session and then showed nothing: no answer
     * in the log, no confirmation page, Home never paused. Before 1.72 the installer's ACTION_VIEW
     * worked there. So a session whose page does not come up in time is abandoned for it.
     */
    private var awaiting: Awaiting? = null
    private class Awaiting(val id: Int, val confirmation: Boolean, val showConfirmation: Runnable?) {
        var homeLeft = false
        var timedOut = false
        lateinit var check: Runnable
    }
    private var homeInFront = false
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Sessions given up on: their answers, should they still come, are ignored. */
    private val abandoned = mutableSetOf<Int>()

    /** 没有应答时多久补交一次会话。 */
    private const val SESSION_KICK_MS = 6_000L
    /** From the commit to the system's answer; it comes within a second on the TVs at hand. */
    private const val ANSWER_TIMEOUT_MS = 8_000L
    /**
     * Android 12+ may compile and replace the app without any intermediate answer or page.
     * 6.5 MB 的 APK 复制 + 提交, 30 秒足够; 以前给到 120 秒, 固件其实在等人按的时候,
     * 界面就干等两分钟"正在等待系统安装…", 看着就是"不会自动装"。
     */
    private const val SILENT_INSTALL_TIMEOUT_MS = 30_000L
    /**
     * Whether Android may install the update without asking: from 12, and only for the app that
     * installed Aerial last (USER_ACTION_NOT_REQUIRED). Installed by adb (#1162's Hisense: none) or
     * by the system installer, it always asks, and an answer is due as soon as before 12.
     */
    private fun mayInstallSilently(app: Context): Boolean = Build.VERSION.SDK_INT >= 31 && runCatching {
        app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName == app.packageName
    }.getOrDefault(true)

    /** From starting the confirmation page to Home pausing beneath it. */
    private const val CONFIRM_TIMEOUT_MS = 4_000L

    /** Wait for an answer, or for a requested confirmation page to pause Home. */
    private fun awaitSession(
        app: Context, id: Int, ms: Long, why: String,
        confirmation: Boolean = false, showConfirmation: Runnable? = null,
    ) {
        awaiting?.let { main.removeCallbacks(it.check) }
        val a = Awaiting(id, confirmation, showConfirmation)
        a.check = Runnable {
            if (awaiting !== a || (a.confirmation && a.homeLeft)) return@Runnable
            a.timedOut = true
            // Do not open an installer over another app or while the TV is asleep —— 但也不能就这么
            // 把它忘了: 以前这一下直接 return, 定时器不再重排, 界面就永远停在"正在等待系统安装…"。
            if (!homeInFront) {
                main.postDelayed(a.check, 3_000)
                return@Runnable
            }
            DiagLog.i("更新: 会话 $id ${ms / 1000}s 无应答($why), 交回安装器")
            giveUpOnSession(app, id, why)
        }
        awaiting = a
        install = Install.Waiting
        cancelSessionKick()
        DiagLog.i("更新: 会话 $id 已提交, 等系统应答最多 ${ms / 1000}s${if (confirmation) " (确认页)" else ""}")
        // A confirmation requested while away is opened on resume; its timer starts then.
        if (showConfirmation == null) main.postDelayed(a.check, ms)
    }

    private fun stopAwaiting(id: Int) {
        val a = awaiting?.takeIf { it.id == id } ?: return
        main.removeCallbacks(a.check)
        awaiting = null
    }

    private fun giveUpOnSession(app: Context, id: Int, why: String) {
        stopAwaiting(id)
        abandoned += id
        runCatching { app.packageManager.packageInstaller.abandonSession(id) }
        Log.w(TAG, "$why; abandoned, opening the installer")
        DiagLog.i("更新: $why; 放弃, 走安装器")
        val file = apkFile(app)
        if (!file.isFile || !openInstaller(app, app, file)) install = Install.Failed(Failure.Blocked)
    }

    /** The session's answer (UpdateResult): the owner's confirmation, or the installer instead. */
    internal fun onSessionResult(context: Context, intent: Intent) {
        val app = context.applicationContext
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val self = intent.getIntExtra(EXTRA_SELF, 0)
        if (self != 0) return onSelfResult(app, intent, status, auto = self == SELF_AUTO)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id in abandoned) {
            Log.i(TAG, "session $id answered ($status) after it was given up")
            return
        }
        DiagLog.i("更新: 会话 $id 应答 status=$status")
        stopAwaiting(id)
        cancelSessionKick()
        sessionKicks = 0
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) return giveUpOnSession(app, id, "会话 $id 需要确认但无页面")
                if (homeInFront) openConfirmation(app, id, confirm)
                else awaitSession(
                    app, id, CONFIRM_TIMEOUT_MS, "会话 $id 确认页未弹出",
                    confirmation = true, showConfirmation = Runnable { openConfirmation(app, id, confirm) },
                )
            }
            PackageInstaller.STATUS_SUCCESS -> install = Install.Idle // normally this process is replaced
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "update not confirmed")
                install = Install.Idle
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val file = apkFile(app)
                // A firmware that turns the dex metadata down (INSTALL_FAILED_BAD_DEX_METADATA):
                // the APK alone in a new session.
                if (intent.getBooleanExtra(EXTRA_WITH_DM, false) && message.orEmpty().contains("DEX_METADATA", ignoreCase = true) &&
                    Build.VERSION.SDK_INT >= 28 && file.isFile
                ) {
                    Log.w(TAG, "install session refused the dex metadata ($message); without it")
                    if (installBySession(app, file, withDm = false)) return
                }
                Log.w(TAG, "install session failed ($status, $message); opening the installer")
                DiagLog.i("更新: 会话失败($status, $message); 走安装器")
                if (!file.isFile || !openInstaller(app, app, file)) install = Install.Failed(Failure.Blocked)
            }
        }
    }

    private fun openConfirmation(app: Context, id: Int, confirm: Intent) {
        runCatching { app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onSuccess {
            DiagLog.i("更新: 会话 $id 需要确认; 页面已打开")
            awaitSession(app, id, CONFIRM_TIMEOUT_MS, "会话 $id 确认页未弹出", confirmation = true)
        }.onFailure {
            // Some Android 13+ firmware has no CONFIRM_INSTALL page but accepts ACTION_VIEW (#827).
            Log.w(TAG, "could not ask to confirm the update; opening the installer", it)
            giveUpOnSession(app, id, "会话 $id 无确认页(${it.javaClass.simpleName})")
        }
    }

    /** The answer to [reinstallSelf]: never the installer by ACTION_VIEW, which would carry no metadata. */
    private fun onSelfResult(app: Context, intent: Intent, status: Int, auto: Boolean) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        // Arrives in the new process, once the replace is done.
        if (status == PackageInstaller.STATUS_SUCCESS) {
            DiagLog.i("自编译: 已安装, odex ${SelfCompile.odexBytes(app)?.div(1024)} KB")
            return
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && !auto) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null && runCatching { app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && id >= 0) runCatching { app.packageManager.packageInstaller.abandonSession(id) }
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.w(TAG, "self compile: not installed ($status, $message)")
        DiagLog.i("自编译: 未安装($status, $message)")
    }

    /** Resume failed checks; if the installer closed without replacing Aerial, reset its button. */
    fun onHomeResumed() {
        homeInFront = true
        checker?.setActive(true)
        if (install == Install.Opened) install = Install.Idle
        awaiting?.let {
            // Only returning from a requested confirmation releases the session's button.
            // Visiting another app while awaiting an answer must not forget the update.
            if (it.showConfirmation != null) it.showConfirmation.run()
            else if (it.confirmation && it.homeLeft) stopAwaiting(it.id)
            else if (it.timedOut) it.check.run()
        }
    }

    fun onHomePaused() {
        homeInFront = false
        checker?.setActive(false)
        awaiting?.takeIf { it.confirmation }?.let {
            it.homeLeft = true
            install = Install.Opened
        }
    }

    /** Back to the button, when the update page is left. */
    fun resetInstall() {
        main.removeCallbacks(sessionKick)
        sessionKicks = 0
        if (awaiting == null && install !is Install.Downloading) install = Install.Idle
    }

    /**
     * 会话提交后没人应答时, 自己再交一次(不再下载, 直接用已经下好并校验过的那个 APK)。
     * 以前这里没有任何兜底: 固件不回话, 界面就永远停在"正在等待系统安装…", 只能靠再按一次。
     */
    private var lastApp: Context? = null
    private var sessionKicks = 0
    private val sessionKick = Runnable {
        val context = lastApp ?: return@Runnable
        if (install != Install.Waiting) return@Runnable
        if (awaiting != null) return@Runnable
        if (sessionKicks >= 2) {
            DiagLog.i("更新: 重新交会话两次都没应答, 不再重试")
            return@Runnable
        }
        sessionKicks++
        DiagLog.i("更新: 等待安装没有应答, 重新交一次会话 (#$sessionKicks)")
        install(context)
    }

    private fun scheduleSessionKick() {
        main.removeCallbacks(sessionKick)
        main.postDelayed(sessionKick, SESSION_KICK_MS)
    }

    /** 有会话真的在等了(或已经有结果), 就不必再补。 */
    private fun cancelSessionKick() {
        main.removeCallbacks(sessionKick)
    }

    private fun apkFile(context: Context): File {
        // Android 5 and 6 installers read a file:// URI, so there the APK sits where they can read it.
        val dir = if (Build.VERSION.SDK_INT >= 24) context.cacheDir else context.externalCacheDir ?: context.cacheDir
        return File(File(dir, "updates"), "aerial.apk")
    }

    /**
     * For testing the whole update on a TV before a release (ConfigReceiver's update_test): the APK
     * pushed to Android/data/app.orchard.tv/files/update-test.apk stands in for the download.
     */
    @Volatile var testApk: File? = null

    /** 缓存住的镜像清单; 换版本时清掉, 免得一直用上一版的地址。 */
    @Volatile private var mirrorUrls: Map<String, String>? = null

    /** 后台把镜像地址查好, 只查一次(版本变了会清空缓存再查)。 */
    private fun warmMirror() {
        if (mirrorUrls != null) return
        scope.launch(Dispatchers.IO) {
            runCatching { mirrorFor(BuildConfig.FLAVOR) }
            DiagLog.i("更新: 镜像清单已预取 ${if (mirrorUrls == null) "无" else "有"}")
        }
    }

    private fun mirrorFor(flavor: String): String? {
        val cached = mirrorUrls ?: runCatching {
            readMirror().lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split(' ', limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }
                .toMap()
        }.onFailure { Log.i(TAG, "mirror: ${it.javaClass.simpleName}") }.getOrNull()
            ?.also { mirrorUrls = it }
            ?: return null
        return cached[flavor]
    }

    /** 镜像清单: Release 的 mirror.txt 与 update 分支的副本各试两次, 读不到就按"没有镜像"处理。 */
    private fun readMirror(): String {
        val sources = listOf("Release" to MIRROR_URL, "raw" to MIRROR_RAW)
        var last: IOException? = null
        for ((name, url) in sources) {
            for (attempt in 0..1) {
                try {
                    val text = read(url, fast = attempt == 0)
                    if (text.isNotEmpty()) return text
                } catch (e: IOException) {
                    last = e
                    DiagLog.i("更新下载: 镜像清单 $name 第 ${attempt + 1} 次失败 ${e.javaClass.simpleName}")
                }
            }
        }
        throw last ?: IOException("no mirror list answered")
    }

    /**
     * 有镜像就只走镜像一条路, 没有才走 GitHub。
     *
     * 3.22 起是 GitHub 先开跑、查到镜像清单再开第二路竞速, 谁先下完用谁。在这台低端的
     * Allwinner 上, 两路 TLS 会把带宽对半分、各自写一个 6.5MB 临时文件, 胜出的那一路还要再
     * copy 一份到正式文件 —— 等于同一份数据写两遍盘, 比单路慢出一截。镜像清单本来就是国内
     * CDN, 单路就能吃满带宽, 所以现在先等清单: 查到就只下镜像, 失败/卡死再回落 GitHub。
     */
    private suspend fun downloadFrom(context: Context, file: File): PackageInfo? {
        testApk?.takeIf { it.isFile }?.let { test ->
            file.parentFile?.mkdirs()
            test.copyTo(file, overwrite = true)
            if (Build.VERSION.SDK_INT < 24) file.setReadable(true, false)
            return verify(context, file)
        }
        val name = "AerialTV-${BuildConfig.FLAVOR}.apk"
        var best = -1
        val report: suspend (Int) -> Unit = { pct ->
            if (pct > best) {
                best = pct
                withContext(Dispatchers.Main) {
                    if (install is Install.Downloading) {
                        // 100% 时立刻转成"等待", 后面还有校验和安装, 不再把 100% 挂在那儿。
                        install = if (pct >= 100) Install.Waiting else Install.Downloading(pct / 100f)
                    }
                }
            }
        }
        val github = "$BASE/$name"
        val mirror = runCatching { mirrorFor(BuildConfig.FLAVOR) }
            .onFailure { Log.i(TAG, "mirror lookup: ${it.javaClass.simpleName}") }
            .getOrNull()
            ?.takeIf { it != github }
        if (mirror == null) DiagLog.i("更新下载: 没有镜像清单, 只走 GitHub")
        val order = if (mirror != null) listOf("镜像" to mirror, "GitHub" to github) else listOf("GitHub" to github)
        for ((label, url) in order) {
            DiagLog.i("更新下载: 试$label 源")
            val started = SystemClock.elapsedRealtime()
            // GitHub 在国内多半连不上, 连接超时按 6 秒算, 不陪它等满 10 秒。
            val ok = runCatching { download(url, file, report, if (label == "GitHub") 6_000 else 10_000) }
                .onFailure {
                    Log.w(TAG, "could not download $url", it)
                    DiagLog.i("更新下载: $label 源失败 ${it.message ?: it.javaClass.simpleName}")
                }
                .isSuccess
            if (ok) {
                DiagLog.i("更新下载: $label 源下完, 用时 ${(SystemClock.elapsedRealtime() - started) / 1000}s")
                return verify(context, file)
            }
        }
        DiagLog.i("更新下载: 所有源都失败")
        file.delete()
        return null
    }

    private suspend fun download(
        url: String,
        file: File,
        onPercent: (suspend (Int) -> Unit)? = null,
        connectMs: Int = 10_000,
    ) {
        val directory = checkNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create update directory: $directory" }
        check(directory.canWrite()) { "Cannot write update directory: $directory" }
        val part = File(directory, file.name + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        LegacyUpdateTls.configure(appContext, c)
        c.connectTimeout = connectMs
        c.readTimeout = 20_000
        // A throttled route can keep the TLS handshake up and deliver a byte now and then, so no
        // read() ever blocks long enough to time out and the progress bar sits at 0% for good
        // (issue #390, FFALCON/TCL on China Unicom). So the bytes are counted, not each read timed.
        var windowAt = SystemClock.elapsedRealtime()
        var windowFrom = 0L
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${c.responseCode}")
            val total = c.contentLength.toLong()
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - windowAt >= STALL_MS) {
                            if (done - windowFrom < STALL_BYTES) error("stalled: ${done - windowFrom} bytes in ${(now - windowAt) / 1000} s")
                            windowAt = now
                            windowFrom = done
                        }
                        // 长度未知(分块传输)时按 APK 的大致大小估, 免得进度条一直停在 0%。
                        val percent = if (total > 0) (done * 100 / total).toInt()
                        else ((done * 100 / NOMINAL_APK_BYTES).toInt()).coerceIn(0, 95)
                        if (onPercent != null && percent != shown) {
                            shown = percent
                            onPercent(percent)
                        }
                    }
                }
            }
            if (total > 0 && done != total) error("got $done of $total bytes")
            if (!part.renameTo(file)) error("could not keep the download")
            // The installer on Android 5 and 6 opens the file itself.
            if (Build.VERSION.SDK_INT < 24) file.setReadable(true, false)
        } finally {
            c.disconnect()
            part.delete()
        }
    }

    /** The APK's package info when it parses as this package, else null. */
    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File): PackageInfo? {
        if (!file.isFile) return null
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = runCatching { context.packageManager.getPackageArchiveInfo(file.path, flags) }.getOrNull() ?: return null
        return info.takeIf { it.packageName == context.packageName }
    }

    /** Whether [apk] is signed like this copy. If the archive's signers cannot be read, the installer decides. */
    @Suppress("DEPRECATION")
    private fun sameSigner(context: Context, apk: PackageInfo): Boolean {
        val pm = context.packageManager
        fun signers(info: PackageInfo?): Set<String>? {
            val sigs = if (Build.VERSION.SDK_INT >= 28) info?.signingInfo?.apkContentsSigners else info?.signatures
            return sigs?.takeIf { it.isNotEmpty() }?.map { it.toCharsString() }?.toSet()
        }
        val theirs = signers(apk) ?: return true
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val ours = signers(runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()) ?: return true
        return theirs == ours
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun openInstaller(context: Context, app: Context, file: File): Boolean {
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        else Uri.fromFile(file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            GuardSetup.noteInstallerOpened(app)
            context.startActivity(intent)
            install = Install.Opened
            Log.i(TAG, "installer opened for ${file.length()} bytes")
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no installer", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "installer refused", e)
            false
        }
    }
}
