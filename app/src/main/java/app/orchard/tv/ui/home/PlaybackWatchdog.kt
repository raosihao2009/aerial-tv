package app.orchard.tv.ui.home

import androidx.media3.exoplayer.ExoPlayer
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 直播卡顿看门狗：独立轮询 ExoPlayer 解码器计数，连续 [streakNeeded] 次异常即触发
 * 切源或重载。从 ChannelFocusPreview 抽出，职责单一、可独立启停。
 *
 * 设计要点：
 * - 与宿主解耦：只接收 [exo]、[sourceId]、灵敏度参数、一个 [isActive] 谓词和 [onJank] 回调。
 *   disposed/paused/loading/sourceId 等守卫由调用方在谓词里合并，看门狗只负责采样计数。
 * - 生命周期：[start] 启动轮询协程，[stop] 取消；重复 [start] 会先停掉旧轮询。
 * - 轮询跑在调用方传入的 [CoroutineScope] 上（宿主用 Main scope，ExoPlayer 非线程安全）。
 * - [onJank] 触发后自动 break，不再继续轮询；宿主切换新流后会新建一个看门狗。
 */
class PlaybackWatchdog(
    private val exo: ExoPlayer,
    private val sourceId: String,
    private val warmupMs: Long,
    private val dropThreshold: Long,
    private val renderedThreshold: Long,
    private val streakNeeded: Int,
    /** 宿主组合是否仍应让看门狗工作：合并 disposed/paused/loading/sourceId 守卫。 */
    private val isActive: () -> Boolean,
    /** 连续异常达到阈值后回调，由宿主决定切源或 reloadTick++。 */
    private val onJank: (sourceId: String) -> Unit,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            var lastDropped = 0L
            var lastRendered = 0L
            var lastPos = -1L
            var stuckStreak = 0
            var age = 0L
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (!isActive()) break
                age += POLL_INTERVAL_MS
                if (age < warmupMs) continue
                // 一次读完解码器计数
                var droppedNow = 0L
                var renderedNow = 0L
                try {
                    val c = exo.videoDecoderCounters
                    c?.ensureUpdated()
                    droppedNow = c?.droppedBufferCount ?: 0L
                    renderedNow = c?.renderedOutputBufferCount ?: 0L
                } catch (t: Throwable) { /* player 已释放等 */ }
                val droppedDelta = droppedNow - lastDropped
                val renderedDelta = if (lastRendered == 0L) 0L else renderedNow - lastRendered
                lastDropped = droppedNow
                lastRendered = renderedNow
                val pos = exo.currentPosition
                val posDelta: Long = if (lastPos < 0) 1000L else pos - lastPos
                lastPos = pos
                // 三种坏情况任一即计一次 bad：
                //   1) 冻住: 2s 内 position 走不到 300ms
                //   2) 掉帧爆发: 2s 丢 >dropThreshold 帧
                //   3) 低帧率PPT: 2s 实际只渲染 <renderedThreshold 帧
                val frozen = !exo.isPlaying || posDelta < FROZEN_POS_DELTA_MS
                val dropBurst = droppedDelta > dropThreshold
                val lowFps = renderedDelta > 0L && renderedDelta < renderedThreshold
                val bad = frozen || dropBurst || lowFps
                stuckStreak = if (bad) stuckStreak + 1 else 0
                if (stuckStreak >= streakNeeded) {
                    DiagLog.i("channel preview: source $sourceId jank (frozen=$frozen dropped=$droppedDelta rendered=$renderedDelta), handle")
                    onJank(sourceId)
                    break
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2000L
        const val FROZEN_POS_DELTA_MS = 300L
    }
}
