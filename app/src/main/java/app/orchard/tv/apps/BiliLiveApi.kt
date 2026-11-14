package app.orchard.tv.apps

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * B 站直播接口公共层: 匿名 buvid3、登录 Cookie、play info 请求与解析。
 * 取流(预览组件)和开播/档位状态轮询(ViewModel)共用, 避免两套请求头、两套解析。
 */
object BiliLiveApi {
    private var buvid3: String? = null

    /** 匿名 buvid3: 进程内缓存, 拿不到给占位串(访客档仍能请求)。 */
    fun buvid3(): String = buvid3 ?: runCatching {
        val c = URL("https://api.bilibili.com/x/frontend/finger/spi").openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
        c.connectTimeout = 8000; c.readTimeout = 8000
        val body = c.inputStream.bufferedReader().readText(); c.disconnect()
        val b3 = JSONObject(body).getJSONObject("data").getString("b_3")
        buvid3 = b3; b3
    }.getOrDefault("buvid3_placeholder")

    /** 直播请求 Cookie: 匿名 buvid3 在前, 已登录再追加 B 站登录态。 */
    fun cookieHeader(): String =
        listOfNotNull("buvid3=${buvid3()}", BiliAccount.cookie).joinToString("; ")

    /** 请求 play info, 返回响应的 data 节点; 失败为 null。qn=0 表示不指定档。 */
    fun playInfo(roomId: Long, qn: Int = 0): JSONObject? = runCatching {
        val api = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=$roomId" +
            "&protocol=0,1&format=0,1&codec=0,1&qn=$qn&platform=web"
        val c = URL(api).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
        c.setRequestProperty("Referer", "https://live.bilibili.com/")
        c.setRequestProperty("Cookie", cookieHeader())
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        val body = c.inputStream.bufferedReader().readText(); c.disconnect()
        JSONObject(body).optJSONObject("data")
    }.getOrNull()

    /** live_status: 1 开播, 其它为下播/未知。 */
    fun liveStatus(data: JSONObject?): Int = data?.optInt("live_status", 1) ?: 1

    /** 收集响应里所有 codec 的 accept_qn, 降序去重; 拿不到返回空列表(调用方自行兜底)。 */
    fun acceptedQn(data: JSONObject?): List<Int> {
        val set = mutableSetOf<Int>()
        val streams = data?.optJSONObject("playurl_info")?.optJSONObject("playurl")?.optJSONArray("stream")
            ?: return emptyList()
        for (i in 0 until streams.length()) {
            val fmts = streams.optJSONObject(i)?.optJSONArray("format") ?: continue
            for (j in 0 until fmts.length()) {
                val codecs = fmts.optJSONObject(j)?.optJSONArray("codec") ?: continue
                for (k in 0 until codecs.length()) {
                    codecs.optJSONObject(k)?.optJSONArray("accept_qn")?.let { arr ->
                        for (a in 0 until arr.length()) set.add(arr.optInt(a))
                    }
                }
            }
        }
        return set.sortedDescending()
    }
}
