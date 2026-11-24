package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import app.orchard.tv.apps.AppCatalog
import app.orchard.tv.apps.FOLDER_KEY_PREFIX
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.AppNames
import app.orchard.tv.apps.BiliAccount
import app.orchard.tv.apps.BiliLogin
import app.orchard.tv.apps.BiliLoginState
import app.orchard.tv.apps.CustomIcons
import app.orchard.tv.apps.IconLibrary
import app.orchard.tv.apps.SourceDevice
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.apps.TileArtFactory
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.KidsLock
import app.orchard.tv.settings.Restricted
import app.orchard.tv.settings.RestrictedKind
import app.orchard.tv.settings.RestrictedRules
import app.orchard.tv.guard.HomeGuardService
import app.orchard.tv.guard.KidsWatch
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import app.orchard.tv.system.Diagnostics
import app.orchard.tv.system.currentNetwork
import app.orchard.tv.settings.FocusStyle
import app.orchard.tv.weather.Weather
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fi.iki.elonen.NanoHTTPD
import app.orchard.tv.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.security.SecureRandom

/**
 * The page a phone opens after scanning the TV's QR code: choose a wallpaper and the screensaver,
 * send photos, give an app's tile a picture of one's own, and name the weather's city. Choosing
 * pictures or typing a place with a remote is slow; with a phone it is not.
 *
 * It listens on the local network while Aerial runs (see [listen]), and every address carries a
 * The page is open to anyone on the local network: the address is just the TV's ip and port.
 */
class PhoneServer private constructor(private val context: Context, private val token: String, port: Int = PORT) : NanoHTTPD(port) {
    @Volatile var isSecure = false
    // 上一次报上来的手机页版本: 同版本只记一条, 免得每 1.5 秒的轮询都把日志刷满。
    @Volatile private var lastPageVersion = -1
    var httpsError: String? = null

    override fun serve(session: IHTTPSession): Response {
        // No token any more: the page answers on "/" so the address is just ip:port. The old
        // /<token>/ form is still accepted, for bookmarks made before this change.
        val prefix = "/$token"
        val uri = session.uri
        val path = uri.removePrefix(prefix).ifEmpty { "/" }
        return runCatching {
            // In Kids and Elders mode the page changes nothing without the parental PIN: anyone on
            // the network with the address (a child with a phone) could otherwise undo the mode.
            // The page sends it with each change once it is entered (header X-Aerial-Pin).
            // Turning the TV off undoes nothing, and is what a parent away from it may want most.
            if (session.method == Method.POST && path != "/api/kids/unlock" && path != "/api/power/off" && (Restricted.active || path.startsWith("/api/kids/")) &&
                path != "/api/kids/start" && !pinOk(session.headers["x-aerial-pin"])
            ) return json(state().put("saved", false).put("kidsLocked", true))
            when {
                // 页面绝对不能进浏览器缓存: 换版本后手机还在跑旧的那份 index.html, 导入判定的
                // 新旧逻辑就会混在一起, 明明修过的东西手机那头看不出变化。
                path == "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page()).apply {
                    addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                    addHeader("Pragma", "no-cache")
                }
                session.method == Method.POST && path == "/api/kids/unlock" -> {
                    val ok = pinOk(form(session)["pin"])
                    json(state().put("saved", ok).put("pinRight", ok))
                }
                session.method == Method.POST && path == "/api/kids/allow" -> {
                    val minutes = form(session)["minutes"]?.toIntOrNull()
                    onMain { Restricted.allow(minutes?.takeIf { it > 0 }); KidsWatch.poke() }
                    json(state().put("saved", true))
                }
                session.method == Method.POST && path == "/api/kids/config" -> kidsConfig(form(session))
                session.method == Method.POST && path == "/api/kids/leave" -> {
                    onMain { Restricted.suspend(); KidsWatch.poke() }
                    json(state().put("saved", true))
                }
                session.method == Method.POST && path == "/api/kids/start" -> {
                    val can = Restricted.enabled && Restricted.allowed.isNotEmpty()
                    if (can) onMain { Restricted.start(); KidsWatch.poke() }
                    json(state().put("saved", can))
                }
                path == "/api/state" -> json(state())
                // 手机端「更多」: 默认把内容回到页面里显示, 带 download=1 才作为附件下载。
                // 这两种是同一份文本, 区别只在响应头 —— 页面里看和存到手机上下载目录各走一条。
                path == "/api/diagnostics" -> textFile(Diagnostics.report(context), "aerial-diag",
                    download = queryOf(session.queryParameterString, "download") != null)
                path == "/api/log" -> textFile(app.orchard.tv.system.DiagLog.exportText(), "aerial-log",
                    download = queryOf(session.queryParameterString, "download") != null)
                // 手机端「更多 → 备份」: 下载整机备份(zip), 或上传一个备份包覆盖本机
                // 打包和下载拆成两步: 浏览器要等到响应头才会弹下载框, 若在同一步里边打边等,
                // 用户看到的就是「点了没反应」。先打包(页面显示进度), 打完再让他去取现成的包。
                path == "/api/backup/prepare" -> {
                    // fresh=1 表示"我要最新的一份", 不fresh 就用缓存里十分钟内打好的。
                    val fresh = session.parms["fresh"] ?: queryOf(session.queryParameterString, "fresh")
                    backupPrepare(fresh == "1")
                }
                path == "/api/backup/state" -> backupState()
                path == "/api/backup" -> backupDownload()
                session.method == Method.POST && path == "/api/restore" -> restoreBackup(session)
                // 恢复后进程自杀重启, 手机可能没收到那条回复: 按同一个 id 回查结果。
                path == "/api/restore-result" -> {
                    // 有的 NanoHTTPD 版本不把 GET 参数塞进 parms, 两种都试。
                    val id = session.parms["id"] ?: queryOf(session.queryParameterString, "id")
                    val found = app.orchard.tv.system.Backup.restoreState(context, id)
                    // 手机每次回查都留一笔: 到底是没回查、还是回查了却对不上 id, 一看就知道。
                    app.orchard.tv.system.Backup.trace(
                        context,
                        "回查: id=${id.ifEmpty { "-" }} -> ${found?.optString("state") ?: "not-found"}",
                    )
                    json(found ?: JSONObject().put("state", "unknown"))
                }
                // 手机端把它这一侧看到的每一步(上传状态、每轮回查结果)送回来, 落在 trace 里。
                // 服务端的成败和手机端的提示对不上时, 这段就是唯一能对齐两边的东西。
                session.method == Method.POST && path == "/api/restore-log" -> {
                    val len = session.headers["content-length"]?.toLongOrNull() ?: 0L
                    val body = if (len in 1..8192) readBody(session, len.toInt()) else ""
                    if (body.isNotEmpty()) app.orchard.tv.system.Backup.trace(context, "手机端导入过程:\n$body")
                    json(JSONObject().put("ok", true))
                }
                path.startsWith("/thumb/") -> thumb(path.removePrefix("/thumb/"))
                session.method == Method.POST && path.startsWith("/live-icon/") -> {
                    val id = path.removePrefix("/live-icon/")
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    val tmpPath = files["file"]
                    if (tmpPath != null) {
                        val src = java.io.File(tmpPath)
                        // 缩放保存
                        val bmp = android.graphics.BitmapFactory.decodeFile(src.absolutePath)
                        if (bmp != null) {
                            val scaled = if (bmp.width > 480) android.graphics.Bitmap.createScaledBitmap(bmp, 480, (480 * bmp.height / bmp.width), true) else bmp
                            val out = java.io.File(context.filesDir, "live_icon_$id.jpg")
                            val fos = java.io.FileOutputStream(out)
                            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos)
                            fos.close()
                        }
                    }
                    onMain { app.orchard.tv.HomeViewModel.notifyLiveSourcesChanged() }
                    json(state())
                }
                path.startsWith("/live-icon/") -> liveIcon(path.removePrefix("/live-icon/"))
                session.method == Method.POST && path == "/api/wallpaper" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Wallpapers.select(id) }
                    json(state())
                }
                // 手机端的检查更新: 和电视面板是同一套东西(Updates), 手机上只显示状态、按按钮;
                // 下载与安装仍在电视那头走包安装会话, 手机只轮询进度。
                path == "/api/update" && session.method == Method.GET -> json(updateState())
                session.method == Method.POST && path == "/api/update/check" -> {
                    onMain { Updates.checkIfStale(force = true) }
                    json(updateState())
                }
                session.method == Method.POST && path == "/api/update/install" -> {
                    onMain { Updates.install(context) }
                    json(updateState())
                }
                // Turn the TV off from the phone (issue #1615): standby through the guard, as the
                // power key; where the guard cannot, the page does not offer it ("canSleep").
                session.method == Method.POST && path == "/api/power/off" -> {
                    json(state().put("saved", HomeGuardService.sleep("phone page")))
                }
                session.method == Method.POST && path == "/api/screensaver" -> {
                    val mode = ScreensaverMode.of(form(session)["mode"])
                    onMain { Wallpapers.chooseScreensaver(mode) }
                    json(state())
                }
                // B 站账号: 手机上扫码登录, 与电视面板共用 BiliLogin / BiliAccount。
                path == "/api/bili" && session.method == Method.GET -> {
                    // 页面版本握手: 手机那头可能还开着上一版 index.html, 那份旧代码不是电视能改的,
                    // 只能告诉它「本机已经换了版本」, 让它自己重载一次, 换成本机包里这一份。
                    val pv = queryOf(session.queryParameterString, "pv").toIntOrNull()
                    val ver = app.orchard.tv.BuildConfig.VERSION_CODE
                    if (pv != null && pv != ver && pv != lastPageVersion) {
                        lastPageVersion = pv
                        app.orchard.tv.system.DiagLog.i("手机页版本 $pv 与本机 $ver 不一致, 已请手机页重载")
                    }
                    json(JSONObject()
                        .put("loggedIn", BiliAccount.loggedIn)
                        .put("uid", BiliAccount.uid ?: "")
                        .put("phase", BiliPhone.phase)
                        .put("hasQr", BiliPhone.qrUrl != null)
                        .put("ver", ver)
                        // 顺手把二维码里那条链接也带上: 手机页的「打开登录页」直接挂成 <a href>, 不用再问一次。
                        .put("url", BiliPhone.qrUrl ?: ""))
                }
                // 手机页点了「打开 B 站登录页」: 只记一条日志, 用来分清「点没点到新页面」和「外壳有没有真跳」。
                session.method == Method.POST && path == "/api/bili/open" -> {
                    val f = form(session)
                    app.orchard.tv.system.DiagLog.i(
                        "手机页点了打开登录页: 页面版本 ${f["pv"] ?: "未知"}, 地址 ${(f["url"] ?: "").take(120).ifEmpty { "(空)" }}"
                    )
                    json(JSONObject().put("ok", true))
                }
                session.method == Method.POST && path == "/api/bili" -> {
                    when (form(session)["action"]) {
                        "logout" -> {
                            BiliAccount.clear()
                            BiliPhone.reset()
                            json(JSONObject().put("ok", true).put("loggedIn", false))
                        }
                        else -> {
                            BiliPhone.start()
                            json(JSONObject().put("ok", true).put("phase", BiliPhone.phase))
                        }
                    }
                }
                path == "/api/bili/qr" -> biliQr()
                // 手机端「打开 B 站登录页」: 只回那条登录链接, 由手机自己决定用浏览器还是 App 打开。
                path == "/api/bili/qr-url" -> json(JSONObject().put("url", BiliPhone.qrUrl ?: ""))
                session.method == Method.POST && path == "/api/setting" -> {
                    // The few on/off settings the phone page shows, as Personalize does.
                    val f = form(session)
                    val on = f["on"] == "1"
                    val known = when (f["key"]) {
                        "wallpaperAlwaysMoves" -> { onMain { AerialSettings.chooseWallpaperAlwaysMoves(on) }; true }
                        "showWeather" -> { onMain { AerialSettings.chooseShowWeather(on) }; true }
                        "screensaverClock" -> { onMain { AerialSettings.chooseScreensaverClock(on) }; true }
                        "screensaverCaption" -> { onMain { AerialSettings.chooseScreensaverCaption(on) }; true }
                        "photoMinutes" -> {
                            val m = f["value"]?.toIntOrNull()
                            if (m == null || m !in AerialSettings.PHOTO_MINUTES) false
                            else { onMain { AerialSettings.choosePhotoMinutes(m); Wallpapers.rotateIfDue() }; true }
                        }
                        else -> false
                    }
                    json(state().put("saved", known))
                }
                // 手机页「壁纸与屏保」那一格最下面的屏保四项: 类型、航拍画质、开始时间、返回键。
                session.method == Method.GET && path == "/api/saver" -> {
                    val lang = queryOf(session.queryParameterString, "lang") ?: "zh"
                    json(SaverPanel.schema(context, lang))
                }
                session.method == Method.POST && path == "/api/saver" -> {
                    val f = form(session)
                    val key = f["key"].orEmpty()
                    val value = f["value"].orEmpty()
                    var ok = false
                    try {
                        onMain { ok = SaverPanel.set(context, key, value) }
                    } catch (e: Exception) {
                        app.orchard.tv.system.DiagLog.e("手机页屏保设置失败: $key=$value", e)
                    }
                    app.orchard.tv.system.DiagLog.i("手机页屏保: $key=$value -> saved=$ok")
                    json(state().put("saved", ok))
                }
                // 电视个性化面板里那些设置: 手机页照电视的 strings.xml 拿文案, 按电视同一套
                // choose* 写进去。壁纸/屏保/隐藏卡片不在这里, 手机页别的标签页已有。
                session.method == Method.GET && path == "/api/prefs" -> {
                    val lang = queryOf(session.queryParameterString, "lang") ?: "zh"
                    json(PhonePrefs.schema(context, lang))
                }
                session.method == Method.POST && path == "/api/prefs" -> {
                    val f = form(session)
                    var ok = false
                    onMain { ok = PhonePrefs.set(f["key"].orEmpty(), f["value"].orEmpty()) }
                    json(JSONObject().put("ok", ok))
                }
                session.method == Method.POST && path == "/api/focus" -> {
                    val style = FocusStyle.of(form(session)["style"])
                    onMain { AerialSettings.chooseFocusStyle(style) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/delete" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Wallpapers.deletePhoto(id) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/upload" -> upload(session)
                session.method == Method.POST && path == "/api/videos/send" -> video(session)
                session.method == Method.POST && path == "/api/videos/delete" -> {
                    val removed = ScreensaverVideos.delete(context, form(session)["id"].orEmpty())
                    if (removed) onMain { Wallpapers.videosChanged() }
                    json(state().put("removed", removed))
                }
                path.startsWith("/tile/") -> tile(java.net.URLDecoder.decode(path.removePrefix("/tile/"), "UTF-8"), own = session.parameters["art"]?.firstOrNull() == "own")
                // 儿童与长辈窗口里的卡片封面: 候选表里有直播卡片, 不在 loaded/pinned 里, /tile/ 找不到它们。
                path.startsWith("/kids/art/") -> kidsArt(java.net.URLDecoder.decode(path.removePrefix("/kids/art/"), "UTF-8"))
                path.startsWith("/library/") -> libraryPicture(java.net.URLDecoder.decode(path.removePrefix("/library/"), "UTF-8"), session.parameters["c"]?.firstOrNull())
                session.method == Method.POST && path == "/api/icon" -> icon(session)
                session.method == Method.POST && path == "/api/source/name" -> {
                    val f = form(session)
                    val source = AppCatalog.pinnedSources.firstOrNull { it.key == f["app"] }?.source
                    // On the main thread before answering, so the state sent back has the new name.
                    if (source != null) runBlocking(Dispatchers.Main) { SourceLooks.rename(source.id, f["name"]) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/app/name" -> {
                    val f = form(session)
                    val key = f["app"].orEmpty()
                    val source = AppCatalog.pinnedSources.firstOrNull { it.key == key }?.source
                    // On the main thread before answering, so the state sent back has the new name.
                    val saved = when {
                        source != null -> { runBlocking(Dispatchers.Main) { SourceLooks.rename(source.id, f["name"]) }; true }
                        AppCatalog.loaded.any { it.key == key } -> { runBlocking(Dispatchers.Main) { AppNames.rename(key, f["name"]) }; true }
                        else -> false
                    }
                    json(state().put("saved", saved))
                }
                session.method == Method.POST && path == "/api/layout" -> layout(form(session))
                session.method == Method.POST && path == "/api/source/device" -> {
                    val f = form(session)
                    val source = AppCatalog.pinnedSources.firstOrNull { it.key == f["app"] }?.source
                    // As from the tile's menu: a device names the tile after it, "" goes back to the plain input.
                    if (source != null) runBlocking(Dispatchers.Main) { SourceLooks.chooseDevice(source.id, SourceDevice.of(f["device"])) }
                    json(state().put("saved", source != null))
                }
                session.method == Method.POST && path == "/api/source/pin" -> {
                    val f = form(session)
                    val id = f["id"].orEmpty()
                    val pin = AppCatalog.pinSource
                    val known = AppCatalog.sources.any { it.id == id }
                    if (known && pin != null) runBlocking(Dispatchers.Main) { pin(id, f["pinned"] == "1") }
                    json(state().put("saved", known && pin != null))
                }
                session.method == Method.POST && path == "/api/folder/name" -> {
                    val f = form(session)
                    val id = f["id"].orEmpty()
                    val name = f["name"].orEmpty().trim().take(FOLDER_NAME_MAX)
                    val rename = AppCatalog.renameFolder
                    val known = AppCatalog.folders.any { it.folder?.id == id }
                    // On the main thread before answering, so the state sent back has the new name.
                    if (known && name.isNotEmpty() && rename != null) runBlocking(Dispatchers.Main) { rename(id, name) }
                    json(state().put("saved", known && name.isNotEmpty() && rename != null))
                }
                path == "/api/library/all" -> json(libraryCatalog())
                session.method == Method.POST && path == "/api/icon/pick" -> {
                    // One of the library's pictures or the app's own art for one app; either
                    // replaces a picture sent from the phone.
                    val f = form(session)
                    val pkg = f["app"].orEmpty()
                    val pick = when (f["pick"]) {
                        "library" -> IconLibrary.Pick.Library
                        "own" -> IconLibrary.Pick.Own
                        else -> null
                    }
                    val choice = f["choice"]?.takeIf { it.isNotEmpty() }
                    // Another app's picture, picked from the whole library (issue #1486).
                    val from = f["from"]?.takeIf { it.isNotEmpty() && it != pkg }
                    val ok = installed(pkg) && pick != null && when {
                        pick != IconLibrary.Pick.Library -> true
                        from != null -> choice != null && IconLibrary.catalog()[from].orEmpty().any { it.id == choice }
                        else -> IconLibrary.choices(pkg).any { choice == null || it.id == choice }
                    }
                    // A borrowed picture is fetched before the pick is kept, so a failed download
                    // leaves the tile as it was rather than on a picture that is not there.
                    if (ok && from != null) {
                        val picture = IconLibrary.catalog()[from].orEmpty().first { it.id == choice }
                        if (!IconLibrary.fetch(picture)) return@runCatching json(state().put("saved", false))
                    }
                    if (ok) {
                        CustomIcons.clear(pkg)
                        // Kept even for the library, which most apps use anyway: a firmware app
                        // drawn as Aerial's own tile takes it only when picked.
                        IconLibrary.choose(pkg, pick, choice, from)
                        runBlocking(Dispatchers.Main) { CustomIcons.changed(); IconLibrary.changed() }
                    }
                    json(state().put("saved", ok))
                }
                session.method == Method.POST && path == "/api/icon/reset" -> {
                    val pkg = form(session)["app"].orEmpty()
                    if (installed(pkg)) { CustomIcons.clear(pkg); onMain { CustomIcons.changed() } }
                    json(state())
                }
                session.method == Method.POST && path == "/api/weather/city" -> {
                    val name = form(session)["name"].orEmpty()
                    val found = name.isNotBlank() && runBlocking(Dispatchers.Main) { Weather.choose(name) }
                    json(state().put("found", found))
                }
                session.method == Method.POST && path == "/api/weather/auto" -> {
                    onMain { Weather.useNetworkLocation() }
                    json(state())
                }
                session.method == Method.POST && path == "/api/weather/placename" -> {
                    val name = form(session)["name"].orEmpty()
                    runBlocking(Dispatchers.Main) { Weather.setCustomPlaceName(name) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/weather/location" -> {
                    val f = form(session)
                    val lat = f["lat"]?.toDoubleOrNull()
                    val lon = f["lon"]?.toDoubleOrNull()
                    val found = lat != null && lon != null && runBlocking(Dispatchers.Main) { Weather.chooseByLocation(lat, lon) }
                    json(state().put("found", found))
                }
                session.method == Method.POST && path == "/api/live-source" -> {
                    val f = form(session)
                    val prefs = context.getSharedPreferences("live_sources", Context.MODE_PRIVATE)
                    val id = f["id"].orEmpty()
                    if (id.isNotEmpty()) {
                        if (id.startsWith("preset_")) {
                            // 预设源: 存name/url覆盖
                            val overrides = org.json.JSONObject(prefs.getString("overrides", "{}") ?: "{}")
                            val o = overrides.optJSONObject(id) ?: org.json.JSONObject()
                            f["name"]?.let { o.put("name", it) }
                            f["url"]?.let { o.put("url", it.trim()) }
                            overrides.put(id, o)
                            prefs.edit().putString("overrides", overrides.toString()).apply()
                        } else {
                            val list = org.json.JSONArray(prefs.getString("custom_list", "[]") ?: "[]")
                            for (i in 0 until list.length()) {
                                if (list.getJSONObject(i).optString("id") == id) {
                                    val o = list.getJSONObject(i)
                                    f["name"]?.let { o.put("name", it) }
                                    f["url"]?.let { o.put("url", it.trim()) }
                                    break
                                }
                            }
                            prefs.edit().putString("custom_list", list.toString()).apply()
                        }
                    } else {
                        val list = org.json.JSONArray(prefs.getString("custom_list", "[]") ?: "[]")
                        val newId = "custom_" + System.currentTimeMillis()
                        list.put(org.json.JSONObject()
                            .put("id", newId)
                            .put("name", f["name"].orEmpty().ifBlank { "直播" })
                            .put("url", f["url"].orEmpty().trim())
                            .put("isBilibili", f["url"].orEmpty().contains("b23.tv") || f["url"].orEmpty().contains("live.bilibili.com"))
                            .put("colorIndex", list.length()))
                        prefs.edit().putString("custom_list", list.toString()).apply()
                    }
                    onMain { app.orchard.tv.HomeViewModel.notifyLiveSourcesChanged() }
                    json(state())
                }
                session.method == Method.POST && path == "/api/live-source/delete" -> {
                    val id = form(session)["id"].orEmpty()
                    val prefs = context.getSharedPreferences("live_sources", Context.MODE_PRIVATE)
                    if (id.startsWith("preset_")) {
                        // 预设源删除=隐藏
                        val hidden = mutableSetOf<String>().apply {
                            addAll(prefs.getStringSet("hidden", emptySet()) ?: emptySet())
                        }
                        hidden.add(id)
                        prefs.edit().putStringSet("hidden", hidden).apply()
                    } else {
                        val list = org.json.JSONArray(prefs.getString("custom_list", "[]") ?: "[]")
                        val newList = org.json.JSONArray()
                        for (i in 0 until list.length()) {
                            val o = list.getJSONObject(i)
                            if (o.optString("id") != id) newList.put(o)
                        }
                        prefs.edit().putString("custom_list", newList.toString()).apply()
                    }
                    onMain {
                        app.orchard.tv.HomeViewModel.instance?.onLiveSourceDeleted(id)
                        app.orchard.tv.HomeViewModel.notifyLiveSourcesChanged()
                    }
                    json(state())
                }
                session.method == Method.POST && path == "/api/live-source/hide" -> {
                    val f = form(session)
                    val id = f["id"].orEmpty()
                    val hide = f["hide"] == "1"
                    val prefs = context.getSharedPreferences("live_sources", Context.MODE_PRIVATE)
                    val hidden = mutableSetOf<String>().apply {
                        addAll(prefs.getStringSet("hidden", emptySet()) ?: emptySet())
                    }
                    if (hide) hidden.add(id) else hidden.remove(id)
                    prefs.edit().putStringSet("hidden", hidden).apply()
                    onMain {
                        if (hide) app.orchard.tv.HomeViewModel.instance?.onLiveSourceDeleted(id)
                        app.orchard.tv.HomeViewModel.notifyLiveSourcesChanged()
                    }
                    json(state())
                }
                session.method == Method.POST && path == "/api/live-source/reorder" -> {
                    val order = form(session)["order"].orEmpty()
                    val prefs = context.getSharedPreferences("live_sources", Context.MODE_PRIVATE)
                    prefs.edit().putString("order", order).apply()
                    onMain { app.orchard.tv.HomeViewModel.notifyLiveSourcesChanged() }
                    json(state())
                }
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        }.getOrElse {
            Log.w(TAG, "request failed", it)
            text(Response.Status.INTERNAL_ERROR, "error")
        }
    }

    private fun page(): String = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
        .replace("{{DEVICE}}", Build.MODEL.replace("_", " "))

    /** 手机页「关于 Aerial」那张卡要看的东西: 版本、有没有新版、检查中、下载到哪一步。 */
    private fun updateState(): JSONObject {
        val inst = Updates.install
        val phase = when (inst) {
            is Updates.Install.Downloading -> "download"
            Updates.Install.Waiting -> "waiting"
            Updates.Install.Opened -> "opened"
            Updates.Install.NeedsPermission -> "perm"
            Updates.Install.NeedsStoragePermission -> "storage"
            is Updates.Install.Failed -> "failed"
            else -> "idle"
        }
        val pct = ((inst as? Updates.Install.Downloading)?.fraction ?: 0f) * 100f
        return JSONObject()
            .put("current", Updates.current)
            .put("code", app.orchard.tv.BuildConfig.VERSION_CODE)
            .put("available", Updates.available ?: "")
            .put("checking", Updates.checking)
            .put("upToDate", Updates.upToDate)
            .put("failed", Updates.checkFailed)
            .put("freshAt", Updates.freshAt)
            .put("phase", phase)
            .put("percent", pct.toInt())
    }

    private fun state(): JSONObject {
        val list = JSONArray()
        val selected = Wallpapers.selectedId
        Wallpapers.items.forEach { w ->
            list.put(JSONObject().put("id", w.id).put("kind", w.kind.name.lowercase()).put("title", w.title).put("caption", w.caption ?: ""))
        }
        return JSONObject()
            .put("selected", selected)
            .put("latestDaily", Wallpapers.LATEST_DAILY)
            .put("rotatePhotos", Wallpapers.ROTATE_PHOTOS)
            .put("screensaver", Wallpapers.screensaver.key)
            .put("canSleep", HomeGuardService.canSleep)
            .put("screensaverFollows", Wallpapers.screensaverFollows)
            .put("videos", JSONArray().apply {
                ScreensaverVideos.all(context).forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
            })
            .put("videoRoom", ScreensaverVideos.room(context))
            .put("focusStyle", AerialSettings.focusStyle.key)
            .put("kids", kidsState())
            .put("wallpaperAlwaysMoves", AerialSettings.wallpaperAlwaysMoves)
            .put("showWeather", AerialSettings.showWeather)
            .put("screensaverClock", AerialSettings.screensaverClock)
            .put("screensaverCaption", AerialSettings.screensaverCaption)
            // 轮流播放照片的换图间隔: 电视面板上那几档, 手机页照同一份列。
            .put("photoMinutes", AerialSettings.photoMinutes)
            .put("photoChoices", JSONArray().apply { AerialSettings.PHOTO_MINUTES.forEach { put(it) } })
            .put("wallpapers", list)
            .put("apps", JSONArray().apply {
                // Pinned inputs first, then apps by the name Home shows. [default] is the name a
                // blank one goes back to; [name] is the one typed on this page, if any.
                val apps = AppCatalog.loaded.map { it to (AppNames.of(it.key) ?: it.label) }.sortedBy { it.second }
                AppCatalog.pinnedSources.forEach { a ->
                    val src = a.source ?: return@forEach
                    // An input's label as Home will show it once it has arranged the new look.
                    val look = SourceLooks.of(src.id)
                    val label = look?.let { it.name ?: it.device?.let { d -> context.getString(d.label) } } ?: src.label
                    // The stamp makes the phone fetch the tile again once its picture changes.
                    put(JSONObject().put("key", a.key).put("label", label).put("default", src.label).put("custom", CustomIcons.has(a.key)).put("stamp", CustomIcons.stamp(a.key))
                        .put("source", true).put("port", src.label).put("name", look?.name ?: "").put("device", look?.device?.key ?: "").put("look", look.hashCode()))
                }
                apps.forEach { (a, label) ->
                    // [icon] is where the tile's picture comes from now; [library] whether the icon
                    // library has one for this app, which the phone can pick instead.
                    val icon = when {
                        CustomIcons.has(a.key) -> "custom"
                        IconLibrary.has(a.key) && AppCatalog.usesLibrary(a.key) -> "library"
                        else -> "own"
                    }
                    put(JSONObject().put("key", a.key).put("label", label).put("default", a.label).put("name", AppNames.of(a.key) ?: "")
                        .put("custom", CustomIcons.has(a.key)).put("library", IconLibrary.has(a.key)).put("icon", icon)
                        .put("choices", libraryChoices(a.key, icon == "library"))
                        .put("stamp", CustomIcons.stamp(a.key) + IconLibrary.stamp(a.key) + icon.hashCode()))
                }
            })
            .put("layout", layoutState())
            .put("devices", JSONArray().apply { SourceDevice.entries.forEach { put(JSONObject().put("key", it.key).put("label", context.getString(it.label))) } })
            .put("inputs", JSONArray().apply {
                // Every input Home knows of, for pinning to Home from the phone.
                AppCatalog.sources.forEach { src ->
                    val look = SourceLooks.of(src.id)
                    put(JSONObject().put("id", src.id).put("port", src.label).put("device", src.deviceName ?: "")
                        .put("name", look?.let { it.name ?: it.device?.let { d -> context.getString(d.label) } } ?: "")
                        .put("pinned", src.id in AerialSettings.pinnedSources))
                }
            })
            .put("folders", JSONArray().apply {
                AppCatalog.folders.forEach { e ->
                    val f = e.folder ?: return@forEach
                    put(JSONObject().put("id", f.id).put("key", e.key).put("name", f.name).put("count", f.apps.size).put("stamp", e.art.hashCode()))
                }
            })
            .put("weather", JSONObject().apply {
                put("place", Weather.place ?: "")
                put("chosen", Weather.chosen)
                Weather.now?.let { put("celsius", it.celsius); put("kind", it.kind.name.lowercase()); put("day", it.day) }
            })
            .put("liveSources", buildLiveSources())
            .put("selectedLiveId", context.getSharedPreferences("live_sources", Context.MODE_PRIVATE).getString("selected_id", "preset_0"))
    }

    private fun buildLiveSources(): JSONArray {
        return try {
            val presets = listOf(
                Triple("preset_0", "央视网", "https://b23.tv/oJgZJqv"),
                Triple("preset_1", "央视频", "https://b23.tv/anI3d9f"),
                Triple("preset_2", "央视网快看", "https://b23.tv/zxbwWNq"),
                Triple("preset_3", "快看", "https://b23.tv/efy30LE"),
                Triple("preset_4", "东方明珠", "https://b23.tv/djN4x6N"),
                Triple("preset_5", "我就是邓子", "https://b23.tv/7HCyKBv"),
            )
            val prefs = context.getSharedPreferences("live_sources", Context.MODE_PRIVATE)
            val hidden = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
            val overrides = org.json.JSONObject(prefs.getString("overrides", "{}") ?: "{}")
            val presetUrls = presets.map { it.third }.toSet()

            val all = mutableListOf<JSONObject>()
            presets.forEach {
                val ov = overrides.optJSONObject(it.first)
                val name = ov?.optString("name")?.takeIf { n -> n.isNotEmpty() } ?: it.second
                val url = ov?.optString("url")?.takeIf { u -> u.isNotEmpty() } ?: it.third
                all.add(JSONObject().put("id", it.first).put("name", name).put("url", url)
                    .put("preset", true).put("hidden", it.first in hidden))
            }
            val list = org.json.JSONArray(prefs.getString("custom_list", "[]") ?: "[]")
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                if (o.getString("url") in presetUrls) continue
                all.add(JSONObject().put("id", o.getString("id")).put("name", o.getString("name"))
                    .put("url", o.getString("url")).put("preset", false)
                    .put("hidden", o.getString("id") in hidden))
            }

            val savedOrder = prefs.getString("order", null)?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
            val ordered = mutableListOf<JSONObject>()
            savedOrder.forEach { id ->
                all.find { it.getString("id") == id }?.let { ordered.add(it) }
            }
            all.forEach { if (it.getString("id") !in savedOrder) ordered.add(it) }

            JSONArray().apply { ordered.forEach { put(it) } }
        } catch (e: Exception) {
            JSONArray()
        }
    }

    /** Home in order, each folder's apps and the hidden apps, by the names Home shows. */
    private fun layoutState(): JSONObject {
        fun tile(e: AppEntry) = JSONObject().put("key", e.key).put("label", e.label)
            .put("kind", when { e.folder != null -> "folder"; e.source != null -> "source"; else -> "app" })
            // Folder and input art changes without a picture from the phone (contents, chosen
            // device), an app's with the icon library too.
            .put("stamp", if (e.folder != null || e.source != null) e.art.hashCode().toLong() else CustomIcons.stamp(e.key) + IconLibrary.stamp(e.key))
            .apply { e.folder?.let { put("id", it.id) }; e.source?.let { put("port", it.label) } }
        val folders = JSONObject()
        AppCatalog.folderApps.forEach { (id, apps) -> folders.put(id, JSONArray().apply { apps.forEach { put(tile(it)) } }) }
        // The phone shows its own PIN gate in front of the hidden list and only asks for the
        // layout once the PIN has been checked, so the names are sent even while the TV is locked
        // (4.21: the phone could not see or bring back a hidden app at all before).
        val locked = AerialSettings.locked
        return JSONObject()
            .put("home", JSONArray().apply { AppCatalog.home.forEach { put(tile(it)) } })
            .put("folders", folders)
            .put("hidden", JSONArray().apply { AppCatalog.hiddenApps.forEach { put(tile(it)) } })
            .put("hiddenCount", AppCatalog.hiddenApps.size)
            .put("columns", AerialSettings.homeColumns)
            .put("locked", locked)
            .put("ready", AppCatalog.layout != null)
    }

    /**
     * One step of arranging Home from the phone: [op] is move, movein, hide, unhide, folder (into
     * [folder], or a new one when it is empty), unfolder or ungroup. Done on the main thread before
     * answering, so the state sent back shows it.
     */
    private fun layout(f: Map<String, String>): Response {
        val ops = AppCatalog.layout ?: return json(state().put("saved", false))
        val key = f["key"].orEmpty()
        val target = f["target"].orEmpty()
        val folder = f["folder"].orEmpty()
        val known = (AppCatalog.home + AppCatalog.folderApps.values.flatten() + AppCatalog.hiddenApps).map { it.key }.toSet()
        val step: (() -> Unit)? = when (f["op"]) {
            "move" -> if (key in known && target in known) ({ ops.move(key, target) }) else null
            "movein" -> if (key in known && target in known) ({ ops.moveInFolder(key, target) }) else null
            "hide" -> if (key in known) ({ ops.hide(key) }) else null
            "unhide" -> if (key in known) ({ ops.unhide(key) }) else null
            "folder" -> if (key in known && !key.startsWith(FOLDER_KEY_PREFIX)) ({ ops.toFolder(key, folder.ifEmpty { null }) }) else null
            "unfolder" -> if (key in known) ({ ops.outOfFolder(key) }) else null
            "ungroup" -> if (folder in AppCatalog.folderApps) ({ ops.ungroup(folder) }) else null
            else -> null
        }
        if (step != null) runBlocking(Dispatchers.Main) { step() }
        return json(state().put("saved", step != null))
    }

    /** An app on the TV or a pinned input: the only tiles a picture may be sent for. */
    /** An app, a firmware shortcut such as Xiaomi's children's mode (issue #530), or a pinned input. */
    private fun installed(pkg: String) =
        pkg.isNotEmpty() && (AppCatalog.loaded.any { it.key == pkg } || AppCatalog.pinnedSources.any { it.key == pkg })

    /** The tile as Home shows it; with [own], as the app's own art would draw it. */
    private fun tile(pkg: String, own: Boolean = false): Response {
        val app = (AppCatalog.pinnedSources + AppCatalog.loaded + AppCatalog.folders).firstOrNull { it.key == pkg } ?: return text(Response.Status.NOT_FOUND, "")
        // An upload or a pick replies before Home's asynchronous reload has finished. Draw from the
        // saved picture or the pick directly, so the phone cannot cache the old tile under the new
        // versioned URL (a library pick showed the previous picture until the next change).
        val isApp = app.source == null && app.folder == null
        val library = isApp && !own && IconLibrary.has(pkg)
        val picked = when {
            own || !isApp -> null
            else -> CustomIcons.load(pkg) ?: if (library && AppCatalog.usesLibrary(pkg)) IconLibrary.load(pkg) else null
        }
        val bmp = picked ?: run {
            // The app's own art when asked for, or when the library has a picture it does not use.
            val art = if (isApp && (own || library)) AppCatalog.ownArt(context, app) ?: app.art else app.art
            TileArtFactory.flatten(art)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    /** 一张候选卡片的封面, 供手机端儿童与长辈窗口用。 */
    private fun kidsArt(key: String): Response {
        val e = AppCatalog.kidsCandidates.firstOrNull { it.key == key } ?: return text(Response.Status.NOT_FOUND, "")
        val bmp = TileArtFactory.flatten(e.art)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "no-cache")
        }
    }

    /**
     * The icon library's pictures of an app on this TV, the default first: [id], [by] (the source's
     * names: "name" and a name per language) and [on] for the one its tile uses ([inUse]: the tile
     * uses the library at all).
     */
    private fun libraryChoices(pkg: String, inUse: Boolean) = JSONArray().apply {
        val on = IconLibrary.chosen(pkg)?.id
        val borrowed = IconLibrary.borrowed(pkg)
        IconLibrary.choices(pkg).forEach { c ->
            put(JSONObject().put("id", c.id).put("by", IconLibrary.credits.optJSONObject(c.by) ?: JSONObject())
                .put("on", inUse && borrowed == null && c.id == on))
        }
        // Another app's picture given to this one: listed too, with the app it is from.
        borrowed?.let { (from, c) ->
            if (IconLibrary.file(pkg, c.id) == null) return@let
            put(JSONObject().put("id", c.id).put("from", from).put("by", IconLibrary.credits.optJSONObject(c.by) ?: JSONObject())
                .put("on", inUse && c.id == on))
        }
    }

    /**
     * The whole icon library for the phone page's picker (issue #1486): each app's pictures by
     * their place on the website, which the phone loads from there; the TV has only its own apps'.
     */
    private fun libraryCatalog(): JSONObject = JSONObject()
        .put("base", IconLibrary.PICTURES)
        .put("apps", JSONArray().apply {
            IconLibrary.catalog().toSortedMap().forEach { (pkg, choices) ->
                put(JSONObject().put("app", pkg).put("choices", JSONArray().apply {
                    choices.forEach { put(JSONObject().put("id", it.id).put("p", it.path)) }
                }))
            }
        })

    /** The icon library's picture of an app, as downloaded: [choice]'s, or the one its tile uses. */
    private fun libraryPicture(pkg: String, choice: String?): Response {
        val f = IconLibrary.file(pkg, choice) ?: return text(Response.Status.NOT_FOUND, "")
        val type = if (f.name.endsWith(".png")) "image/png" else "image/jpeg"
        return newFixedLengthResponse(Response.Status.OK, type, f.inputStream(), f.length()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    private fun icon(session: IHTTPSession): Response {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        val pkg = session.parameters["app"]?.firstOrNull().orEmpty()
        if (!installed(pkg)) return text(Response.Status.BAD_REQUEST, "unknown app")
        val f = files.values.map(::File).firstOrNull { it.exists() && it.length() > 0 }
            ?: return text(Response.Status.BAD_REQUEST, "no picture")
        val saved = f.inputStream().use { CustomIcons.save(pkg, it, context.cacheDir) }
        if (saved) onMain { CustomIcons.changed() }
        return json(state().put("saved", saved))
    }

    /** 手机页的 B 站二维码: 就一张黑白 PNG, 与电视面板同一份登录链接。 */
    private fun biliQr(): Response {
        val url = BiliPhone.qrUrl ?: return text(Response.Status.NOT_FOUND, "")
        val bmp = qr(url, scale = 10)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        }
    }

    private fun thumb(id: String): Response {
        val bmp = Wallpapers.thumbnail(java.net.URLDecoder.decode(id, "UTF-8")) ?: return text(Response.Status.NOT_FOUND, "")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    private fun liveIcon(id: String, session: IHTTPSession? = null): Response {
        // 用户上传的图标优先
        val uploadFile = java.io.File(context.filesDir, "live_icon_$id.jpg")
        if (uploadFile.exists()) {
            val bytes = uploadFile.readBytes()
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
                addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            }
        }
        val resName = when (id) {
            "preset_0" -> "live_cctv"
            "preset_1" -> "live_yangshipin"
            "preset_2" -> "live_cctvkuikan"
            "preset_3" -> "live_kuikan"
            "preset_4" -> "live_dongfangmingzhu"
            "preset_5" -> "live_dengzi"
            else -> return text(Response.Status.NOT_FOUND, "")
        }
        val resId = context.resources.getIdentifier(resName, "drawable", context.packageName)
        if (resId == 0) return text(Response.Status.NOT_FOUND, "")
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeResource(context.resources, resId, opts)
        opts.inSampleSize = (opts.outWidth / 480).coerceAtLeast(1)
        opts.inJustDecodeBounds = false
        val bmp = android.graphics.BitmapFactory.decodeResource(context.resources, resId, opts)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "public, max-age=86400")
        }
    }

    private fun upload(session: IHTTPSession): Response {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        var added = 0
        // NanoHTTPD stores each part in a temp file; several photos arrive as photo, photo1, photo2...
        files.values.forEach { path ->
            val f = File(path)
            if (f.exists() && f.length() > 0) {
                val id = f.inputStream().use { input -> runBlocking(Dispatchers.Main) { Wallpapers.addPhoto(input) } }
                if (id != null) added++
            }
        }
        return json(state().put("added", added))
    }

    /**
     * One film per request, its bytes as the body and its name in the address. Not through
     * [IHTTPSession.parseBody]: that writes the body to the cache, copies the file part out again and
     * maps the whole body into memory, which a 2 GB film on a 32-bit TV cannot afford (#1164).
     */
    private fun video(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull() ?: 0L
        val name = session.parameters["name"]?.firstOrNull().orEmpty()
        val saved = ScreensaverVideos.receive(context, session.inputStream, length, name) != null
        if (saved) onMain { Wallpapers.videosChanged() }
        // A refused or broken film leaves unread bytes behind; do not read them as the next request.
        return json(state().put("saved", saved)).apply { if (!saved) closeConnection(true) }
    }

    // NanoHTTPD writes the whole body to the cache before we see it; keep a stray or hostile
    // upload from filling the TV's storage (#57).
    private fun tooLarge(session: IHTTPSession): Boolean =
        (session.headers["content-length"]?.toLongOrNull() ?: 0L) > MAX_UPLOAD

    private fun form(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun onMain(block: () -> Unit) = runBlocking(Dispatchers.Main) { block() }

    /**
     * The parental PIN, from the phone. Five wrong in a row and none is taken for a minute, so that
     * the 10 000 four-digit PINs cannot be tried from a phone in a few minutes.
     */
    private fun pinOk(pin: String?): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (pin.isNullOrEmpty() || now < pinRestUntil) return false
        if (AerialSettings.pinMatches(pin)) { pinMisses = 0; return true }
        if (++pinMisses >= 5) { pinMisses = 0; pinRestUntil = now + 60_000L }
        return false
    }
    @Volatile private var pinMisses = 0
    @Volatile private var pinRestUntil = 0L

    /** Kids and Elders mode as the page shows it: on or not, the day's time, a lock. */
    private fun kidsState(): JSONObject {
        val r = Restricted
        val now = System.currentTimeMillis()
        return JSONObject()
            .put("enabled", r.enabled)
            .put("active", r.active)
            .put("kind", r.kind.key)
            .put("pinSet", AerialSettings.pinSet)
            .put("pinArrows", AerialSettings.pinArrows)
            .put("usedMin", r.usage.usedMs / 60_000L)
            .put("limitMin", r.limitToday(now))
            .put("allowUntil", r.usage.allowUntil.takeIf { it > now } ?: 0L)
            .put("lock", when (r.lock) { is KidsLock.Break -> "break"; KidsLock.DayUsed -> "day"; KidsLock.Late -> "late"; null -> "" })
            .put("startMinute", r.rules.startMinute)
            .put("endMinute", r.rules.endMinute)
            // The whole setup, for the page's editor; changing it takes the PIN.
            .put("blockOthers", r.blockOthers)
            .put("guard", HomeGuardService.isRunning)
            .put("weekdayMinutes", r.rules.weekdayMinutes)
            .put("weekendMinutes", r.rules.weekendMinutes)
            .put("breakEvery", r.rules.breakEvery)
            .put("breakMinutes", r.rules.breakMinutes)
            .put("allowed", JSONArray(r.allowed))
            .put("candidates", JSONArray().apply {
                AppCatalog.kidsCandidates.forEach { e ->
                    put(JSONObject().put("key", e.key).put("label", e.label).put("source", e.source != null))
                }
            })
    }

    /**
     * The mode set up from the phone, all at once: who it is for, other apps, any number of
     * minutes, any window, the break, the tiles in order (keys one per line) and on or off.
     * Turned on here it starts at once: the parent is not at the TV. Nothing is kept unless all
     * of it is valid.
     */
    private fun kidsConfig(f: Map<String, String>): Response {
        fun int(key: String, default: Int) = f[key]?.trim()?.toIntOrNull() ?: default
        val current = Restricted.rules
        val rules = RestrictedRules(
            weekdayMinutes = int("weekday", current.weekdayMinutes),
            weekendMinutes = int("weekend", current.weekendMinutes),
            startMinute = int("start", current.startMinute),
            endMinute = int("end", current.endMinute),
            breakEvery = int("breakEvery", current.breakEvery),
            breakMinutes = int("breakMinutes", current.breakMinutes),
        )
        val known = AppCatalog.kidsCandidates.mapTo(HashSet()) { it.key }
        val allowed = f["allowed"]?.split('\n')?.map { it.trim() }?.filter { it in known }?.distinct() ?: Restricted.allowed
        val on = f["enabled"]?.let { it == "1" } ?: Restricted.enabled
        val kind = f["kind"]?.let { RestrictedKind.of(it) } ?: Restricted.kind
        if (!rules.valid || (on && allowed.isEmpty())) return json(state().put("saved", false))
        onMain {
            Restricted.chooseKind(kind)
            f["block"]?.let { Restricted.chooseBlockOthers(it == "1") }
            Restricted.chooseRules(rules)
            Restricted.chooseAllowed(allowed)
            if (on != Restricted.enabled) {
                Restricted.chooseEnabled(on)
                if (on) Restricted.start()
            }
            KidsWatch.poke()
        }
        return json(state().put("saved", true))
    }

    private fun backupFileName(): String =
        "aerial-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".zip"

    private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun text(status: Response.Status, msg: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)

    /** 从 GET 查询串里取一个参数, 兜住不把 GET 参数放进 parms 的 NanoHTTPD 版本。 */
    private fun queryOf(raw: String?, key: String): String =
        raw?.split('&')
            ?.firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            .orEmpty()

    /** 读固定长度的原始请求体, 用在上传之外的小段文本(手机端导入过程)。 */
    private fun readBody(session: IHTTPSession, len: Int): String = runCatching {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(2048)
        var total = 0
        while (total < len) {
            val read = session.inputStream.read(buf)
            if (read <= 0) break
            val take = minOf(read, len - total)
            out.write(buf, 0, take)
            total += take
        }
        String(out.toByteArray(), Charsets.UTF_8)
    }.getOrDefault("")

    /**
     * 一份文本: [download] 为真时作为附件, 文件名带时间戳, 浏览器直接存到手机的下载目录;
     * 否则只回正文, 页面把内容内联显示出来(日志按钮点了不弹下载框, 就是走这条路)。
     */
    private fun textFile(body: String, prefix: String, download: Boolean): Response {
        val r = newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", body)
        if (download) {
            app.orchard.tv.system.DiagLog.i("手机端下载日志: ${body.length} 字符")
            val name = prefix + "-" + java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date()) + ".txt"
            r.addHeader("Content-Disposition", "attachment; filename=\"$name\"")
        }
        return r
    }

    /**
     * 整机备份下载。先写一份临时 zip 再发固定长度响应: 浏览器/下载管理器要拿到 Content-Length
     * 才肯显示总大小和进度。临时文件放内部缓存目录, 句柄打开后再删。
     */
    // ---- 备份打包: 一次只打一份, 打完的包在缓存里放 10 分钟供反复取
    private val backupLock = Any()

    @Volatile private var backupReady: File? = null
    @Volatile private var backupReadyAt = 0L
    @Volatile private var backupPacking = false
    @Volatile private var backupInfo = ""
    @Volatile private var backupError: String? = null
    @Volatile private var backupStartedAt = 0L

    private fun backupFresh(): File? {
        val f = backupReady ?: return null
        if (!f.exists() || System.currentTimeMillis() - backupReadyAt > 10 * 60_000L) return null
        return f
    }

    private fun backupPrepare(force: Boolean): Response {
        synchronized(backupLock) {
            if (force && !backupPacking) {
                // 用户要的是这一刻的数据, 十分钟前的旧包不能糊弄过去。
                backupReady?.delete()
                backupReady = null
                backupReadyAt = 0
                backupError = null
            }
            backupFresh()?.let {
                return json(JSONObject().put("state", "ready").put("bytes", it.length()).put("info", backupInfo)
                    .put("ms", (backupReadyAt - backupStartedAt).coerceAtLeast(0)).put("name", backupFileName()))
            }
            if (backupPacking) return json(JSONObject().put("state", "packing").put("bytes", 0))
            backupPacking = true
            backupError = null
            backupInfo = ""
            backupStartedAt = System.currentTimeMillis()
            app.orchard.tv.system.DiagLog.i("手机端导出备份: 开始")
            app.orchard.tv.system.Backup.trace(context, "导出请求: 缓存剩余 " + runCatching {
                android.os.StatFs(context.cacheDir.path).availableBytes
            }.getOrDefault(0L) + " 字节")
            Thread {
                val tmp = File(context.cacheDir, "aerial-backup-ready.zip")
                val out = runCatching {
                    tmp.outputStream().use { app.orchard.tv.system.Backup.export(context, it) }
                }
                synchronized(backupLock) {
                    backupPacking = false
                    out.onSuccess { s ->
                        backupReady = tmp
                        backupReadyAt = System.currentTimeMillis()
                        backupInfo = "${s.prefs} 项设置, ${s.files} 个文件"
                        app.orchard.tv.system.DiagLog.i("手机端导出备份: ${s.prefs} 项设置, ${s.files} 个文件, ${tmp.length()} 字节")
                        app.orchard.tv.system.Backup.trace(context, "导出完成: ${s.prefs} 项设置, ${s.files} 个文件, ${tmp.length()} 字节")
                    }.onFailure {
                        backupError = app.orchard.tv.system.Backup.readable(it)
                        tmp.delete()
                        app.orchard.tv.system.DiagLog.i("手机端导出备份失败: ${it.message}")
                        app.orchard.tv.system.Backup.trace(context, "导出失败: ${android.util.Log.getStackTraceString(it)}")
                    }
                }
            }.apply { name = "backup-pack"; isDaemon = true }.start()
            return json(JSONObject().put("state", "packing").put("bytes", 0))
        }
    }

    private fun backupState(): Response {
        val o = JSONObject()
        val ready = backupFresh()
        when {
            ready != null -> o.put("state", "ready").put("bytes", ready.length()).put("info", backupInfo)
                .put("ms", (backupReadyAt - backupStartedAt).coerceAtLeast(0)).put("name", backupFileName())
            backupPacking -> o.put("state", "packing").put("bytes", 0)
            backupError != null -> o.put("state", "failed").put("error", backupError)
            else -> o.put("state", "idle")
        }
        return json(o)
    }

    private fun backupDownload(): Response {
        // 页面已经等打包完才来取: 直接把现成的文件发出去, 响应头立刻返回, 下载框当场就弹。
        backupFresh()?.let { f ->
            app.orchard.tv.system.DiagLog.i("手机端导出备份: 发送现成包 ${f.length()} 字节")
            val name = backupFileName()
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/zip",
                f.inputStream(),
                f.length(),
            ).apply {
                addHeader("Content-Disposition", "attachment; filename=\"$name\"")
                addHeader("Cache-Control", "no-store")
            }
        }
        app.orchard.tv.system.DiagLog.i("手机端导出备份: 开始")
        val tmp = File.createTempFile("aerial-backup", ".zip", context.cacheDir)
        val summary = runCatching {
            tmp.outputStream().use { app.orchard.tv.system.Backup.export(context, it) }
        }.getOrElse {
            tmp.delete()
            app.orchard.tv.system.DiagLog.i("手机端导出备份失败: ${it.message}")
            app.orchard.tv.system.Backup.trace(context, "导出失败: ${android.util.Log.getStackTraceString(it)}")
            return text(Response.Status.INTERNAL_ERROR, "备份失败: ${app.orchard.tv.system.Backup.readable(it)}")
        }
        app.orchard.tv.system.DiagLog.i("手机端导出备份: ${summary.prefs} 项设置, ${summary.files} 个文件, ${tmp.length()} 字节")
        app.orchard.tv.system.Backup.trace(context, "导出完成: ${summary.prefs} 项设置, ${summary.files} 个文件, ${tmp.length()} 字节")
        val name = backupFileName()
        synchronized(backupLock) {
            backupReady = tmp
            backupReadyAt = System.currentTimeMillis()
        }
        // 句柄先打开再删: Linux 下边读边删是安全的, 不在缓存里留一份副本。
        val stream = tmp.inputStream()
        val length = tmp.length()
        return newFixedLengthResponse(Response.Status.OK, "application/zip", stream, length).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$name\"")
        }
    }

    /** 上传备份包恢复本机。包体直接作为请求体流进来, 不走 multipart: 大包不该先落盘再解一遍。 */
    private fun restoreBackup(session: IHTTPSession): Response {
        // 手机端会带一个随机 id, 恢复后进程要重启, 那条回复可能到不了手机, 它靠这个 id 回查结果。
        val id = session.headers["x-restore-id"].orEmpty()
        // 备份里有照片和影片, 不能按普通上传的 200 MB 卡, 单独给一个更宽松的上限。
        val declared = session.headers["content-length"]?.toLongOrNull() ?: 0L
        val free = runCatching { android.os.StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L)
        app.orchard.tv.system.Backup.trace(
            context,
            "导入请求: 包 $declared 字节, 剩余空间 $free 字节, id=${id.ifEmpty { "-" }}",
        )
        if (declared > MAX_BACKUP) {
            app.orchard.tv.system.Backup.trace(context, "拒绝: 包超过上限 $MAX_BACKUP")
            return text(Response.Status.BAD_REQUEST, "备份包超过 4 GB")
        }
        // 空间不够就别拆: 一半设置已经写进去, 文件写到中途 ENOSPC, 用户看到的是"导入了一半"。
        if (declared > 0 && declared > free) {
            app.orchard.tv.system.Backup.trace(context, "拒绝: 空间不足")
            app.orchard.tv.system.Backup.markRestoreState(context, id, "failed", error = "存储空间不足")
            return text(Response.Status.BAD_REQUEST, "存储空间不足: 需要 $declared 字节, 剩余 $free 字节")
        }
        app.orchard.tv.system.Backup.markRestoreState(context, id, "running")
        val summary = runCatching {
            app.orchard.tv.system.Backup.restore(context, session.inputStream)
        }.getOrElse { e ->
            app.orchard.tv.system.DiagLog.i("手机端导入备份失败: ${e.message}")
            app.orchard.tv.system.Backup.trace(context, "导入失败: ${android.util.Log.getStackTraceString(e)}")
            val reason = app.orchard.tv.system.Backup.readable(e)
            app.orchard.tv.system.Backup.markRestoreState(context, id, "failed", error = reason)
            return text(Response.Status.BAD_REQUEST, "恢复失败: $reason")
        }
        app.orchard.tv.system.DiagLog.i("手机端导入备份: ${summary.prefs} 项设置, ${summary.files} 个文件; 即将重启")
        app.orchard.tv.system.Backup.markRestoreState(context, id, "ok", summary.prefs, summary.files)
        // 先把结果发回去(serve 返回后 NanoHTTPD 才写响应), 留几秒再把进程重启。
        onMain { app.orchard.tv.system.Backup.scheduleRestart(context, delayMs = 5000L) }
        return json(state().put("restored", true).put("prefs", summary.prefs).put("files", summary.files)).apply {
            // 响应写完就关连接: 进程随后自杀, 别让 keep-alive 把手机那头正在读的回复掐断。
            addHeader("Connection", "close")
        }
    }

    /**
     * 手机页发起的那次扫码登录的状态。手机上关掉页面也不影响电视已经登录的凭据:
     * 这里只管"码画出来没有、扫到哪一步", 成功与否以 [BiliAccount] 为准。
     */
    private object BiliPhone {
        @Volatile var phase: String = "idle"     // idle / qr / wait / confirm / ok / expired / failed
        @Volatile var qrUrl: String? = null
        @Volatile private var running = false

        fun reset() {
            phase = "idle"
            qrUrl = null
        }

        fun start() {
            if (running) return
            running = true
            phase = "qr"
            qrUrl = null
            Thread {
                try {
                    runBlocking {
                        BiliLogin.login { st ->
                            when (st) {
                                is BiliLoginState.QrReady -> { qrUrl = st.qrUrl; phase = "qr" }
                                BiliLoginState.WaitingScan -> if (qrUrl != null) phase = "wait"
                                BiliLoginState.ScannedConfirm -> phase = "confirm"
                                BiliLoginState.Success -> { phase = "ok"; qrUrl = null }
                                BiliLoginState.Expired -> { phase = "expired"; qrUrl = null }
                                is BiliLoginState.Failed -> { phase = "failed"; qrUrl = null }
                                else -> {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    app.orchard.tv.system.DiagLog.e("手机页 B 站登录失败", e)
                    phase = "failed"
                } finally {
                    running = false
                }
            }.start()
        }
    }

    companion object {
        const val PORT = 8790
const val HTTPS_PORT = 8791
        private const val TAG = "AerialPhone"
        /** Enough for a batch of phone photos at full size. */
        private const val MAX_UPLOAD = 200L * 1024 * 1024
        /** A backup carries photos and films, so it gets its own, much higher ceiling. */
        private const val MAX_BACKUP = 4L * 1024 * 1024 * 1024
        /** As for an input's name: Home's caption has room for about this much. */
        private const val FOLDER_NAME_MAX = 24

        @Volatile
        private var running: PhoneServer? = null
        @Volatile
        private var secureRunning: PhoneServer? = null

        /**
         * Listens for as long as Aerial runs, from Home's start (MainActivity.startWork), so a
         * computer can open the page at any time without Personalize open on the TV. On every
         * interface: the address the TV has may change, the page stays the same.
         */
        @Synchronized
        private fun keystoreFile(context: Context): File = File(context.filesDir, "aerial.p12").also { f ->
            if (!f.exists()) context.assets.open("aerial.p12").use { input ->
                FileOutputStream(f).use { output -> input.copyTo(output) }
            }
        }

        fun listen(context: Context): PhoneServer? {
            running?.let { if (it.isAlive) return it }
            val appCtx = context.applicationContext
            val tok = token(context)
            // HTTP on 8790 (WeChat/browser)
            val http = runCatching {
                PhoneServer(appCtx, tok).also {
                    it.start(SOCKET_READ_TIMEOUT, true)
                    running = it
                }
            }.onFailure { Log.w(TAG, "HTTP listen failed", it) }.getOrNull() ?: return null
            // HTTPS on 8791 (GPS/app)
            runCatching {
                PhoneServer(appCtx, tok, HTTPS_PORT).also { server ->
                    val ks = KeyStore.getInstance("PKCS12")
                    appCtx.assets.open("aerial.p12").use { ks.load(it, "aerial123".toCharArray()) }
                    val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
                    kmf.init(ks, "aerial123".toCharArray())
                    val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
                    ctx.init(kmf.keyManagers, null, java.security.SecureRandom())
                    server.makeSecure(ctx.serverSocketFactory, null)
                    server.isSecure = true
                    server.start(SOCKET_READ_TIMEOUT, true)
                    secureRunning = server
                }
            }.onFailure { Log.w(TAG, "HTTPS listen failed", it) }
            return http
        }

        /** The address to put in the QR code, listening first if need be, or null offline. */
        fun start(context: Context): String? {
            val ip = lanAddress(context) ?: return null
            val server = listen(context) ?: return null
return "http://$ip:$PORT/"
        }

        /**
         * Kept from one start to the next, so the address can be bookmarked on a computer and the
         * phone's tab still works the next day.
         */
        /** Exposed for the relay loopback: same 8-char token the local page uses. */
        fun currentToken(context: Context): String = token(context)

        private fun token(context: Context): String {
            val prefs = context.getSharedPreferences("phone_page", Context.MODE_PRIVATE)
            prefs.getString("token", null)?.takeIf { it.length == 8 }?.let { return it }
            return newToken().also { prefs.edit().putString("token", it).apply() }
        }

        private fun newToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }

        fun lanAddress(context: Context): String? {
            val cm = context.getSystemService<ConnectivityManager>() ?: return null
            val current = cm.currentNetwork()
            // Under a proxy app's VPN the default network is the tunnel, whose address the phone
            // cannot reach (Clash and the like use 198.18.0.1; Xiaomi S55, issue #667). The Wi-Fi
            // or Ethernet network under it is still in allNetworks: take its address first.
            val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
            val lan = networks.filter { n ->
                val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return@filter false
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }.sortedByDescending { it == current }
            return (lan + listOfNotNull(current)).firstNotNullOfOrNull { n ->
                runCatching { cm.getLinkProperties(n) }.getOrNull()?.linkAddresses?.map { it.address }
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress && !isBenchmark(it) }
            }?.hostAddress
        }

        /** 198.18.0.0/15, the range proxy apps put their tunnel in; never a home network. */
        private fun isBenchmark(address: java.net.InetAddress): Boolean {
            val b = address.address
            return b.size == 4 && (b[0].toInt() and 0xff) == 198 && (b[1].toInt() and 0xfe) == 18
        }

        /** A QR code, dark modules on white, each module [scale] pixels square. */
        fun qr(text: String, scale: Int = 8): Bitmap {
            val matrix = QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
            val n = matrix.width
            val bmp = createBitmap(n * scale, n * scale)
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix[x, y]) for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.BLACK)
                else for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.WHITE)
            }
            return bmp
        }
    }
}
