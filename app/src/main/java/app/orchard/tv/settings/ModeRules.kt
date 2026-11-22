package app.orchard.tv.settings

import app.orchard.tv.apps.AppCatalog
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.LIVE_KEY_PREFIX

/**
 * Kids and Elders mode's two must-keep cards (用户口径):
 * 1. 央视网快看(内置 preset_2) 一直在模式里;
 * 2. 只有选了**非必选**的直播卡片, 才需要一张能放直播预览的应用卡片 —— 有它模式桌面才有直播画面。
 *    用户再取消掉那些直播卡片时, 当初自动补进来的那张预览卡片也跟着撤掉, 不留在桌面上。
 *
 * 电视的儿童长辈设置页和手机页走同一套规矩, 所以两边各写一份迟早会走偏, 集中在这里。
 */
object ModeRules {
    const val REQUIRED_LIVE_KEY = "live:preset_2"

    private fun isLive(e: AppEntry) = e.key.startsWith(LIVE_KEY_PREFIX)

    private fun previewApps(candidates: List<AppEntry>): List<AppEntry> =
        candidates.filter { !isLive(it) && it.key in AerialSettings.liveAppPackages }

    /** 除了必选的央视网快看, 还选着别的直播卡片。 */
    fun hasExtraLive(allowed: Collection<String>): Boolean =
        allowed.any { it.startsWith(LIVE_KEY_PREFIX) && it != REQUIRED_LIVE_KEY }

    /** 现在这两条规矩要求锁住的卡片; [allowed] 默认取当前选中的。 */
    fun locks(candidates: List<AppEntry> = AppCatalog.kidsCandidates, allowed: Collection<String> = Restricted.allowed): Set<String> {
        val required = if (candidates.any { it.key == REQUIRED_LIVE_KEY }) setOf(REQUIRED_LIVE_KEY) else emptySet()
        if (!hasExtraLive(allowed)) return required
        val picked = previewApps(candidates).filter { it.key in allowed }.map { it.key }
        val preview = picked.ifEmpty { previewApps(candidates).take(1).map { it.key } }
        return required + preview
    }

    /**
     * 落一版新的选中列表: 补齐必选, 并在没有额外直播卡片时把之前自动补进来的预览卡片撤掉。
     * 用户自己勾的预览卡片不会被撤 —— 只撤 [Restricted.autoPreview] 里记着的那些。
     */
    fun apply(desired: List<String>, candidates: List<AppEntry> = AppCatalog.kidsCandidates) {
        if (candidates.isEmpty()) {
            if (desired != Restricted.allowed) Restricted.chooseAllowed(desired.distinct())
            return
        }
        val known = candidates.map { it.key }.toSet()
        var list = desired.filter { it in known }.distinct()
        val auto = Restricted.autoPreview.filter { it in known }.toMutableSet()

        // 不再要别的直播卡片了: 当初自动补的那张预览卡片一起撤掉。
        if (!hasExtraLive(list)) {
            list = list.filter { it !in auto }
            auto.clear()
        }
        // 央视网快看必选。
        if (REQUIRED_LIVE_KEY in known && REQUIRED_LIVE_KEY !in list) list = listOf(REQUIRED_LIVE_KEY) + list
        // 选了别的直播卡片: 用户自己选过预览应用就用用户的, 否则自动补一张, 并记住是补的。
        if (hasExtraLive(list) && previewApps(candidates).none { it.key in list }) {
            previewApps(candidates).firstOrNull()?.let {
                list = list + it.key
                auto += it.key
            }
        }
        Restricted.chooseAutoPreview(auto)
        if (list != Restricted.allowed) Restricted.chooseAllowed(list)
    }
}
