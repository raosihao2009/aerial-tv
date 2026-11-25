package app.orchard.tv.ui.home

import androidx.media3.common.Player
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
    /** 对应 [isActive] 不满足时是哪一项不满足, 只用来写诊断日志; 返回 null 表示仍在工作。 */
    private val isActiveReason: (() -> String?)? = null,
    /** 连续异常达到阈值后回调，由宿主决定重新加载、换源或升级成看门狗换源。 */
    private val onJank: (sourceId: String) -> Unit,
    /**
     * 只看"明显卡住"(真冻住: 没在播, 或 position 不走且 buffer 不涨), 不看低帧率/掉帧爆发。
     * 看门狗设置为关时用这个: 关掉的是"卡了就换源"的自动动作, 不是"卡死了也不管"——
     * 真冻住仍要交给宿主去查开播/下播, 只重载不换源。
     */
    private val frozenOnly: Boolean = false,
    /**
     * "疑似卡住"由假变真/由真变假时回调。外观上画面一停就该挂加载图, 但确认卡死还要再等
     * 两三个采样窗口; 用它先把转圈图顶上去, 判明只是短暂停顿再收掉。
     */
    private val onSuspect: (Boolean) -> Unit = {},
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
            // 静默挂死兜底计数: 和上面的卡顿 streak 分开累计, 不被间歇回退/读数抖动清零。
            var deadStreak = 0
            // 最近一次有实质播放进展(位置推进/出新帧/缓冲实质增长)的时刻; 超过 DEAD_WINDOW_MS
            // 没有任何进展即判静默挂死。用时间窗口而不是单轮 delta 累计, 避免间歇微帧把计数打断。
            var lastAliveAt = 0L
            var age = 0L
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (!isActive()) {
                    DiagLog.i("直播预览: 看门狗退出 $sourceId(${isActiveReason?.invoke() ?: "inactive"})")
                    break
                }
                age += POLL_INTERVAL_MS
                if (age < warmupMs) continue
                // 一次读完解码器计数、位置、播放器状态; 任何读数异常都记到 countersOk, 不当成新帧。
                var droppedNow = 0L
                var renderedNow = 0L
                var pos = 0L
                var buf = 0L
                var countersOk = true
                var playerState = Player.STATE_IDLE
                var playWhenReady = false
                try {
                    val c = exo.videoDecoderCounters
                    c?.ensureUpdated()
                    droppedNow = (c?.droppedBufferCount ?: 0L).toLong()
                    renderedNow = (c?.renderedOutputBufferCount ?: 0L).toLong()
                    pos = exo.currentPosition
                    buf = exo.bufferedPosition
                    playerState = exo.playbackState
                    playWhenReady = exo.playWhenReady
                } catch (t: Throwable) { countersOk = false }
                val now = System.currentTimeMillis()
                val droppedDelta = droppedNow - lastDropped
                val renderedDelta = if (lastRendered == 0L) 0L else renderedNow - lastRendered
                lastDropped = droppedNow
                lastRendered = renderedNow
                val posDelta: Long = if (lastPos < 0) 1000L else pos - lastPos
                val bufDelta: Long = if (lastBuffered < 0) 1000L else buf - lastBuffered
                lastPos = pos
                lastBuffered = buf

                // 位置/缓冲回退(负增量)不是"卡住": 原地换流时 currentPosition 会归零、对齐直播边缘
                // 时也会跳变, 旧基准还停在上一条流的位置上, 一减就是大负数。以前直接拿
                // `posDelta < 100` 判 frozen, 于是刚换完流正在出帧(rendered>0)也被判成冻住,
                // 连续几次就白白再触发一次重载。这种情况只重置基准, 本轮不计异常。
                if (posDelta < 0L || bufDelta < 0L) {
                    if (stuckStreak > 0) onSuspect(false)
                    stuckStreak = 0
                    deadStreak = 0
                    lastAliveAt = now
                    lastDropped = droppedNow
                    lastRendered = renderedNow
                    continue
                }

                // 实质进展: 位置推进超阈值 / 出新帧 / 缓冲实质增长, 任一成立即认为流还活着,
                // 刷新最后进展时刻; 都不成立则保留旧时刻, 交给时间窗口判定是否挂死。
                val alive = posDelta >= FROZEN_POS_DELTA_MS || renderedDelta > 0L || bufDelta >= BUF_ALIVE_MIN_MS
                if (lastAliveAt == 0L || alive) lastAliveAt = now

                // 对 10-20fps 低帧率直播(B站/央视常见)：renderedDelta 自然就低，
                // 不能再用"2s 渲染 <renderedThreshold 帧"直接判 bad。
                // 真正的"冻住"是 position 几乎不动 AND buffer 不再增长 —— 不再拿 isPlaying 一票
                // 否决: 处在 STATE_BUFFERING 时 isPlaying 本来就是 false, 台其实只是网络抖了几秒。
                val buffering = exo.isLoading
                val frozen = posDelta < FROZEN_POS_DELTA_MS && bufDelta <= 0L
                // 卡在缓冲: 一直处于 loading 且位置不走, 不等缓冲吸干再判。缓冲还剩几秒时
                // 位置仍在推进, posDelta 够大不会命中; 真正卡死时位置停住、缓冲又补不进来。
                val stuckBuffering = buffering && posDelta < FROZEN_POS_DELTA_MS
                // 掉帧爆发按比例算(以前用绝对帧数, 60fps 流 2s 丢 40 帧 = 2% 也会命中)
                val totalFrames = droppedDelta + renderedDelta
                val dropBurst = totalFrames >= DROP_BURST_MIN_FRAMES &&
                    droppedDelta * 100L >= totalFrames * dropBurstPercent
                // 低帧率直播白名单：只要 position 在推进就当成正常播放(缓冲吃满后 bufferedPosition
                // 不动是常态, 以前要求 bufDelta > 0 会把低帧率直播间误判成卡)
                val lowFpsLive = posDelta >= LOWFPS_MIN_POS_DELTA_MS
                val lowFps = !lowFpsLive && renderedDelta > 0L && renderedDelta < renderedThreshold
                // frozenOnly(看门狗关闭): 只认"真冻住", 不把掉帧爆发/低帧率算成卡死。
                val bad = if (frozenOnly) frozen else frozen || stuckBuffering || dropBurst || lowFps

                // 静默挂死形态: 进了 IDLE 出不来; 或连续 DEAD_WINDOW_MS 没有任何实质进展。
                // 这类挂死 ExoPlayer 不会抛 onPlayerError, 普通卡顿 streak 又可能被间歇微帧打断,
                // 所以单独累计 deadStreak, 到阈值复核后直接判死救场。读数本身异常(countersOk=false)
                // 本轮不判死, 只跳过, 避免偶发反射异常误触发重载。
                val idle = playerState == Player.STATE_IDLE
                // 静默挂死: IDLE 出不来; 或连续 DEAD_WINDOW_MS 没有任何实质进展。
                // 不按单轮 noFrames&&frozen 判 —— 间歇微帧/缓冲微跳会打断单轮累计, 时间窗口不会。
                val dead = countersOk && (idle || (lastAliveAt > 0L && now - lastAliveAt > DEAD_WINDOW_MS))
                // 计数做衰减而不是一票清零: 一轮读数抖动(缓冲微涨/位置微跳)不至于打断连续判定,
                // 但持续正常播放时计数仍会自己退到 0, 不会长期挂着加载图。
                deadStreak = if (dead) deadStreak + 1 else (deadStreak - 1).coerceAtLeast(0)

                val wasStuck = stuckStreak > 0
                stuckStreak = if (bad) stuckStreak + 1 else (stuckStreak - 1).coerceAtLeast(0)
                if (bad && stuckStreak == 1) {
                    // 第一次采样就判异常: 立刻挂加载图, 别等凑满 streakNeeded 才转圈
                    onSuspect(true)
                } else if (!bad && wasStuck) {
                    onSuspect(false)
                }
                if (bad && stuckStreak == 1) {
                    DiagLog.i("直播预览: 源 $sourceId 异常累积(frozen=$frozen stuckBuffering=$stuckBuffering pos=$posDelta buf=$bufDelta buffering=$buffering rendered=$renderedDelta)")
                }

                // 静默挂死兜底: 连续 DEAD_FALLBACK_SAMPLES 个窗口没动静, 复核位置仍不走就直接救场。
                if (deadStreak >= DEAD_FALLBACK_SAMPLES) {
                    val posBefore = pos
                    delay(RECHECK_MS)
                    if (!isActive()) {
                        DiagLog.i("直播预览: 看门狗退出 $sourceId(${isActiveReason?.invoke() ?: "inactive"})")
                        break
                    }
                    val posAfter = try { exo.currentPosition } catch (t: Throwable) { posBefore }
                    val recheckAdvance = posAfter - posBefore
                    if (recheckAdvance >= RECHECK_ADVANCE_MS) {
                        onSuspect(false)
                        DiagLog.i("直播预览: 源 $sourceId 静默挂死解除(复核 ${RECHECK_MS}ms 内推进 ${recheckAdvance}ms), 继续播放")
                        deadStreak = 0
                        stuckStreak = 0
                        lastAliveAt = System.currentTimeMillis()
                        lastPos = posAfter
                        lastBuffered = try { exo.bufferedPosition } catch (t: Throwable) { lastBuffered }
                        lastDropped = try { (exo.videoDecoderCounters?.also { it.ensureUpdated() }?.droppedBufferCount ?: 0).toLong() } catch (t: Throwable) { lastDropped }
                        lastRendered = try { (exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0).toLong() } catch (t: Throwable) { lastRendered }
                        continue
                    }
                    DiagLog.i("直播预览: 源 $sourceId 静默挂死连续 $deadStreak 窗口(state=$playerState idle=$idle playWhenReady=$playWhenReady countersOk=$countersOk pos=$posDelta buf=$bufDelta rendered=$renderedDelta), 触发救场")
                    onJank(sourceId)
                    break
                }

                if (stuckStreak >= streakNeeded) {
                    // 触发前再等一个采样窗口复核: 有一类"看着卡"其实只是切流后的短暂停顿,
                    // 位置自己又走起来了就不算, 静默继续播。
                    val posBefore = pos
                    delay(RECHECK_MS)
                    if (!isActive()) break
                    val posAfter = try { exo.currentPosition } catch (t: Throwable) { posBefore }
                    val recheckAdvance = posAfter - posBefore
                    if (recheckAdvance >= RECHECK_ADVANCE_MS) {
                        onSuspect(false)
                        DiagLog.i("直播预览: 源 $sourceId 并非真卡(复核 ${RECHECK_MS}ms 内推进 ${recheckAdvance}ms), 继续播放")
                        stuckStreak = 0
                        lastAliveAt = System.currentTimeMillis()
                        lastPos = posAfter
                        lastBuffered = try { exo.bufferedPosition } catch (t: Throwable) { lastBuffered }
                        lastDropped = try { (exo.videoDecoderCounters?.also { it.ensureUpdated() }?.droppedBufferCount ?: 0).toLong() } catch (t: Throwable) { lastDropped }
                        lastRendered = try { (exo.videoDecoderCounters?.renderedOutputBufferCount ?: 0).toLong() } catch (t: Throwable) { lastRendered }
                        continue
                    }
                    DiagLog.i("直播预览: 源 $sourceId 卡顿(frozen=$frozen stuckBuffering=$stuckBuffering dropped=$droppedDelta/$totalFrames rendered=$renderedDelta pos=$posDelta buf=$bufDelta buffering=$buffering lowFpsLive=$lowFpsLive), 触发处理")
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
        // 连续这么久没有任何实质进展(位置/新帧/缓冲都不动)即判静默挂死; 用时间窗口而非单轮累计,
        // 间歇微帧/缓冲微跳撑不满这个窗口, 不会再把计数打断。
        const val DEAD_WINDOW_MS = 8000L
        // 缓冲增长超过这个量才算实质进展, 避开几十字节的读数抖动。
        const val BUF_ALIVE_MIN_MS = 300L
        // 判死后再连续这么多个(每 2s)窗口确认一次, 复核位置仍不动才救场(2×2s=4s)。
        const val DEAD_FALLBACK_SAMPLES = 2
    }
}
