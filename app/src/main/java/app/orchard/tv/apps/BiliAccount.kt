package app.orchard.tv.apps

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * B 站登录态(web 扫码)。凭据只存在本应用私有 prefs, 不写进诊断日志、不上报。
 * 直播取流的 Cookie 头把这里的登录态追加在匿名 buvid3 后面; 未登录时为 null, 走访客档。
 */
object BiliAccount {
    private const val PREFS = "bili_account"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_UID = "uid"

    private lateinit var prefs: SharedPreferences

    /** 整条登录 Cookie 串(`k=v; k2=v2`), 未登录为 null。 */
    var cookie by mutableStateOf<String?>(null); private set
    var uid by mutableStateOf<String?>(null); private set

    val loggedIn: Boolean get() = !cookie.isNullOrEmpty()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cookie = prefs.getString(KEY_COOKIE, null)
        uid = prefs.getString(KEY_UID, null)
    }

    /** 保存换取到的登录 cookie; 没有 SESSDATA 视为没登上, 不写。 */
    fun save(cookies: Map<String, String>) {
        val sess = cookies["SESSDATA"]
        if (sess.isNullOrEmpty()) return
        val joined = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        cookie = joined
        uid = cookies["DedeUserID"] ?: uid
        prefs.edit {
            putString(KEY_COOKIE, joined)
            putString(KEY_UID, uid)
        }
    }

    fun clear() {
        cookie = null
        uid = null
        prefs.edit { remove(KEY_COOKIE); remove(KEY_UID) }
    }
}
