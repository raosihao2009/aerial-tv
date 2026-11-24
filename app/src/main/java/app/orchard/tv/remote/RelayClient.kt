package app.orchard.tv.remote

import android.content.Context
import android.os.Build
import android.util.Base64
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.SystemProbe
import app.orchard.tv.wallpaper.PhoneServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.security.SecureRandom

/**
 * 远程中继客户端：电视主动出站连到中继服务，把手机经中继下发的请求回环到本机
 * [PhoneServer] 处理，再把响应回传中继。局域网通道保持不变，这里只补公网远程这一条路。
 *
 * 传输是 HTTP 长轮询（见 relay/README.md），不引入 WebSocket 库；单线程循环，断线按
 * 指数退避重连。中继地址在 prefs 里可配，留空则不启动远程通道。
 *
 * MVP 限制：只转发控制类小请求；整机备份、大批量照片仍走局域网。
 */
object RelayClient {
    private const val PREFS = "remote_relay"
    /** 中继公网地址；留空表示远程通道关闭。 */
    private const val DEFAULT_RELAY_URL = "https://aerial-relay-pwmyuzhaef.cn-hangzhou.fcapp.run"

    private const val HELLO_INTERVAL_MS = 30_000L
    private const val POLL_TIMEOUT_MS = 25_000
    private const val REQ_TIMEOUT_MS = 15_000
    private const val MAX_BACKOFF_MS = 30_000L
    private const val LOOPBACK_PORT = PhoneServer.PORT

    private val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
    private val random = SecureRandom()

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(context: Context) {
        if (thread?.isAlive == true) return
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString("relay_url", DEFAULT_RELAY_URL)?.trimEnd('/')
        if (url.isNullOrEmpty()) return

        val id = deviceId(prefs)
        val secret = deviceSecret(prefs)
        running = true
        thread = Thread { loop(appCtx, url, id, secret) }.also {
            it.isDaemon = true
            it.name = "aerial-relay"
            it.start()
        }
        DiagLog.i("远程中继: 已启动 id=$id")
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** 供电视界面展示的配对信息: 设备码 + 密钥; 不存在时就地生成并持久化。 */
    fun pairingInfo(context: Context): Pair<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return deviceId(prefs) to deviceSecret(prefs)
    }

    /** 远程通道是否配了中继地址(未被用户改成空)。 */
    fun isConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !prefs.getString("relay_url", DEFAULT_RELAY_URL).isNullOrEmpty()
    }

    private fun loop(context: Context, url: String, id: String, secret: String) {
        var backoff = 2_000L
        var lastHello = 0L
        var connected = false
        while (running) {
            try {
                // 开机阶段网络接口可能还没就绪: 此时不要发请求, 避免卡在半通不通的黑洞连接上。
                if (!SystemProbe.online(context)) {
                    if (connected) {
                        DiagLog.i("远程中继: 网络已断开, 等待网络恢复")
                        connected = false
                    }
                    sleep(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    lastHello = 0L
                    continue
                }
                if (System.currentTimeMillis() - lastHello > HELLO_INTERVAL_MS) {
                    hello(url, id, secret, deviceName())
                    lastHello = System.currentTimeMillis()
                }
                val reqs = poll(context, url, id, secret)
                backoff = 2_000L
                if (!connected) {
                    DiagLog.i("远程中继: 中继已连接 id=$id")
                    connected = true
                }
                for (req in reqs) handleRequest(context, url, id, secret, req)
            } catch (t: Throwable) {
                if (!running) break
                connected = false
                val kind = t.javaClass.simpleName ?: t.javaClass.name
                DiagLog.e("远程中继: 连接失败($kind: ${t.message}), ${backoff / 1000}s 后重试", t)
                sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                lastHello = 0L
            }
        }
        DiagLog.i("远程中继: 线程结束")
    }

    private fun hello(url: String, id: String, secret: String, name: String) {
        val body = JSONObject()
            .put("id", id).put("secret", secret).put("name", name)
        postJson("$url/tv/hello", body, REQ_TIMEOUT_MS)
    }

    private fun poll(context: Context, url: String, id: String, secret: String): List<JSONObject> {
        val body = JSONObject().put("id", id).put("secret", secret)
        val resp = postJson("$url/tv/poll", body, POLL_TIMEOUT_MS) ?: return emptyList()
        val arr: JSONArray = resp.optJSONArray("reqs") ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it) }
    }

    private fun handleRequest(
        context: Context,
        url: String,
        id: String,
        secret: String,
        req: JSONObject,
    ) {
        val rid = req.optString("rid")
        val method = req.optString("method", "GET").uppercase()
        var path = req.optString("path", "/")
        if (!path.startsWith("/")) path = "/$path"
        val headers = req.optJSONObject("headers") ?: JSONObject()
        val bodyB64 = req.optString("bodyB64")
        val bodyBytes = if (bodyB64.isNullOrEmpty()) null
        else Base64.decode(bodyB64, Base64.DEFAULT)

        val token = PhoneServer.currentToken(context)
        val loopback = "http://127.0.0.1:$LOOPBACK_PORT/$token$path"

        val (status, respHeaders, respBytes) = runCatching {
            loopback(method, loopback, headers, bodyBytes)
        }.getOrElse {
            DiagLog.e("远程中继: 回环请求失败 $path", it)
            Triple(502, emptyMap<String, String>(), ByteArray(0))
        }

        val res = JSONObject()
            .put("id", id).put("secret", secret).put("rid", rid)
            .put("status", status)
            .put("headers", JSONObject(respHeaders))
            .put("bodyB64", if (respBytes.isEmpty()) null
            else Base64.encodeToString(respBytes, Base64.NO_WRAP))
        postJson("$url/tv/res", res, REQ_TIMEOUT_MS)
    }

    private fun loopback(
        method: String,
        url: String,
        headers: JSONObject,
        body: ByteArray?,
    ): Triple<Int, Map<String, String>, ByteArray> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = REQ_TIMEOUT_MS
            readTimeout = REQ_TIMEOUT_MS
            instanceFollowRedirects = false
        }
        // 只转发真正影响响应的请求头; Host/Content-Length 由连接自己管。
        val forward = listOf("Content-Type", "Content-Disposition", "X-Aerial-Pin", "If-None-Match")
        for (key in forward) {
            val v = headers.optString(key, "")
            if (v.isNotEmpty()) conn.setRequestProperty(key, v)
        }
        // POST/PUT/DELETE 等方法: Android 的 HttpURLConnection 在没开输出流时, 空 body 的 POST
        // 会发出畸形请求行, 本机 NanoHTTPD 解析不出方法(回 400 "verb unhandled"); 这里统一开启
        // 输出流, body 为空就写零字节, 走正常的请求输出路径。
        val writesBody = method != "GET" && method != "HEAD"
        if (writesBody) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body ?: ByteArray(0)) }
        }
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val bytes = stream?.readBytes() ?: ByteArray(0)
        val respHeaders = mutableMapOf<String, String>()
        for (k in conn.headerFields.keys) {
            if (k == null || k.equals("Transfer-Encoding", true)) continue
            respHeaders[k] = conn.getHeaderField(k)
        }
        conn.disconnect()
        return Triple(status, respHeaders, bytes)
    }

    private fun postJson(url: String, body: JSONObject, timeoutMs: Int): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            // 非 2xx 不能当成成功: 网关 401/502 之类要记进日志, 走正常退避重试。
            if (status !in 200..299) throw IOException("HTTP $status: ${text.take(120)}")
            return runCatching { JSONObject(text) }.getOrNull()
        } finally {
            conn.disconnect()
        }
    }

    private fun deviceName(): String {
        val model = Build.MODEL ?: "Aerial TV"
        return model.replaceFirstChar { it.titlecase() }
    }

    private fun deviceId(prefs: android.content.SharedPreferences): String =
        prefs.getString("device_id", null) ?: randomCode(10).also {
            prefs.edit().putString("device_id", it).apply()
        }

    private fun deviceSecret(prefs: android.content.SharedPreferences): String =
        prefs.getString("device_secret", null) ?: randomCode(20).also {
            prefs.edit().putString("device_secret", it).apply()
        }

    private fun randomCode(len: Int): String =
        (1..len).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }
}
