package app.orchard.tv.apps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import android.os.SystemClock

/** B 站扫码登录的界面状态。 */
sealed interface BiliLoginState {
    /** 二维码已生成, [qrUrl] 是要画成二维码的登录链接。 */
    data class QrReady(val qrUrl: String) : BiliLoginState
    /** 还没人扫。 */
    object WaitingScan : BiliLoginState
    /** 手机已扫, 待在手机上确认。 */
    object ScannedConfirm : BiliLoginState
    /** 扫码成功, 登录态已保存。 */
    object Success : BiliLoginState
    /** 二维码超时/失效, 需要重新生成。 */
    object Expired : BiliLoginState
    data class Failed(val msg: String) : BiliLoginState
}

/**
 * web 扫码登录。流程(2024 后 B 站把发 cookie 拆成了两步):
 *  1. generate 取登录链接和 qrcode_key;
 *  2. poll 轮询扫码状态;
 *  3. 确认成功后, 返回的链接要么直接带 cookie(旧流程), 要么只带 ticket ——
 *     ticket 要再访问 crossDomain 端点, 从响应 Set-Cookie 里换到 SESSDATA(新流程)。
 * 凭据由 [BiliAccount] 落本地私有 prefs, 本模块不打印 cookie 原值。
 */
object BiliLogin {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val GEN_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header"
    private const val POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=%s&source=main-fe-header"
    private const val DEADLINE_MS = 180_000L
    private const val POLL_INTERVAL_MS = 2_000L

    suspend fun login(emit: (BiliLoginState) -> Unit) = withContext(Dispatchers.IO) {
        val gen = runCatching { httpGet(GEN_URL).first }.getOrNull()
        val data = gen?.let { JSONObject(it).optJSONObject("data") }
        val qrUrl = data?.optString("url").orEmpty()
        val key = data?.optString("qrcode_key").orEmpty()
        if (qrUrl.isEmpty() || key.isEmpty()) {
            emit(BiliLoginState.Failed("generate"))
            return@withContext
        }
        emit(BiliLoginState.QrReady(qrUrl))

        val deadline = SystemClock.elapsedRealtime() + DEADLINE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_INTERVAL_MS)
            val pollBody = runCatching { httpGet(POLL_URL.format(URLEncoder.encode(key, "UTF-8"))).first }.getOrNull()
            // 网络临时抖动: 保持等待, 不清掉二维码; 业务层报错才退出。
            if (pollBody == null) {
                emit(BiliLoginState.WaitingScan)
                continue
            }
            val root = runCatching { JSONObject(pollBody) }.getOrNull()
            if (root == null || root.optInt("code", 0) != 0) {
                emit(BiliLoginState.Failed(root?.optString("message").orEmpty()))
                return@withContext
            }
            val d = root.optJSONObject("data")
            val code = d?.optInt("code", -1) ?: -1
            when (code) {
                0 -> {
                    val successUrl = d?.optString("url").orEmpty()
                    val cookies = resolveSuccessUrl(successUrl)
                    if (loginCookiesComplete(cookies)) {
                        BiliAccount.save(cookies)
                        emit(BiliLoginState.Success)
                    } else {
                        emit(BiliLoginState.Failed("missing cookies"))
                    }
                    return@withContext
                }
                86038 -> {
                    emit(BiliLoginState.Expired)
                    return@withContext
                }
                86090 -> emit(BiliLoginState.ScannedConfirm)
                86101 -> emit(BiliLoginState.WaitingScan)
                else -> {
                    emit(BiliLoginState.Failed(d?.optString("message").orEmpty()))
                    return@withContext
                }
            }
        }
        emit(BiliLoginState.Expired)
    }

    /** 登录成功要求三字段齐全: 缺一个都按未登上处理(对照 BT 的 missing cookies 判定)。 */
    private fun loginCookiesComplete(c: Map<String, String>): Boolean =
        !c["SESSDATA"].isNullOrEmpty() && !c["bili_jct"].isNullOrEmpty() && !c["DedeUserID"].isNullOrEmpty()

    /** 成功链接: 旧流程 query 直接带登录 cookie; 新流程只有 ticket, 去 crossDomain 换。 */
    private fun resolveSuccessUrl(url: String): Map<String, String> {
        val q = parseQuery(url)
        if (q.containsKey("SESSDATA")) return q
        val ticket = q["ticket"] ?: return emptyMap()
        val gourl = q["gourl"] ?: "https://www.bilibili.com"
        return exchangeTicket(ticket, gourl)
    }

    private fun exchangeTicket(ticket: String, gourl: String): Map<String, String> {
        val cookies = mutableMapOf<String, String>()
        val hosts = listOf(
            "https://passport.biligame.com/x/passport-login/web/crossDomain",
            "https://passport.bilibili.com/x/passport-login/web/crossDomain",
            "https://passport.biligame.com/crossDomain",
        )
        for (host in hosts) {
            var url = "$host?ticket=${URLEncoder.encode(ticket, "UTF-8")}&gourl=${URLEncoder.encode(gourl, "UTF-8")}"
            repeat(5) { hop ->
                val c = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return@repeat
                c.requestMethod = "GET"
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Referer", "https://passport.bilibili.com/")
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.instanceFollowRedirects = false
                val sc = c.responseCode
                cookies += readSetCookies(c)
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (cookies.containsKey("SESSDATA")) return cookies
                if (loc.isNullOrEmpty() || hop == 4) return@repeat
                url = loc
            }
            if (cookies.containsKey("SESSDATA")) return cookies
        }
        return cookies
    }

    private fun httpGet(url: String): Pair<String, Map<String, String>> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", "https://passport.bilibili.com/")
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        val body = c.inputStream.bufferedReader().readText()
        val cookies = readSetCookies(c)
        c.disconnect()
        return body to cookies
    }

    private fun readSetCookies(c: HttpURLConnection): Map<String, String> {
        val out = mutableMapOf<String, String>()
        c.headerFields["Set-Cookie"]?.forEach { raw ->
            val pair = raw.substringBefore(";")
            val eq = pair.indexOf("=")
            if (eq > 0) out[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
        }
        return out
    }

    private fun parseQuery(url: String): Map<String, String> {
        val qs = url.substringAfter("?", "")
        if (qs.isEmpty()) return emptyMap()
        return qs.split("&").mapNotNull {
            val eq = it.indexOf("=")
            if (eq < 0) return@mapNotNull null
            URLDecoder.decode(it.substring(0, eq), "UTF-8") to URLDecoder.decode(it.substring(eq + 1), "UTF-8")
        }.toMap()
    }
}
