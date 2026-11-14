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
    /** 掉帧爆发按比例判: 采样窗口内丢的帧占总帧(dropped+rendered)的比例超过这个百分数才算卡。 */
    private val dropBurstPercent: Int,
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
                // 真正的"冻住"是 position 几乎不动 AND buffer 不再增长 —— 不再拿 isPlaying 一票
                // 否决: 处在 STATE_BUFFERING 时 isPlaying 本来就是 false, 台其实只是网络抖了几秒。
                val buffering = exo.isLoading
                val frozen = posDelta < FROZEN_POS_DELTA_MS && bufDelta <= 0L
                // 掉帧爆发按比例算(以前用绝对帧数, 60fps 流 2s 丢 40 帧 = 2% 也会命中)
                val totalFrames = droppedDelta + renderedDelta
                val dropBurst = totalFrames >= DROP_BURST_MIN_FRAMES &&
                    droppedDelta * 100L >= totalFrames * dropBurstPercent
                // 低帧率直播白名单：只要 position 在推进就当成正常播放(缓冲吃满后 bufferedPosition
                // 不动是常态, 以前要求 bufDelta > 0 会把低帧率直播间误判成卡)
                val lowFpsLive = posDelta >= LOWFPS_MIN_POS_DELTA_MS
                val lowFps = !lowFpsLive && renderedDelta > 0L && renderedDelta < renderedThreshold
                // frozenOnly(看门狗关闭): 只认"真冻住", 不把掉帧爆发/低帧率算成卡死。
                val bad = if (frozenOnly) frozen else frozen || dropBurst || lowFps
                stuckStreak = if (bad) stuckStreak + 1 else 0
                if (stuckStreak >= streakNeeded) {
                    // 触发前再等一个采样窗口复核: 有一类"看着卡"其实只是切流后的短暂停顿,
                    // 位置自己又走起来了就不算, 静默继续播。
                    val posBefore = pos
                    delay(RECHECK_MS)
                    if (!isActive()) break
                    val posAfter = try { exo.currentPosition } catch (t: Throwable) { posBefore }
                    val recheckAdvance = posAfter - posBefore
                    if (recheckAdvance >= RECHECK_ADVANCE_MS) {
                        DiagLog.i("直播预览: 源 $sourceId 并非真卡(复核 ${RECHECK_MS}ms 内推进 ${recheckAdvance}ms), 继续播放")
                        stuckStreak = 0
                        lastPos = posAfter
                        lastBuffered = try { exo.bufferedPosition } catch (t: Throwable) { lastBuffered }
                        lastDropped = try { (exo.videoDecoderCounters?.also { it.ensureUpdated() }?.droppedBufferCount ?: 0).toLong() } catch (t: Throwable) { lastDropped }
                        lastRendered = try { (exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0).toLong() } catch (t: Throwable) { lastRendered }
                        continue
                    }
                    DiagLog.i("直播预览: 源 $sourceId 卡顿(frozen=$frozen dropped=$droppedDelta/$totalFrames rendered=$renderedDelta pos=$posDelta buf=$bufDelta buffering=$buffering lowFpsLive=$lowFpsLive), 触发处理")
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
        // 低帧率直播白名单: 只要 position 在推进即可, 不再要求 buffer 继续增长
        const val LOWFPS_MIN_POS_DELTA_MS = 1L
        // 掉帧爆发比例分母太小会抖(一片 1 丢 1 就 50%), 至少攒这么多帧再算比例
        const val DROP_BURST_MIN_FRAMES = 30L
        // 触发前的复核窗口: 同一个 2s 采样长度, 位置推进超过 500ms 就当没卡
        const val RECHECK_MS = 2000L
        const val RECHECK_ADVANCE_MS = 500L
    }
}
