package app.orchard.tv.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 整机备份与恢复: 一个 zip, 里面是本机所有设置和一个 files 目录。
 *
 * 设置按 pref 文件逐个存成 JSON, 不直接拷 XML: 恢复时走 SharedPreferences 接口回写, 类型(布尔/
 * 整数/长整/浮点/字符串/字符串集合)不会因为手写 XML 出错。
 *
 * files 目录整体打包, 照片、影片、图标、每天一张的壁纸都在里面。两样东西排除在外: 自己生成的
 * HTTPS 证书(aerial.p12, 换台机器会重建, 也不该外流)和日志。
 */
object Backup {
    /** 格式版本。以后加字段时靠它决定怎么读旧包。 */
    private const val VERSION = 1
    private const val MANIFEST = "backup.json"
    private const val FILES = "files/"
    /** 卡片布局的存档名, 恢复时要单独合并(见 [mergeLayout])。 */
    private const val LAYOUT = "layout"
    /** 与 LayoutStore.FOLDER_KEY_PREFIX 一致: order 里一个文件夹占的那一格。 */
    private const val FOLDER_KEY = "folder:"

    /** 本机所有存档。漏掉一个就是恢复后少一块设置, 所以集中写在这里。 */
    private val PREF_FILES = listOf(
        "settings",          // 个性化的大部分开关、直播画质偏好
        "live_sources",      // 直播源、名字、画质、当前选中的台
        "layout",            // 卡片布局、文件夹、隐藏项
        "system",            // 桌面守护、开机打开、诊断日志开关、设备画像
        "icon_library",      // 图标库里下载过的图标
        "wallpaper",         // 壁纸选择、屏保模式与延时
        "wallpaper_frames",  // 壁纸关键帧
        "uhd_canvas",        // 4K 壁纸画布
        "phone_page",        // 手机页状态
        "bili_account",      // B 站登录态
        "restricted",        // 家长密码与儿童/长辈模式
        "aerial",            // 亮度
        "aerial_set",        // 航拍画质
        "weather",           // 天气城市与缓存
        "updates",           // 更新检查状态
        "self_compile",      // 自编译状态
    )

    /** 恢复结果, 用来给用户一句人话。 */
    data class Summary(val prefs: Int, val files: Int)

    /** 恢复进度存档。恢复完进程会自杀重启, 手机那头可能等不到那一条 HTTP 回复, 靠它补查。 */
    private const val RESTORE_STATE = "restore_state"

    /** 记下这次恢复的结果: running / ok / failed。commit 同步落盘, 进程随后被杀也不会丢。 */
    fun markRestoreState(context: Context, id: String, state: String, prefs: Int = 0, files: Int = 0, error: String? = null) {
        if (id.isEmpty()) return
        context.getSharedPreferences(RESTORE_STATE, Context.MODE_PRIVATE).edit()
            .putString("id", id)
            .putString("state", state)
            .putInt("prefs", prefs)
            .putInt("files", files)
            .putString("error", error)
            .commit()
    }

    /** 查某次恢复的结果; [id] 传空表示最近一次。没记录过返回 null。 */
    fun restoreState(context: Context, id: String): JSONObject? {
        val sp = context.getSharedPreferences(RESTORE_STATE, Context.MODE_PRIVATE)
        val saved = sp.getString("id", null) ?: return null
        if (id.isNotEmpty() && saved != id) return null
        return JSONObject()
            .put("id", saved)
            .put("state", sp.getString("state", "unknown"))
            .put("prefs", sp.getInt("prefs", 0))
            .put("files", sp.getInt("files", 0))
            .put("error", sp.getString("error", null))
    }

    private fun prefsDir(context: Context) = File(context.applicationInfo.dataDir, "shared_prefs")

    private fun skipFile(name: String) =
        name == "aerial.p12" || name.endsWith(".log") || name.endsWith(".tmp") || name.startsWith(".")

    /** 导出到 [out]; 调用方负责关闭流。 */
    fun export(context: Context, out: OutputStream): Summary {
        // 必须走到 use 的 close: ZipOutputStream 关闭时才写末尾的中央目录, 只 flush 出来的包
        // 标准解压软件会报 "zip END header not found" 打不开。
        ZipOutputStream(out).use { zip ->
            // 先把清单算出来, 再写进包里, 最后逐个压文件: 清单得在前面才能边读边解。
            val prefJson = JSONObject()
            var prefs = 0
            PREF_FILES.forEach { name ->
                val values = readPrefs(context, name) ?: return@forEach
                prefJson.put(name, values)
                prefs += values.length()
            }
            val fileList = JSONArray()
            var files = 0
            walkFiles(context.filesDir) { _, rel ->
                fileList.put(rel)
                files++
            }
            val manifest = JSONObject().apply {
                put("version", VERSION)
                put("package", context.packageName)
                put("createdAt", System.currentTimeMillis())
                put("prefs", prefJson)
                put("files", fileList)
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            walkFiles(context.filesDir) { file, rel ->
                runCatching {
                    zip.putNextEntry(ZipEntry(FILES + rel))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return Summary(prefs, files)
        }
    }

    /** 遍历 files 目录, 跳过证书与日志。 */
    private fun walkFiles(root: File, onFile: (File, String) -> Unit) {
        fun go(dir: File, prefix: String) {
            dir.listFiles()?.forEach { f ->
                if (skipFile(f.name)) return@forEach
                if (f.isDirectory) go(f, "$prefix${f.name}/")
                else if (f.length() > 0) onFile(f, prefix + f.name)
            }
        }
        if (root.isDirectory) go(root, "")
    }

    /** 从 [input] 读一个备份包并覆盖本机。调用方负责关闭流。 */
    fun restore(context: Context, input: InputStream): Summary {
        var prefs = 0
        var files = 0
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                // 只认自己写出来的路径: 别让一个包里的 ../ 写到别处去。
                if (name.contains("..") || name.startsWith("/")) throw IllegalArgumentException("bad entry $name")
                when {
                    name == MANIFEST -> {
                        val manifest = JSONObject(String(zip.readBytes(), Charsets.UTF_8))
                        prefs = applyPrefs(context, manifest.optJSONObject("prefs"))
                    }
                    name.startsWith(FILES) && !entry.isDirectory -> {
                        val target = File(context.filesDir, name.removePrefix(FILES))
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                        files++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (prefs == 0 && files == 0) throw IllegalArgumentException("empty backup")
        return Summary(prefs, files)
    }

    private fun applyPrefs(context: Context, all: JSONObject?): Int {
        if (all == null) return 0
        var count = 0
        all.keys().forEach { name ->
            val block = all.optJSONObject(name) ?: return@forEach
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            // 卡片布局要两台机器求并集: 本机独有的卡片(装的是备份里没有的 App 或源)不该因为
            // 导入而掉到桌面末尾。先记下本机原样, 写完备份那份再合一次。
            val mine = if (name == LAYOUT) readLayout(prefs) else null
            // 整份覆盖: 残留的旧键会让恢复出来的状态和新包不一致(比如新包里没有的开关还留着)。
            prefs.edit().clear().commit()
            val edit = prefs.edit()
            block.keys().forEach { key ->
                val node = block.optJSONObject(key) ?: return@forEach
                when (node.optString("t")) {
                    "b" -> edit.putBoolean(key, node.optBoolean("v"))
                    "i" -> edit.putInt(key, node.optInt("v"))
                    "l" -> edit.putLong(key, node.optLong("v"))
                    "f" -> edit.putFloat(key, node.optDouble("v").toFloat())
                    "ss" -> {
                        val set = HashSet<String>()
                        node.optJSONArray("v")?.let { arr -> for (i in 0 until arr.length()) set.add(arr.optString(i)) }
                        edit.putStringSet(key, set)
                    }
                    else -> edit.putString(key, node.optString("v"))
                }
                count++
            }
            edit.commit()
            if (mine != null) writeLayout(prefs, mergeLayout(mine, readLayout(prefs)))
        }
        return count
    }

    /** 一个 pref 文件里的全部键, 带类型。文件不存在时返回 null, 不新建空文件。 */
    private fun readPrefs(context: Context, name: String): JSONObject? {
        if (!File(prefsDir(context), "$name.xml").exists()) return null
        val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
        if (all.isEmpty()) return null
        return JSONObject().apply {
            all.forEach { (key, value) ->
                val node = JSONObject()
                when (value) {
                    is Boolean -> { node.put("t", "b"); node.put("v", value) }
                    is Int -> { node.put("t", "i"); node.put("v", value) }
                    is Long -> { node.put("t", "l"); node.put("v", value) }
                    is Float -> { node.put("t", "f"); node.put("v", value.toDouble()) }
                    is Set<*> -> {
                        val arr = JSONArray()
                        value.forEach { arr.put(it?.toString().orEmpty()) }
                        node.put("t", "ss")
                        node.put("v", arr)
                    }
                    else -> { node.put("t", "s"); node.put("v", value?.toString().orEmpty()) }
                }
                put(key, node)
            }
        }
    }

    /** 一个 layout 存档的可读形态: 桌面顺序、隐藏项、文件夹。 */
    internal data class LayoutSnapshot(
        val order: List<String>,
        val hidden: Set<String>,
        val folders: List<FolderSnapshot>,
    )

    internal data class FolderSnapshot(val id: String, val name: String, val apps: List<String>)

    /**
     * 两台机器的卡片布局求并集, 以备份那份([theirs])为主: 备份里的卡片与文件夹位置照旧,
     * 本机([mine])独有的 App 一个不丢 —— 本机原来在同一个名字的文件夹里就并进备份那个文件夹;
     * 本机那个文件夹备份里没有就整个带过来; 散在桌面上的补到末尾。隐藏项取并集, 本机藏着的照旧藏着。
     */
    internal fun mergeLayout(mine: LayoutSnapshot?, theirs: LayoutSnapshot): LayoutSnapshot {
        if (mine == null) return theirs
        val covered = (theirs.order + theirs.folders.flatMap { it.apps }).toHashSet()
        val folders = theirs.folders.toMutableList()
        val order = theirs.order.toMutableList()
        var nextId = (folders.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1
        mine.folders.forEach { f ->
            val extra = f.apps.filterNot { it in covered || it.startsWith(FOLDER_KEY) }
            if (extra.isEmpty()) return@forEach
            val sameName = folders.indexOfFirst { it.name == f.name }
            if (sameName >= 0) {
                folders[sameName] = folders[sameName].copy(apps = folders[sameName].apps + extra)
            } else {
                val id = nextId.toString()
                nextId++
                folders += FolderSnapshot(id, f.name, extra)
                order += FOLDER_KEY + id
            }
        }
        val inMyFolders = mine.folders.flatMap { it.apps }.toHashSet()
        order += mine.order.filterNot { it.startsWith(FOLDER_KEY) || it in covered || it in inMyFolders }
        return LayoutSnapshot(order, theirs.hidden + mine.hidden, folders)
    }

    private fun readLayout(prefs: SharedPreferences): LayoutSnapshot {
        val folders = runCatching {
            val array = JSONArray(prefs.getString("folders", null) ?: return@runCatching emptyList())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val apps = o.optJSONArray("apps")?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList()
                FolderSnapshot(o.optString("id"), o.optString("name"), apps)
            }
        }.getOrDefault(emptyList())
        return LayoutSnapshot(
            order = prefs.getString("order", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
            hidden = prefs.getStringSet("hidden", null)?.toSet() ?: emptySet(),
            folders = folders,
        )
    }

    private fun writeLayout(prefs: SharedPreferences, snap: LayoutSnapshot) {
        prefs.edit()
            .putString("order", snap.order.joinToString(","))
            .putStringSet("hidden", snap.hidden)
            .putString("folders", JSONArray().apply {
                snap.folders.forEach { f ->
                    put(JSONObject().put("id", f.id).put("name", f.name).put("apps", JSONArray(f.apps)))
                }
            }.toString())
            .putBoolean("initialized", true)
            .commit()
    }

    /**
     * 恢复完成后重启本进程: 设置都是各对象在 init 时读进内存的, 就地改内存等于到处补丁。
     * 用闹钟先把主页拉起再杀自己, 系统重开一份干净的。桌面守护开着时系统也会自己重启主页。
     */
    fun scheduleRestart(context: Context, delayMs: Long = 1600L) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pending = PendingIntent.getActivity(
                context, 1, launch,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.set(AlarmManager.RTC, System.currentTimeMillis() + delayMs, pending)
            }.onFailure { DiagLog.i("备份恢复: 安排重启失败 ${it.message}") }
        }
        // 先让回复写回手机, 再杀自己: 手机端还要拿到「已导入」这一句, 不能把连接掐了。
        Handler(Looper.getMainLooper()).postDelayed({
            DiagLog.i("备份恢复: 重启进程")
            Process.killProcess(Process.myPid())
        }, (delayMs - 250L).coerceAtLeast(300L))
    }
}
