package app.orchard.tv.apps

/**
 * 直播画质档位: qn → 展示名, 以及各台实际可选档位的内存缓存(只在本次运行内有效, 不持久化)。
 * 接口没返回档位时用常见档兜底, 避免菜单打开是空的。
 */
object LiveQualityOptions {
    private val names = mapOf(
        30000 to "杜比",
        20000 to "4K",
        10000 to "原画",
        800 to "1080P",
        400 to "720P",
        250 to "480P",
        150 to "360P",
    )

    fun label(qn: Int): String = names[qn] ?: "画质 $qn"

    /** 没拿到接口档位时的兜底常见档, 从高到低。 */
    private val fallback = listOf(10000, 800, 400, 250)

    private val cache = mutableMapOf<String, List<Int>>()

    fun options(sourceId: String): List<Int> = cache[sourceId] ?: fallback

    fun setOptions(sourceId: String, qns: List<Int>) {
        if (qns.isNotEmpty()) cache[sourceId] = qns
    }
}
