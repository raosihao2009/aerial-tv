package app.orchard.tv.ui.home

import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.orchard.tv.ui.components.liquidGlass
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import app.orchard.tv.LiveSource
import app.orchard.tv.R
import app.orchard.tv.ui.BootVeil
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.apps.BiliLiveApi
import app.orchard.tv.apps.LiveQualityOptions
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.SystemProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

@Composable
fun ChannelFocusPreview(
    source: LiveSource?,
    modifier: Modifier = Modifier,
    /**
     * 冷启动首屏是否已稳定: 首起播等它放开再建播放器, 避免和壁纸影片同时冷启动抢解码器。
     * 必须是读取函数而不是 Boolean: 传值的话起播协程在开始那一刻就把 false 闭包进去了,
     * snapshotFlow 读的是常量、永远不会再发, 首起播就永远停在这个等待里(实测热启动必现:
     * 只打 "wait bootStable", 之后再无取流/READY/超时日志, 加载卡片一直挂着)。
     */
    gateOpen: () -> Boolean = { true },
    onFallback: () -> String? = { null },
    onRevert: (String) -> String? = { null },
    onReadyChange: (Boolean) -> Unit = {},
    onStatusRefresh: () -> Unit = {},
    onRevertFail: () -> Unit = {},
    onSwitched: (String) -> Unit = {},
    onActuallyPlayingChange: (Boolean) -> Unit = {},
    // 加载中(含切源): 由 HomeScreen 画在最上层, 盖住壁纸和预览影片
    onLoadingChange: (Boolean) -> Unit = {},
    /** 切到指定源(和菜单里点选同一套: 先挂起, 新流真开播才提交)。 */
    onPickSource: (String) -> Unit = {},
    /** 当前全部直播源(带 liveStatus: 1 开播 / 2 下播 / 0 未知)。 */
    sourcesNow: () -> List<LiveSource> = { emptyList() },
    /** 所有直播源都下播时: 把焦点移出直播卡片, 落到相邻的普通卡片上。 */
    onMoveOffLiveCard: () -> Unit = {},
    /** 全局玻璃胶囊提示: 预览被销毁后还要看的提示走这个(焦点一移出直播卡, 预览就 dispose 了)。 */
    onGlobalToast: (String) -> Unit = {},
    /** 画质等偏好变更后自增的重载信号; 变化时即使源没变也重新取流。 */
    externalReload: Int = 0,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var surface by remember { mutableStateOf<TextureView?>(null) }

    var restartSignal by remember { mutableStateOf(0) }
    // 上一次消费的画质重载信号; 信号变化时旧流地址缓存是别的档位, 要清掉重新解析
    var lastReload by remember { mutableStateOf(externalReload) }
    // 看门狗自动切走时, 不弹"已经从X切换至Y", 只留"卡顿严重"那条
    var suppressSwitchToast by remember { mutableStateOf(false) }
    // 状态聚合：disposed/paused 是纯守卫旗标(不驱动重组, @Volatile), loading/toastMessage 是 UI 状态
    val st = remember { PreviewState() }
    // toast 时序：每次 showToast 都自增, 即使文案相同也重置 4s 倒计时
    var toastTick by remember { mutableStateOf(0) }
    fun showToast(msg: String) {
        st.toastMessage = msg
        toastTick++
    }
    // 候选流缓存: 每个源对应一组有序候选(同 codec 的备用 CDN host 在前, 其它 codec 在后),
    // 原地重载时依次轮换, 不再每次卡住都新建播放器、重拉整条流。
    val streamCache = remember { mutableMapOf<String, StreamCandidates>() }
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    var retryCount by remember { mutableStateOf(0) }
    // 原地重载进行中: 看门狗和播放器错误回调可能同时进来, 防重入
    val reloadBusy = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // 断网等待中: 网络不可达时不反复重试/切源, 只留一个等待网络恢复的协程
    val netWaitBusy = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // 恢复中: 客户端的"转圈"状态。全程挂直播加载图, 期间播放器报错不弹失败提示
    val recovering = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // 一次恢复只提示一条"正在重新获取地址", 真出画面后复位, 下次卡住才再提示
    val hintShown = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // 播放器报错计数: 重载循环靠它立刻判定这一路起不来, 不必等满 8 秒
    var errorTick by remember { mutableStateOf(0) }
    // 恢复中"网络已断开"提示只弹一次, 免得每秒刷新
    val netHint = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // 当前已在播放的源名称(用于切换失败时提示继续观看谁)
    var currentPlayingName by remember { mutableStateOf<String?>(null) }
    // 上一个正在播放的源 id(切换失败时回退焦点)
    var playingSourceId by remember { mutableStateOf<String?>(null) }
    // 把加载状态报给外面: 加载图要画在壁纸/预览影片之上
    LaunchedEffect(st) {
        androidx.compose.runtime.snapshotFlow { st.loading }.collect { onLoadingChange(it) }
    }
    // 加载遮罩：直接显示/隐藏，不用alpha渐变动画(低端GPU fill rate优化)
    val loadingAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (st.loading) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(if (st.loading) 100 else 250),
        label = "loadingAlpha"
    )
    // 当前正在跑的卡顿看门狗(纯运行时对象, 不驱动重组; 换源/dispose时 stop)
    val watchdogRef = remember { mutableStateOf<PlaybackWatchdog?>(null) }
    // 当前 player 挂载的 listener 引用(纯运行时; release 前必须 removeListener,
    // 否则 release 旧 player 会触发旧 listener 的 onPlayerError, 旧闭包还拿着旧 source 上下文,
    // 会误调 onFallback/onRevert)
    val playerListenerRef = remember { mutableStateOf<Player.Listener?>(null) }

    // ===== 卡住时的统一救场 =====
    // 规矩是"先查开播/下播, 再决定怎么动":
    //  - 还在开播: 重新加载本场。看门狗开着时, 30 秒内救场超过 3 次(第 4 次)才升级成换源;
    //  - 本场下播: 换到央视网快看;
    //  - 央视网快看也下播: 换到最近一个开播的源;
    //  - 一个开播的都没有: 焦点移出直播卡片, 去看壁纸。
    // 每个动作配一条磨砂提示(4 秒自动消失); 换源/重载都会走 st.loading, 加载图照常盖满全屏。
    val recoverTimes = remember { mutableListOf<Long>() }
    // 重组会重建这个局部变量, 用原子量把它钉在 remember 里, 免得两次救场撞在一起
    val stallBusy = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun noteRecover(): Int {
        val now = android.os.SystemClock.elapsedRealtime()
        recoverTimes.removeAll { now - it > STALL_WINDOW_MS }
        recoverTimes += now
        return recoverTimes.size
    }

    /**
     * 失败原因归类与文案(判定顺序固定: 网络 → 下播 → 空地址 → 地址失效/播放失败)。
     * 顺序很重要: 网络断的时候解析/播放同样会失败, 必须先判网络, 否则会把断网说成地址失效。
     *
     * @param offline 调用方已查过的下播结果(仅 B 站可判; 非 B 站传 false)
     * @param hasOld  是否还有旧源在播(有旧则回退旧源, 文案带"请继续观看")
     * @param fallbackLabel 无旧源时切过去的备用源名
     * @param oldLabel 有旧源时仍在看的旧源名
     */
    fun failureToast(
        offline: Boolean,
        hasOld: Boolean,
        fallbackLabel: String? = null,
        oldLabel: String? = null,
        decodeFail: Boolean = false,
    ): String {
        val sourceName = source?.name ?: "该源"
        // 这里是拼文案, 不做连通性探测(探测会阻塞几百毫秒), 只看系统的 VALIDATED 位
        val netOffline = !SystemProbe.online(context, probe = false)
        val urlEmpty = source?.url?.trim().isNullOrEmpty()
        val keepWatching = oldLabel ?: "上一个源"
        val netMsg = if (hasOld) "网络未连接，「$sourceName」暂时无法观看，请继续观看「$keepWatching」" else "网络未连接，「$sourceName」暂时无法观看"
        val offMsg = if (hasOld) "「$sourceName」已下播，请继续观看「$keepWatching」" else "「$sourceName」已下播，请观看「${fallbackLabel ?: "备用源"}」"
        // 解码失败不是地址问题: 源还在开播, 只是这台机器解不出画面, 不能说"地址可能失效"
        val decMsg = if (hasOld) "「$sourceName」的画面暂时无法解码，请继续观看「$keepWatching」" else "「$sourceName」的画面暂时无法解码，请观看「${fallbackLabel ?: "备用源"}」"
        val badMsg = if (hasOld) "「$sourceName」的直播地址可能失效了，请继续观看「$keepWatching」" else "「$sourceName」的直播地址可能失效了，请检查"
        return when {
            netOffline -> netMsg
            offline -> offMsg
            urlEmpty -> "「$sourceName」暂未设置直播地址"
            decodeFail -> decMsg
            else -> badMsg
        }
    }

    /** 离 [origin] 最近的开播源(排除 [exclude]); liveStatus==1 才算开播。 */
    fun nearestLive(exclude: Set<String>): LiveSource? {
        val list = sourcesNow()
        val origin = list.indexOfFirst { it.id == source?.id }.let { if (it < 0) 0 else it }
        return list.withIndex()
            .filter { (_, s) -> s.id !in exclude && s.liveStatus == 1 }
            .minByOrNull { (i, _) -> kotlin.math.abs(i - origin) }
            ?.value
    }

    /**
     * 原地重载(对齐 B 站客户端的缓冲重载): 不销毁 player、不解绑 surface, 只换 MediaSource。
     * TextureView 保留最后一帧, 新流缓冲上来自动续播; 候选里还有备用 CDN/编码就先轮换,
     * 全部耗尽才重新调 playinfo 取新签名地址。
     */
    // 重载失败后交回救场流程的入口: 两个局部函数互相引用, 用变量打破前向引用限制
    var retryStall: (String) -> Unit = {}
    // handleStall 定义在 startWatchdog 之后, 用变量打破前向引用限制
    var onJankHandler: (String) -> Unit = {}

    /**
     * 为"当前正在播的这一路"起一个卡顿看门狗。
     * 创建新 player 时起一次; 原地重载(不换 player)之后也必须再起一次 ——
     * 看门狗触发后会自己 break 结束, 不重启的话原地重载救回来以后就再没人盯着,
     * 挨到下一次断网时画面冻住也不会有任何反应, 连加载图都不弹。
     */
    val startWatchdog: (androidx.media3.exoplayer.ExoPlayer, String, Long?) -> Unit = { exo, id, warmupOverride ->
        val sens = AerialSettings.jankSensitivity
        val warmupMs: Long; val dropBurstPercent: Int; val renderedThreshold: Long; val streakNeeded: Int
        when (sens) {
            AerialSettings.JankSensitivity.Low -> { warmupMs = 12000L; dropBurstPercent = 25; renderedThreshold = 12L; streakNeeded = 3 }
            AerialSettings.JankSensitivity.High -> { warmupMs = 4000L; dropBurstPercent = 12; renderedThreshold = 28L; streakNeeded = 2 }
            // Medium / Off: 连续 3 次(约 6s)异常才动, 掉帧按 18% 比例算
            else -> { warmupMs = 8000L; dropBurstPercent = 18; renderedThreshold = 20L; streakNeeded = 3 }
        }
        // 停掉旧看门狗(换源/重载), 再为新流起一个
        watchdogRef.value?.stop()
        val wd = PlaybackWatchdog(
            exo = exo,
            sourceId = id,
            // 预热期用来避开起播/切流那几秒的抖动。原地重载救回来时流已经在播、
            // 也刚对齐过位置, 不需要 8 秒预热 —— 否则刚恢复就又冻住这段时间完全无人盯。
            warmupMs = warmupOverride ?: warmupMs,
            dropBurstPercent = dropBurstPercent,
            renderedThreshold = renderedThreshold,
            streakNeeded = streakNeeded,
            isActive = {
                // 不能用 st.loading 当守卫: 疑似卡住会先把加载图挂上, 那样看门狗一停摆
                // 就永远等不到确认, 只能一直转圈。恢复中由 recovering 挡。
                !st.disposed && !st.paused && !recovering.get() && playingSourceId == id
            },
            frozenOnly = sens == AerialSettings.JankSensitivity.Off,
            onJank = { sid -> onJankHandler(sid) },
            // 画面一停就先顶加载图; 判明只是短暂停顿(复核通过/异常消失)再收掉
            onSuspect = { suspected ->
                if (st.disposed) { /* 交给 dispose 收图 */ }
                else if (suspected) { st.loading = true }
                else if (!recovering.get()) { st.loading = false }
            },
        )
        wd.start(scope)
        watchdogRef.value = wd
    }

    fun reloadInPlace(stalled: LiveSource) {
        if (!reloadBusy.compareAndSet(false, true)) return
        val p = player
        if (p == null || playingSourceId != stalled.id) {
            reloadBusy.set(false)
            return
        }
        scope.launch {
            // 从卡住这一刻起就挂上直播加载图(那张转圈图), 一路换 URL 期间不撤,
            // 只有真出画面才撤; 期间任何播放器报错都不弹"地址可能失效"。
            st.loading = true
            recovering.set(true)
            if (hintShown.compareAndSet(false, true)) {
                showToast("正在尝试重新加载「${stalled.name}」")
            }
            var startAt = android.os.SystemClock.elapsedRealtime()
            var loaded = false
            var handBack = false
            var rounds = 0
            try {
                while (!st.disposed && !loaded &&
                    android.os.SystemClock.elapsedRealtime() - startAt < RECOVER_MAX_MS
                ) {
                    val waitStartAt = android.os.SystemClock.elapsedRealtime()
                    rounds++
                    val key = stalled.url ?: stalled.id
                    var cands = streamCache[key]
                    var url = cands?.pollNext()
                    if (url == null) {
                        // 候选耗尽: 重拉 playinfo 取新签名地址, 最多 3 次
                        var fresh: List<String> = emptyList()
                        for (attempt in 1..3) {
                            fresh = withContext(Dispatchers.IO) { resolveCandidates(stalled, context) }
                            if (fresh.isNotEmpty()) break
                            kotlinx.coroutines.delay(2000)
                        }
                        if (fresh.isEmpty()) {
                            DiagLog.i("直播预览: ${stalled.id} 原地重载取流失败, 第 $rounds 轮")
                            // 取不到流先真探一次连通性: 路由器拉黑时系统 VALIDATED 位照样是连着的,
                            // 只看那一位会把断网说成"地址失效"。断网就挂着转圈等恢复, 不耗恢复时限。
                            if (!withContext(Dispatchers.IO) { SystemProbe.online(context) }) {
                                if (netHint.compareAndSet(false, true)) {
                                    showToast("网络已断开，正在等待恢复")
                                }
                                var waited = 0L
                                while (!st.disposed &&
                                    !withContext(Dispatchers.IO) { SystemProbe.online(context) }
                                ) {
                                    kotlinx.coroutines.delay(1000L)
                                    waited += 1000L
                                    if (waited % 15_000L == 0L) showToast("网络已断开，正在等待恢复")
                                }
                                netHint.set(false)
                                if (st.disposed) break
                                DiagLog.i("直播预览: ${stalled.id} 网络已恢复, 继续换流")
                                // 等网络这段时间不计入恢复时限, 否则拉黑久了会被 60s 直接判死
                                startAt = startAt + android.os.SystemClock.elapsedRealtime() - waitStartAt
                            }
                            kotlinx.coroutines.delay(RECOVER_GAP_MS)
                            continue
                        }
                        cands = StreamCandidates(fresh)
                        streamCache[key] = cands
                        url = cands.pollNext()!!
                    }
                    DiagLog.i("直播预览: ${stalled.id} 原地重载, 第 $rounds 轮 ${cands?.index ?: 0}/${cands?.urls?.size ?: 0} 路候选")
                    val hls = buildHlsMediaSource(url, stalled.isBilibiliStream, context)
                    // 不 release、不暂停: 上一轮最后一帧留在转圈图下面, 新流就绪后直接续播
                    p.setMediaSource(hls)
                    p.prepare()
                    // 等这一路起流(最多 8s); 播放器一报错立刻换下一路, 不空等
                    val tick = errorTick
                    var ready = false
                    val deadline = System.currentTimeMillis() + 8_000
                    while (System.currentTimeMillis() < deadline) {
                        kotlinx.coroutines.delay(200)
                        if (errorTick != tick) break
                        if (p.playbackState == Player.STATE_READY &&
                            p.bufferedPosition - p.currentPosition >= 1500L
                        ) { ready = true; break }
                    }
                    if (ready) {
                        // 不能 seekTo(bufferedPosition): 那是缓冲的最末端, 前面一秒数据都没有,
                        // 一起播立刻就再饿一次 —— 表现就是"加载完 1~2 秒又卡"。往回留 2 秒安全垫。
                        p.seekTo(
                            (p.bufferedPosition - RECOVER_SEEK_BACK_MS)
                                .coerceAtLeast(p.currentPosition.coerceAtLeast(0L))
                        )
                        DiagLog.i("直播预览: ${stalled.id} 原地重载就绪(第 $rounds 轮)")
                        loaded = true
                        // 看门狗触发后已经 break 结束, 原地重载没换 player, 这里必须重新挂上看门狗,
                        // 否则救回来以后就没人再盯着, 下一次断网冻住连加载图都不会弹。
                        // 预热用短档: 刚恢复就又卡的情况要能立刻被发现。
                        startWatchdog(p, stalled.id, RELOAD_WARMUP_MS)
                    } else {
                        DiagLog.i("直播预览: ${stalled.id} 第 $rounds 轮未就绪, 换下一路")
                        kotlinx.coroutines.delay(RECOVER_GAP_MS)
                    }
                }
                if (!loaded && !st.disposed) {
                    // 全部候选轮完还没起来: 撤转圈, 只弹一次真正的失败, 再交回救场流程
                    DiagLog.i("直播预览: ${stalled.id} 恢复 ${RECOVER_MAX_MS / 1000}s 未成功, 交回救场流程")
                    st.loading = false
                    showToast("「${stalled.name}」暂时无法播放，请稍后再试")
                    // 交给救场流程前先把看门狗挂回去: 否则之后又断网时没人盯着, 不会有任何反应
                    startWatchdog(p, stalled.id, RELOAD_WARMUP_MS)
                    handBack = true
                }
            } catch (t: Throwable) {
                DiagLog.i("直播预览: 原地重载异常: ${t.message}")
                st.loading = false
            } finally {
                if (loaded) st.loading = false
                recovering.set(false)
                if (loaded || handBack) hintShown.set(false)
                reloadBusy.set(false)
            }
            if (handBack) retryStall(stalled.id)
        }
    }

    fun handleStall(id: String) {
        if (!stallBusy.compareAndSet(false, true)) return
        val stalled = sourcesNow().firstOrNull { it.id == id } ?: source
        if (stalled == null) {
            stallBusy.set(false)
            return
        }
        scope.launch {
            try {
                // 网络本身断开时重载毫无意义: 候选地址和 playInfo 都拉不通, 只会白白重建一轮
                // 并把 30 秒窗口的救场次数用掉, 提前耗尽升级切源的机会。等网络回来再换流。
                // 用可达性而不是 transport: 断路由器外网时 WiFi 仍连着, kind 还是 Wifi。
                if (!withContext(Dispatchers.IO) { SystemProbe.online(context) }) {
                    DiagLog.i("直播预览: ${stalled.id} 卡住且网络不可达, 等待网络恢复后重载")
                    showToast("网络已断开，正在等待恢复")
                    var waited = 0L
                    var lastHint = 0L
                    var aliveNow = false
                    // 断网期间不重试、不切备用源: 这些操作在不通的网络上都拉不通, 只会把候选
                    // 和救场次数用光。一直等到网络恢复再原地换流; 预览离开(disposed)就退出。
                    while (!st.disposed && !aliveNow) {
                        kotlinx.coroutines.delay(1000L)
                        waited += 1000L
                        // 探测有 800ms 上限, 放 IO 线程, 别卡主线程动画
                        aliveNow = withContext(Dispatchers.IO) { SystemProbe.online(context) }
                        if (waited - lastHint >= 15_000L) {
                            lastHint = waited
                            showToast("网络已断开，正在等待恢复")
                        }
                    }
                    if (st.disposed) return@launch
                    DiagLog.i("直播预览: ${stalled.id} 网络已恢复, 重新加载")
                    reloadInPlace(stalled)
                    return@launch
                }
                DiagLog.i("直播预览: ${stalled.id} 卡住, 正在检查是否仍在开播")
                val offline = withContext(Dispatchers.IO) { checkIfOffline(stalled, context) }
                onStatusRefresh()
                val cctv = sourcesNow().firstOrNull { it.id == CCTV_ID }
                val cctvName = cctv?.name ?: "央视网快看"
                val sens = AerialSettings.jankSensitivity
                // 每次救场(重载或换源)都记一笔, 30 秒窗口内的次数决定要不要升级成看门狗换源
                val times = noteRecover()
                when {
                    // 下播: 本场换央视网快看
                    offline && stalled.id != CCTV_ID && cctv != null && cctv.liveStatus != 2 -> {
                        onPickSource(CCTV_ID)
                        showToast("「${stalled.name}」已下播，请观看「$cctvName」")
                        DiagLog.i("直播预览: ${stalled.id} 已下播, 已切到 $CCTV_ID")
                    }
                    // 下播且本场就是央视网快看, 或它也已下播: 换最近一个开播的源
                    offline -> {
                        val next = nearestLive(exclude = setOf(stalled.id, CCTV_ID))
                        if (next != null) {
                            onPickSource(next.id)
                            // 本场就是央视网快看, 或它也已下播: 提示里说的是"央视网快看已下播";
                            // 列表里根本没有央视网快看时, 说本场自己下播更准确。
                            val offName = if (stalled.id != CCTV_ID && cctv == null) stalled.name else cctvName
                            showToast("「$offName」已下播，请观看「${next.name}」")
                            DiagLog.i("直播预览: $CCTV_ID 已下播, 已切到 ${next.id}")
                        } else {
                            // 焦点一移出直播卡, 预览就 dispose, 挂在预览里的提示会立刻消失:
                            // 这条必须走全局胶囊。
                            onMoveOffLiveCard()
                            onGlobalToast("所有直播卡片均未开播，请欣赏桌面壁纸")
                            DiagLog.i("直播预览: 没有剩余开播源, 焦点已离开直播卡片")
                        }
                    }
                    // 还在开播: 先重载; 看门狗开着且 30 秒内救场超过 3 次(第 4 次起),
                    // 才升级成真正的看门狗换源。央视网快看没有可换的备用源, 只重载。
                    else -> {
                        val escalate = sens != AerialSettings.JankSensitivity.Off && times >= STALL_ESCALATE_TIMES
                        val target = if (escalate && stalled.id != CCTV_ID) {
                            suppressSwitchToast = true
                            onFallback()
                        } else null
                        if (target != null) {
                            showToast("「${stalled.name}」卡顿严重，已切换至「$target」")
                            DiagLog.i("直播预览: ${stalled.id} 30 秒内卡住 $times 次, 看门狗已切到 $target")
                        } else {
                            // 还在开播: 原地换流重载, 不重建播放器; 30 秒窗口次数由 noteRecover 统计,
                            // 到阈值才升级成 onFallback 切源。提示由 reloadInPlace 按耗时决定(快恢复不弹)。
                            reloadInPlace(stalled)
                            DiagLog.i("直播预览: ${stalled.id} 仍在开播, 原地重载(30 秒内第 $times 次)")
                        }
                    }
                }
            } catch (t: Throwable) {
                DiagLog.i("直播预览: 卡顿处理失败: ${t.message}")
            } finally {
                stallBusy.set(false)
            }
        }
    }
    // 现在 handleStall 已定义, 把重载失败的回落入口接上
    retryStall = { id -> handleStall(id) }
    onJankHandler = { id -> handleStall(id) }

    LaunchedEffect(source?.url, restartSignal, externalReload) {
        if (source == null) {
            player?.release(); player = null
            DiagLog.i("预览 effect 跳过: 源为空")
            return@LaunchedEffect
        }
        // 切回当前正在播放的源(onRevert): 不重新加载, 继续用现有player, 避免画面声音回溯;
        // externalReload > 0 表示画质等偏好刚改过, 同样要重新取流。卡住的原地重载不走这个 effect。
        if (externalReload == 0 && source.id == playingSourceId && player != null) {
            DiagLog.i("预览 effect 跳过: 已在播放 ${source.id}")
            return@LaunchedEffect
        }

        DiagLog.i("预览 effect 进入 id=${source.id} url=${source.url} player=${if (player != null) "已绑定" else "无"}")

        // 冷启动: 直播预览也是个硬件解码器, 别和开屏动画同时起播(实测首列直播预览+视频壁纸
        // 时过渡一卡一卡; 把预览挪到第≥2列就顺, 就是因为起播时机错开了)。等揭开后再走下面。
        while (!BootVeil.revealed) kotlinx.coroutines.delay(100)
        DiagLog.i("预览 effect 遮罩已揭下 id=${source.id}")

        // 新聚焦到直播卡时, 卡片焦点缩放动画还在跑; 主线程紧接着就建 ExoPlayer/挂解码会和缩放
        // 抢主线程, 低端盒子上缩放动画掉帧。先等缩放弹簧落定(~180ms)再起播, 让出前一两帧。
        if (player == null) kotlinx.coroutines.delay(180)

        // 加载遮罩：始终显示加载图, 直到新流就绪
        st.loading = true
        // 首起播(冷启动/回到预览卡第一次, 还没有旧 player)与运行中切换分开计时:
        // 冷启动要和壁纸影片、图标网格、状态轮询抢资源, 解码器也没热, 出帧天然慢; 用同一个
        // 15s 窗口容易把"起播慢"误判成坏源。首起播放宽到 25s, 切换仍保持 15s。
        val firstRun = player == null
        val loadTimeoutMs = if (firstRun) 25_000L else 15_000L

        // 冷启动首起播错峰: 等首屏稳定(网格/贴图落定、壁纸解码器先热起来)再建播放器, 避免
        // 两路硬解同一秒冷启动把刚拉起的直播拖到卡住、触发看门狗重载。运行中切换不等。
        if (firstRun && !gateOpen()) {
            DiagLog.i("预览 effect 等待 bootStable id=${source.id}")
            // 读的是 gateOpen() 里的 vm.bootStable 本身, 它翻转时 snapshotFlow 才会重新取值;
            // 这里必须再套一层"超时兜底": 闸门万一因为别的路径没置真, 也不能把首起播永远按住。
            withTimeoutOrNull(3_000) {
                androidx.compose.runtime.snapshotFlow { gateOpen() }.first { it }
            }
            DiagLog.i("预览 effect bootStable 就绪 id=${source.id} open=${gateOpen()}")
        }

        if (externalReload != lastReload) {
            streamCache.clear()
            lastReload = externalReload
        }
        val cacheKey = source.url ?: ""
        var cands = streamCache[cacheKey]
        var streamUrl: String? = cands?.pollNext()
        if (streamUrl == null) {
            // 解析新流候选, 最多重试 5 次(网络刚恢复/待机回来可能需要等)
            var fresh: List<String> = emptyList()
            for (attempt in 1..5) {
                fresh = withContext(Dispatchers.IO) { resolveCandidates(source, context) }
                if (fresh.isNotEmpty()) break
                kotlinx.coroutines.delay(2000)
            }
            // 只缓存成功的候选, 失败不缓存(避免下次直接读空)
            if (fresh.isNotEmpty()) {
                cands = StreamCandidates(fresh)
                streamCache[cacheKey] = cands
                streamUrl = cands.pollNext()
            }
        }
        DiagLog.i("直播预览: 取流地址 = $streamUrl")

        if (streamUrl == null) {
            // 解析失败(可能已下播或网络未就绪)
            DiagLog.i("直播预览: 取流地址解析失败")
            st.loading = false
            val offline = withContext(Dispatchers.IO) { checkIfOffline(source, context) }
            if (player != null) {
                // 切换源失败：回退到旧源(本来就在播)
                playingSourceId?.let { onRevert(it) }
                showToast(failureToast(offline = offline, hasOld = true, oldLabel = currentPlayingName))
                onRevertFail()
            } else {
                // 首次加载失败: 切换到备用源
                DiagLog.i("直播预览: 首次加载失败, 切换到备用源")
                val target = onFallback()
                showToast(failureToast(offline = offline, hasOld = false, fallbackLabel = target))
            }
            // 失败路径没走到成功切换的 reset, 这里补回, 避免下次用户主动切源被 suppress
            suppressSwitchToast = false
            return@LaunchedEffect
        }

        // 准备新流
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(1920, 1080)
                    // 不强制最高码率, 让ExoPlayer自适应网络/CPU, 低端设备更流畅
            )
        }
        val exo = ExoPlayer.Builder(context)
            // 硬解初始化失败时自动退到软解(下一个解码器)。TCL 上实测同一路低码率直播在硬解
            // 上反复 DECODER_INIT_FAILED / FAILED_RUNTIME_CHECK, 一帧都不出; 允许回退后
            // 还有软解这条路能播, 而不是整路源直接判失败。
            .setRenderersFactory(
                androidx.media3.exoplayer.DefaultRenderersFactory(context)
                    .setEnableDecoderFallback(true)
            )
            .setTrackSelector(trackSelector)
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    // 低端盒子(Allwinner DBBOX-A2, Mali-G31, 仅 1960MB RAM)针对 HLS 直播调优：
                    // - minBuffer 5s->3s, maxBuffer 15s->10s：减少缓冲占用的内存(TS 分片+解码帧)，
                    //   2GB RAM 盒子上 15s 缓冲会明显占内存并挤占解码缓存
                    // - bufferForPlayback 2s->1.5s：更快起播(焦点预览切源时少等)
                    // - bufferForPlaybackAfterRebuffer 3s->2.5s：卡顿后恢复更快
                    // 直播源本身低帧率(10-20fps)，10s maxBuffer 足够吸收网络抖动
                    .setBufferDurationsMs(3000, 10000, 1500, 2500)
                    // 不保留回放 back-buffer，省内存(直播不需要回看)
                    .setBackBuffer(0, false)
                    .build()
            )
            .build()
        var readyListener: Player.Listener? = null
        try {
        val hls = buildHlsMediaSource(streamUrl, source.isBilibiliStream, context)

        // 等待新流READY且缓冲足够(至少3秒)再切换，避免开播后卡缓冲
        var ready = false
        // 超时兜底, 避免永远卡住; 窗口按首起播/切换分开(见 loadTimeoutMs)
        scope.launch {
            kotlinx.coroutines.delay(loadTimeoutMs)
            if (st.disposed) return@launch
            if (!ready) {
                DiagLog.i("直播预览: 加载超时 ${loadTimeoutMs / 1000}s (${if (firstRun) "首次" else "切换"}), 切换到备用源")
                st.loading = false
                val offline = withContext(Dispatchers.IO) { checkIfOffline(source, context) }
                val target = onFallback()
                showToast(failureToast(offline = offline, hasOld = player != null, fallbackLabel = target, oldLabel = currentPlayingName))
                suppressSwitchToast = false
            }
        }
        readyListener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                DiagLog.i("直播预览: 首帧 id=${source.id} player=${if (player === exo) "已绑定" else "未绑定"}")
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    retryCount = 0
                    // 等缓冲够再标记ready(主线程轮询, ExoPlayer非线程安全)
                    scope.launch {
                        val deadline = System.currentTimeMillis() + 10_000
                        while (System.currentTimeMillis() < deadline) {
                            if (exo.bufferedPosition - exo.currentPosition >= 3000L) {
                                ready = true
                                break
                            }
                            kotlinx.coroutines.delay(200)
                        }
                        if (!ready) ready = true
                        onReadyChange(true)
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (st.disposed) return  // 组合离开释放player触发的错误, 不处理
                errorTick++
                DiagLog.i("播放器错误: ${error.errorCodeName}, 第 $retryCount 次重试")
                // 恢复中(正在转圈换地址): 这一路的失败只交回重载循环换下一路,
                // 不在这里弹提示、不在这里升格切源, 免得一轮几路地址各弹一次"失效"。
                if (recovering.get()) {
                    DiagLog.i("播放器错误: 恢复中, 交给重载循环换下一路")
                    return
                }
                // 网络不可达时既不重试也不切源: 断网时换节点、换台都拉不通, 只会把候选和重试
                // 次数耗光, 最后走成"地址可能失效"提示或无奈切走。只等网络恢复再换流。
                val netErr = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                // ExoPlayer 的错误回调在主线程, 这里不能直接做几百毫秒的连通性探测。
                if (netErr && !SystemProbe.online(context, probe = false)) {
                    DiagLog.i("播放器错误: 网络不可达, 暂停重试等待网络恢复")
                    showToast("网络已断开，正在等待恢复")
                    if (netWaitBusy.compareAndSet(false, true)) {
                        scope.launch {
                            try {
                                var waited = 0L
                                var lastHint = 0L
                                var aliveNow = withContext(Dispatchers.IO) { SystemProbe.online(context) }
                                // 一直等到网络恢复, 不超时撒手、也不切备用源; 预览离开(disposed)就退出。
                                while (!st.disposed && !aliveNow) {
                                    kotlinx.coroutines.delay(1000L)
                                    waited += 1000L
                                    aliveNow = withContext(Dispatchers.IO) { SystemProbe.online(context) }
                                    if (waited - lastHint >= 15_000L) {
                                        lastHint = waited
                                        showToast("网络已断开，正在等待恢复")
                                    }
                                }
                                if (!st.disposed && aliveNow) {
                                    DiagLog.i("播放器错误: 网络已恢复, 重新加载 ${source.id}")
                                    reloadInPlace(source)
                                }
                            } finally {
                                netWaitBusy.set(false)
                            }
                        }
                    }
                    return
                }
                val p = exo
                // 先快速检测是否B站下播(下播就不重试)
                scope.launch {
                    val offline = withContext(Dispatchers.IO) { checkIfOffline(source, context) }
                    // 是否有旧源在播(player 还不是当前 exo)
                    val hasOld = player != null && player !== exo
                    // 解码器初始化失败/运行时检查失败: 硬解起不来, 同一路流重试和看门狗重载都不会
                    // 恢复(日志实测反复 DECODER_INIT_FAILED, rendered=0/buf=0 无限循环)。直接按
                    // 耗尽处理: 回退旧源或换备用源, 不再在这个源上重试。
                    val fatalDecoder = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
                    if (offline || retryCount >= 3 || fatalDecoder) {
                        // 重试耗尽的这一路如果正是当前在播的那一路, 不要直接判"地址可能失效":
                        // 路由器把盒子拉黑这类网络黑洞不会让系统掉 VALIDATED 位, 走不到断网分支,
                        // 这里进转圈恢复, 由它等网络回来并重新取签名地址。
                        val playingThisOne = player === exo && !hasOld
                        if (!offline && !fatalDecoder && playingThisOne) {
                            ready = true
                            DiagLog.i("播放器错误: 当前源连续失败, 进入转圈恢复")
                            reloadInPlace(source)
                            return@launch
                        }
                        // 下播或重试耗尽: 标记ready=true取消15s超时协程, 避免超时再触发一次onFallback
                        ready = true
                        if (hasOld) {
                            // 切换源失败: 回退到旧源(本来就在播)
                            st.loading = false
                            playingSourceId?.let { onRevert(it) }
                            showToast(failureToast(offline = offline, hasOld = true, oldLabel = currentPlayingName, decodeFail = fatalDecoder))
                            onRevertFail()
                            onStatusRefresh()
                        } else {
                            // 当前源播不了: 切换到备用源
                            st.loading = false
                            DiagLog.i("播放器错误: 当前源失败, 切换到备用源")
                            val target = onFallback()
                            showToast(failureToast(offline = offline, hasOld = false, fallbackLabel = target, decodeFail = fatalDecoder))
                        }
                        suppressSwitchToast = false
                        return@launch
                    }
                    // 网络卡顿: 同实例重试, 循环换到下一路候选 CDN/编码(不消耗原地重载的候选池);
                    // 候选池为空时才用原流。
                    retryCount++
                    val attempt = retryCount
                    kotlinx.coroutines.delay(1500L * attempt)
                    val nextUrl = streamCache[source.url ?: ""]?.cycleNext()
                    val nextHls = nextUrl?.let { buildHlsMediaSource(it, source.isBilibiliStream, context) } ?: hls
                    runCatching {
                        p.setMediaSource(nextHls)
                        p.prepare()
                    }
                }
            }
        }
        exo.addListener(readyListener!!)
        exo.setMediaSource(hls)
        exo.prepare()
        // 所有情况都静音缓冲，READY且对齐后再绑定surface开播
        exo.playWhenReady = false

        // 等待新流READY且缓冲够(至少3秒)
        val waitStart = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            val deadline = System.currentTimeMillis() + loadTimeoutMs
            while (!ready && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(200)
            }
        }

        if (ready) {
            DiagLog.i(
                "直播预览: 就绪 id=${source.id} 耗时=${System.currentTimeMillis() - waitStart}ms " +
                    "state=${exo.playbackState}",
            )
            // 新流已对齐：释放旧player，绑定新player后开播
            val old = player
            val oldName = currentPlayingName
            // 先摘掉旧 listener, 否则 release() 会触发旧 listener 的 onPlayerError,
            // 旧闭包还拿着旧 source/retryCount/hls, 会误调 onFallback/onRevert
            playerListenerRef.value?.let { old?.removeListener(it) }
            playerListenerRef.value = readyListener!!
            // 旧 player 必须在绑新 surface 前释放: 这台 TCL 的硬解实例有限, 两个 player 同时
            // 存活(旧的只是 playWhenReady=false)会占住解码器, 新源直接 DECODER_INIT_FAILED /
            // FAILED_RUNTIME_CHECK, 一帧都出不来。曾试过"旧 player 留到新流首帧再放", 结果
            // 就是切源起不来。无缝画面要靠解码器自己保留最后一帧, 不能靠并存两个 player。
            old?.release()
            player = exo
            // 跳到直播边缘(缓冲末尾), 避免音视频从不同位置开始导致画面超前声音
            // 同样别贴死在缓冲末尾, 留 2 秒垫子, 否则起播瞬间就再饿一次
            exo.seekTo(
                (exo.bufferedPosition - RECOVER_SEEK_BACK_MS)
                    .coerceAtLeast(exo.currentPosition.coerceAtLeast(0L))
            )
            exo.playWhenReady = true
            onActuallyPlayingChange(true)
            currentPlayingName = source.name
            playingSourceId = source.id
            onSwitched(source.id)
            // 切换成功提示(有旧源才提示; 看门狗触发的切换不弹这个, 只留"卡顿严重"那条)
            if (oldName != null && oldName != source.name && !suppressSwitchToast) {
                showToast("已经从「$oldName」切换至「${source.name}」")
            }
            suppressSwitchToast = false
            // 卡顿探测: 关掉看门狗也照样跑 —— 关掉的是"卡了就自动换源", 不是"卡死了也不管"。
            // 关时只看真冻住(frozenOnly), 触发后走 handleStall 的救场流程(查开播/下播 → 重载或换源)。
            startWatchdog(exo, source.id, null)
        } else {
            // 新流超时未就绪
            DiagLog.i("直播预览: 新流未就绪")
            exo.removeListener(readyListener!!)
            exo.release()
            if (player != null) {
                // 切换源失败: 回退到旧源(本来就在播)
                st.loading = false
                val offline = withContext(Dispatchers.IO) { checkIfOffline(source, context) }
                playingSourceId?.let { onRevert(it) }
                showToast(failureToast(offline = offline, hasOld = true, oldLabel = currentPlayingName))
                onRevertFail()
            } else {
                // 开机首次加载超时：持续重试直到成功
                st.loading = true
                showToast("正在加载直播…")
                kotlinx.coroutines.delay(2000)
                restartSignal++
            }
            suppressSwitchToast = false
            return@LaunchedEffect
        }
        st.loading = false
        } finally {
            // 协程被取消(快速切源)时, 释放未被采用的 player
            if (player !== exo) {
                readyListener?.let { exo.removeListener(it) }
                exo.release()
            }
        }
    }

    DisposableEffect(Unit) {
        // HOME回弹(onPause后立刻onResume, 没走onStop)不暂停player: 延迟300ms暂停, onResume则取消
        var pauseJob: kotlinx.coroutines.Job? = null
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> {
                    st.paused = true
                    // 延迟暂停: 如果300ms内onResume来了(只是HOME回弹), 不暂停
                    pauseJob?.cancel()
                    pauseJob = scope.launch {
                        kotlinx.coroutines.delay(300)
                        player?.playWhenReady = false
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    // HOME回弹回来了: 取消延迟暂停, 恢复播放
                    pauseJob?.cancel()
                    st.paused = false
                    if (player?.playbackState == androidx.media3.common.Player.STATE_READY) {
                        player?.playWhenReady = true
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    // 真正进入其他app: 立即销毁
                    DiagLog.i("预览 ON_STOP id=${playingSourceId ?: source?.id}")
                    pauseJob?.cancel()
                    watchdogRef.value?.stop()
                    playerListenerRef.value?.let { player?.removeListener(it) }
                    player?.release()
                    player = null
                    st.loading = false
                    onActuallyPlayingChange(false)
                }
                Lifecycle.Event.ON_START -> {
                    // 从其他app回到桌面: 播放器已销毁, 重新加载
                    DiagLog.i("预览 ON_START id=${playingSourceId ?: source?.id}")
                    st.paused = false
                    st.loading = true
                    restartSignal++
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            DiagLog.i("预览销毁 dispose id=${playingSourceId ?: source?.id} player=${if (player != null) "已绑定" else "无"}")
            st.disposed = true
            // 预览销毁(切到非直播卡片/退出直播)时把加载图收掉, 别留在屏幕上
            onLoadingChange(false)
            onActuallyPlayingChange(false)
            lifecycleOwner.lifecycle.removeObserver(obs)
            watchdogRef.value?.stop()
            scope.cancel()
            val p = player; player = null
            playerListenerRef.value?.let { p?.removeListener(it) }
            playerListenerRef.value = null
            p?.release()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> TextureView(ctx).also {
                // 必须是 TextureView 而不是 SurfaceView: 切源时旧 player release 后,
                // TextureView 会把最后渲染的那一帧纹理留在原地, 新流首帧上来直接替换 ->
                // 视觉上无缝。SurfaceView 是独立视频层, 解码器一停这一层就被清空,
                // Amlogic(TCL) 上表现为黑屏(当贝的显示管线会保留最后一帧, 所以在当贝看不到)。
                it.isFocusable = false
                it.isFocusableInTouchMode = false
                surface = it
            }},
            onRelease = { surface = null },
        )
        // player 或 surface 变化时重新绑定
        LaunchedEffect(player, surface) {
            val p = player; val s = surface
            if (p != null && s != null) {
                p.setVideoTextureView(s)
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                p.playWhenReady = true
            }
        }
        // 加载图不再画在这里: 它要盖在壁纸/预览影片之上(见 HomeScreen 的 LiveLoadingOverlay)
        // 回退提示(和移动卡片提示同款)
        st.toastMessage?.let { msg ->
            // key 用 toastTick 而不是 msg：即使文案相同(连续两条相同提示)也重置 4s 倒计时
            LaunchedEffect(toastTick) {
                kotlinx.coroutines.delay(4000)
                st.toastMessage = null
            }
            BasicText(
                msg,
                style = androidx.compose.ui.text.TextStyle(
                    color = Color.White,
                    fontSize = 14.sp,
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .liquidGlass(20.dp)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

private suspend fun resolveCandidates(source: LiveSource, context: android.content.Context): List<String> = withContext(Dispatchers.IO) {
    try {
        when {
            source.url.endsWith(".m3u8") -> listOf(source.url)
            source.isBilibiliStream ->
                extractBilibiliRoomId(source.url)?.let { fetchBilibiliHlsCandidates(it, source.id) } ?: emptyList()
            else -> listOf(source.url)
        }
    } catch (e: Exception) { emptyList() }
}

private fun extractBilibiliRoomId(url: String): Long? = runCatching {
    extractRoomIdFromUrl(url)?.let { return@runCatching it }
    // 短链要跟跳转才拿得到房间号。B 站的短链域名不止 b23.tv(还有 bili23.cn / bili2233.cn),
    // 以前只认 b23.tv, 换成别的短链域名就解析不出房间号、取流直接失败。
    var loc: String? = url
    var hops = 0
    while (!loc.isNullOrBlank() && hops < 4) {
        val room = extractRoomIdFromUrl(loc!!)
        if (room != null) return@runCatching room
        val c = java.net.URL(loc).openConnection() as java.net.HttpURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 8000; c.readTimeout = 8000
        loc = c.getHeaderField("Location")
        c.disconnect()
        hops++
    }
    null
}.getOrNull()

private fun extractRoomIdFromUrl(url: String): Long? =
    Regex("live\\.bilibili\\.com/(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()

private fun bilibiliQn(sourceId: String): Int =
    app.orchard.tv.settings.AerialSettings.effectiveLiveQn(sourceId)

/** 偏好标记 → 接口请求 qn: 自动档不锁码率, 请求里按不指定档(qn=0)发。 */
private fun requestQn(sourceId: String): Int {
    val q = bilibiliQn(sourceId)
    return if (q == LiveQualityOptions.AUTO_QN) 0 else q
}

private fun fetchBilibiliHlsCandidates(roomId: Long, sourceId: String): List<String> = runCatching {
    val targetQn = bilibiliQn(sourceId)
    val bodyData = BiliLiveApi.playInfo(roomId, requestQn(sourceId)) ?: return@runCatching emptyList()
    // 检测下播：live_status != 1 表示未开播
    val liveStatus = BiliLiveApi.liveStatus(bodyData)
    if (liveStatus != 1) {
        DiagLog.i("B站房间 $roomId 未开播 (status=$liveStatus)")
        return@runCatching emptyList()
    }
    val streams = bodyData.getJSONObject("playurl_info").getJSONObject("playurl").getJSONArray("stream")
    data class StreamItem(val qn: Int, val url: String)
    val all = mutableListOf<StreamItem>()
    for (i in 0 until streams.length()) {
        val s = streams.getJSONObject(i); if (s.optString("protocol_name") != "http_hls") continue
        val fmts = s.getJSONArray("format")
        for (j in 0 until fmts.length()) {
            val f = fmts.getJSONObject(j); if (f.optString("format_name") != "ts") continue
            val codecs = f.getJSONArray("codec")
            for (k in 0 until codecs.length()) {
                val codec = codecs.getJSONObject(k)
                val uiArr = codec.getJSONArray("url_info")
                val baseUrl = codec.getString("base_url")
                val qn = codec.optInt("current_qn", 0)
                // 同一 codec 下的每个 CDN host 都是一条独立候选, 重载时先换备用节点
                for (u in 0 until uiArr.length()) {
                    val ui = uiArr.getJSONObject(u)
                    all.add(StreamItem(qn, ui.getString("host") + baseUrl + ui.getString("extra")))
                }
            }
        }
    }
    LiveQualityOptions.setOptions(sourceId, BiliLiveApi.acceptedQn(bodyData))
    if (all.isEmpty()) return@runCatching emptyList()
    // 选档规则与原实现一致: 自动档取默认序第一路; qn=0 取最高; 指定档取不超过上限的最高。
    val selected = when {
        targetQn == LiveQualityOptions.AUTO_QN -> all.firstOrNull()
        targetQn == 0 -> all.maxByOrNull { it.qn }
        else -> all.filter { it.qn <= targetQn }.maxByOrNull { it.qn } ?: all.maxByOrNull { it.qn }
    }
    val selectedQn = selected?.qn ?: -1
    // 候选顺序: 选中档的各 CDN host 在前(先换备用节点, 不降压画质), 其它档按 qn 降序在后兜底。
    val head = all.filter { it.qn == selectedQn }
    val tail = all.filter { it.qn != selectedQn }.sortedByDescending { it.qn }
    val ordered = head + tail
    LiveQualityOptions.setActiveQn(sourceId, ordered.first().qn)
    ordered.map { it.url }
}.getOrElse { emptyList() }

/** 检测B站直播间是否已下播 */
private fun checkIfOffline(source: LiveSource, context: android.content.Context): Boolean = runCatching {
    if (!source.isBilibiliStream) {
        return@runCatching false
    }
    val roomId = extractBilibiliRoomId(source.url) ?: return@runCatching false
    val data = BiliLiveApi.playInfo(roomId, requestQn(source.id))
    LiveQualityOptions.setOptions(source.id, BiliLiveApi.acceptedQn(data))
    DiagLog.i("开播检查 room=$roomId status=${BiliLiveApi.liveStatus(data)}")
    BiliLiveApi.liveStatus(data) != 1
}.getOrDefault(false)

/** 央视网快看(备用源)的固定 id: 本场下播时先换它。 */
private const val CCTV_ID = "preset_2"

/**
 * 一次取流解析出的有序候选。
 * - [pollNext] 向前取, 取完返回 null(供初始起播/原地重载, 用完才重拉接口);
 * - [cycleNext] 循环取, 永不返回 null(供播放器错误重试: 网络抖一下就想换节点试,
 *   但不能把留给原地重载的候选一次吃光 —— 实测断网时三次错误重试就把 4 路候选全打光,
 *   真正触发重载时只剩"第 4/4 路")。
 */
private class StreamCandidates(val urls: List<String>) {
    var index = 0
        private set
    private var cycleIndex = 0
    fun pollNext(): String? = if (index < urls.size) urls[index++] else null
    fun cycleNext(): String {
        val u = urls[cycleIndex % urls.size]
        cycleIndex++
        return u
    }
}

/** 共用的 HLS 媒体源构造: 初始起播和原地重载走同一套请求头/超时, 避免两处各写一份漂移。 */
private fun buildHlsMediaSource(url: String, isBili: Boolean, context: android.content.Context): HlsMediaSource {
    val factory = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        .setConnectTimeoutMs(10_000).setReadTimeoutMs(10_000)
    if (isBili || url.contains("bilibili.com")) {
        factory.setDefaultRequestProperties(mapOf(
            "Referer" to "https://live.bilibili.com/",
            "Cookie" to BiliLiveApi.cookieHeader()
        ))
    }
    return HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.parse(url)))
}

/** 30 秒窗口内救场超过 3 次(即第 4 次起), 看门狗才升级成真正的换源。 */
private const val STALL_ESCALATE_TIMES = 4

/** 上面的滑动窗口长度。 */
private const val STALL_WINDOW_MS = 30_000L

/** 一次"转圈"恢复最多持续多久: 到点才撤加载图并提示一次失败。 */
private const val RECOVER_MAX_MS = 60_000L

/** 换下一路地址之间的间隔, 别把 CDN 打成限流。 */
private const val RECOVER_GAP_MS = 2_000L

/** 就地恢复成功后往回对齐的安全垫: 提到缓冲最末端起播, 下一秒就会再饿一次。 */
private const val RECOVER_SEEK_BACK_MS = 2_000L

/** 原地重载救回来之后看门狗的预热: 流已在播, 只留一个采样窗口避开 seek 抖动。 */
private const val RELOAD_WARMUP_MS = 2_000L
