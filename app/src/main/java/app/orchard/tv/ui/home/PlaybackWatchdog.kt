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
    /** 连续异常达到阈值后回调，由宿主决定重新加载、换源或升级成看门狗换源。 */
    private val onJank: (sourceId: String) -> Unit,
    /**
     * 只看"明显卡住"(真冻住: 没在播, 或 position 不走且 buffer 不涨), 不看低帧率/掉帧爆发。
     * 看门狗设置为关时用这个: 关掉的是"卡了就换源"的自动动作, 不是"卡死了也不管"——
     * 真冻住仍要交给宿主去查开播/下播, 只重载不换源。
     */
    private val frozenOnly: Boolean = false,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            var lastDropped = 0L
            var lastRendered = 0L
            var lastPos = -1L
            var lastBuffered = -1L
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
                    droppedNow = (c?.droppedBufferCount ?: 0L).toLong()
                    renderedNow = (c?.renderedOutputBufferCount ?: 0L).toLong()
                } catch (t: Throwable) { /* player 已释放等 */ }
                val droppedDelta = droppedNow - lastDropped
                val renderedDelta = if (lastRendered == 0L) 0L else renderedNow - lastRendered
                lastDropped = droppedNow
                lastRendered = renderedNow
                val pos = exo.currentPosition
                val buf = exo.bufferedPosition
                val posDelta: Long = if (lastPos < 0) 1000L else pos - lastPos
                val bufDelta: Long = if (lastBuffered < 0) 1000L else buf - lastBuffered
                lastPos = pos
                lastBuffered = buf

                // 对 10-20fps 低帧率直播(B站/央视常见)：renderedDelta 自然就低，
                // 不能再用"2s 渲染 <renderedThreshold 帧"直接判 bad。
                // 真正的"冻住"是 position 几乎不动 AND buffer 不再增长；
                // 如果 position 在走、buffer 在涨，即使 rendered 少也是低帧率直播，不算 bad。
                val playing = exo.isPlaying
                val buffering = exo.isLoading
                // 真冻住：没在播，或 2s 内 position 走不到 100ms 且 buffer 不再增长
                val frozen = !playing || (posDelta < FROZEN_POS_DELTA_MS && bufDelta <= 0L)
                // 掉帧爆发：2s 丢 >dropThreshold 帧(真卡顿)
                val dropBurst = droppedDelta > dropThreshold
                // 低帧率直播白名单：在播且不在缓冲、position 推进 >500ms/2s、buffer 还在涨
                // —— 这是正常的低帧率直播，rendered 少但内容在推进，不算 bad
                val lowFpsLive = playing && !buffering && posDelta > LOWFPS_MIN_POS_DELTA_MS && bufDelta > 0L
                val lowFps = !lowFpsLive && renderedDelta > 0L && renderedDelta < renderedThreshold
                // frozenOnly(看门狗关闭): 只认"真冻住", 不把掉帧爆发/低帧率算成卡死。
                val bad = if (frozenOnly) frozen else frozen || dropBurst || lowFps
                stuckStreak = if (bad) stuckStreak + 1 else 0
                if (stuckStreak >= streakNeeded) {
                    DiagLog.i("channel preview: source $sourceId jank (frozen=$frozen dropped=$droppedDelta rendered=$renderedDelta pos=$posDelta buf=$bufDelta lowFpsLive=$lowFpsLive), handle")
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
        // 真冻住：2s 采样窗口内 position 推进不足 100ms 且 buffer 不涨
        const val FROZEN_POS_DELTA_MS = 100L
        // 低帧率直播白名单：position 推进超过 500ms/2s 视为在正常播放
        const val LOWFPS_MIN_POS_DELTA_MS = 500L
    }
}
