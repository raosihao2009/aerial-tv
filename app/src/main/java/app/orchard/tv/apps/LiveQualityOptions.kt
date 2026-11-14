package app.orchard.tv.apps

/**
 * 直播画质档位: qn → 展示名, 以及各台实际可选档位的内存缓存(只在本次运行内有效, 不持久化)。
 * 接口没返回档位时用常见档兜底, 避免菜单打开是空的。
 *
 * 档位名对齐 B 站客户端的口径(原画/蓝光/超清/高清/流畅)。不再用 "720P/480P" 这种分辨率
 * 标注: 接口只回该次请求回落到的档的分辨率, m3u8 媒体清单里也没有 RESOLUTION, 拿不到每档
 * 自己的分辨率; 硬标会和客户端的 "720P 蓝光" 这种对不上。
 */
object LiveQualityOptions {
    private val names = mapOf(
        30000 to "杜比",
        20000 to "4K",
        10000 to "原画",
        400 to "蓝光",
        250 to "超清",
        150 to "高清",
        80 to "流畅",
    )

    fun label(qn: Int): String = names[qn] ?: "画质 $qn"

    /** 没拿到接口档位时的兜底常见档, 从高到低。 */
    private val fallback = listOf(10000, 400, 250)

    private val cache = mutableMapOf<String, List<Int>>()

    fun options(sourceId: String): List<Int> = cache[sourceId] ?: fallback

    fun setOptions(sourceId: String, qns: List<Int>) {
        if (qns.isNotEmpty()) cache[sourceId] = qns
    }
}
