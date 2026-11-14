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
import app.orchard.tv.apps.BiliAccount
import app.orchard.tv.apps.LiveQualityOptions
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.SystemProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject

@Composable
fun ChannelFocusPreview(
    source: LiveSource?,
    modifier: Modifier = Modifier,
    /** 冷启动首屏是否已稳定: 首起播等它放开再建播放器, 避免和壁纸影片同时冷启动抢解码器。 */
    gateOpen: Boolean = true,
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
    // 备用源本身也卡时, 不是再切一次(切来切去同一个), 而是把当前源重新加载一遍
    var reloadTick by remember { mutableStateOf(0) }
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
    val streamCache = remember { mutableMapOf<String, String?>() }
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    var retryCount by remember { mutableStateOf(0) }
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
        val netOffline = SystemProbe.network(context).kind == NetKind.Offline
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

    fun handleStall(id: String) {
        if (!stallBusy.compareAndSet(false, true)) return
        val stalled = sourcesNow().firstOrNull { it.id == id } ?: source
        if (stalled == null) {
            stallBusy.set(false)
            return
        }
        scope.launch {
            try {
                DiagLog.i("channel preview: ${stalled.id} stalled, checking whether it is still live")
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
                        DiagLog.i("channel preview: ${stalled.id} offline, switched to $CCTV_ID")
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
                            DiagLog.i("channel preview: $CCTV_ID offline, switched to ${next.id}")
                        } else {
                            // 焦点一移出直播卡, 预览就 dispose, 挂在预览里的提示会立刻消失:
                            // 这条必须走全局胶囊。
                            onMoveOffLiveCard()
                            onGlobalToast("所有直播卡片均未开播，请欣赏桌面壁纸")
                            DiagLog.i("channel preview: no live source left, focus moved off live cards")
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
                            DiagLog.i("channel preview: ${stalled.id} stalled $times times in 30 s, watchdog switched to $target")
                        } else {
                            reloadTick++
                            // 30 秒内反复卡住时, 第 2、3 次不再弹同一条: 加载图已经在说"正在重载",
                            // 同一条提示连刷只会盖住画面; 第 4 次升级成第 9 条"卡顿严重"。
                            if (times <= 1) showToast("正在尝试重新加载「${stalled.name}」")
                            DiagLog.i("channel preview: ${stalled.id} still live, reloading ($times in 30 s)")
                        }
                    }
                }
            } catch (t: Throwable) {
                DiagLog.i("channel preview: stall handling failed: ${t.message}")
            } finally {
                stallBusy.set(false)
            }
        }
    }

    LaunchedEffect(source?.url, restartSignal, reloadTick, externalReload) {
        if (source == null) {
            player?.release(); player = null
            return@LaunchedEffect
        }
        // 切回当前正在播放的源(onRevert): 不重新加载, 继续用现有player, 避免画面声音回溯
        // 但 reloadTick > 0 表示看门狗要求强行重载, 要放过这个早退; externalReload > 0 表示
        // 画质等偏好刚改过, 同样要重新取流。
        if (reloadTick == 0 && externalReload == 0 && source.id == playingSourceId && player != null) {
            return@LaunchedEffect
        }

        // 冷启动: 直播预览也是个硬件解码器, 别和开屏动画同时起播(实测首列直播预览+视频壁纸
        // 时过渡一卡一卡; 把预览挪到第≥2列就顺, 就是因为起播时机错开了)。等揭开后再走下面。
        while (!BootVeil.revealed) kotlinx.coroutines.delay(100)

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
        if (firstRun && !gateOpen) {
            androidx.compose.runtime.snapshotFlow { gateOpen }.first { it }
        }

        if (externalReload != lastReload) {
            streamCache.clear()
            lastReload = externalReload
        }
        var streamUrl: String? = streamCache[source.url ?: ""]
        if (streamUrl == null) {
            // 解析新流地址，最多重试 5 次(网络刚恢复/待机回来可能需要等)
            for (attempt in 1..5) {
                streamUrl = withContext(Dispatchers.IO) { resolveStreamUrl(source, context) }
                if (streamUrl != null) break
                kotlinx.coroutines.delay(2000)
            }
            // 只缓存成功的 URL, 失败不缓存(避免下次直接读 null)
            if (streamUrl != null) {
                streamCache[source.url ?: ""] = streamUrl
            }
        }
        DiagLog.i("channel preview: url = $streamUrl")

        if (streamUrl == null) {
            // 解析失败(可能已下播或网络未就绪)
            DiagLog.i("channel preview: stream resolve failed")
            st.loading = false
            val offline = withContext(Dispatchers.IO) { checkIfOffline(source, context) }
            if (player != null) {
                // 切换源失败：回退到旧源(本来就在播)
                playingSourceId?.let { onRevert(it) }
                showToast(failureToast(offline = offline, hasOld = true, oldLabel = currentPlayingName))
                onRevertFail()
            } else {
                // 首次加载失败: 切换到备用源
                DiagLog.i("channel preview: first load failed, switching to fallback")
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
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(10_000)
        if (source.isBilibiliStream || streamUrl.contains("bilibili.com")) {
            val buvid3 = fetchBuvid3()
            factory.setDefaultRequestProperties(mapOf(
                "Referer" to "https://live.bilibili.com/",
                "Cookie" to biliCookieHeader(buvid3)
            ))
        }
        val hls = HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)))

        // 等待新流READY且缓冲足够(至少3秒)再切换，避免开播后卡缓冲
        var ready = false
        // 超时兜底, 避免永远卡住; 窗口按首起播/切换分开(见 loadTimeoutMs)
        scope.launch {
            kotlinx.coroutines.delay(loadTimeoutMs)
            if (st.disposed) return@launch
            if (!ready) {
                DiagLog.i("channel preview: load timeout ${loadTimeoutMs / 1000}s (${if (firstRun) "first run" else "switch"}), switching to fallback")
                st.loading = false
                val offline = withContext(Dispatchers.IO) { checkIfOffline(source, context) }
                val target = onFallback()
                showToast(failureToast(offline = offline, hasOld = player != null, fallbackLabel = target, oldLabel = currentPlayingName))
                suppressSwitchToast = false
            }
        }
        readyListener = object : Player.Listener {
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
                DiagLog.i("player err: ${error.errorCodeName}, retry $retryCount")
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
                            DiagLog.i("player err: current source failed, switching to fallback")
                            val target = onFallback()
                            showToast(failureToast(offline = offline, hasOld = false, fallbackLabel = target, decodeFail = fatalDecoder))
                        }
                        suppressSwitchToast = false
                        return@launch
                    }
                    // 网络卡顿: 重试
                    retryCount++
                    val attempt = retryCount
                    kotlinx.coroutines.delay(1500L * attempt)
                    runCatching {
                        p.setMediaSource(hls)
                        p.prepare()
                        p.playWhenReady = false
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
        withContext(Dispatchers.Main) {
            val deadline = System.currentTimeMillis() + loadTimeoutMs
            while (!ready && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(200)
            }
        }

        if (ready) {
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
            exo.seekTo(exo.bufferedPosition.coerceAtLeast(exo.currentPosition))
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
            val sens = AerialSettings.jankSensitivity
            val warmupMs: Long; val dropThreshold: Long; val renderedThreshold: Long; val streakNeeded: Int
            when (sens) {
                AerialSettings.JankSensitivity.Low -> { warmupMs = 12000L; dropThreshold = 80L; renderedThreshold = 12L; streakNeeded = 3 }
                AerialSettings.JankSensitivity.High -> { warmupMs = 4000L; dropThreshold = 20L; renderedThreshold = 28L; streakNeeded = 2 }
                else -> { warmupMs = 8000L; dropThreshold = 40L; renderedThreshold = 20L; streakNeeded = 2 }  // Medium / Off
            }
            // 停掉旧看门狗(换源/重载), 再为新流起一个
            watchdogRef.value?.stop()
            val wd = PlaybackWatchdog(
                exo = exo,
                sourceId = source.id,
                warmupMs = warmupMs,
                dropThreshold = dropThreshold,
                renderedThreshold = renderedThreshold,
                streakNeeded = streakNeeded,
                isActive = {
                    !st.disposed && !st.paused && !st.loading && playingSourceId == source.id
                },
                frozenOnly = sens == AerialSettings.JankSensitivity.Off,
                onJank = { id -> handleStall(id) },
            )
            wd.start(scope)
            watchdogRef.value = wd
        } else {
            // 新流超时未就绪
            DiagLog.i("channel preview: new stream not ready")
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
                    st.paused = false
                    st.loading = true
                    restartSignal++
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
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

private suspend fun resolveStreamUrl(source: LiveSource, context: android.content.Context): String? = withContext(Dispatchers.IO) {
    try {
        when {
            source.url.endsWith(".m3u8") -> source.url
            source.isBilibiliStream ->
                extractBilibiliRoomId(source.url)?.let { fetchBilibiliHlsUrl(it, source.id, context) }
            else -> source.url
        }
    } catch (e: Exception) { null }
}

private val buvidCache = mutableMapOf<String, String?>()

private fun fetchBuvid3(): String {
    buvidCache["buvid3"]?.let { return it }
    return runCatching {
        val c = java.net.URL("https://api.bilibili.com/x/frontend/finger/spi").openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
        c.connectTimeout = 8000; c.readTimeout = 8000
        val body = c.inputStream.bufferedReader().readText(); c.disconnect()
        val b3 = JSONObject(body).getJSONObject("data").getString("b_3")
        buvidCache["buvid3"] = b3
        b3
    }.getOrDefault("buvid3_placeholder")
}

/** 直播请求 Cookie: 匿名 buvid3 在前, 已登录再追加 B 站登录态。 */
private fun biliCookieHeader(buvid3: String): String =
    listOfNotNull("buvid3=$buvid3", BiliAccount.cookie).joinToString("; ")

private fun extractBilibiliRoomId(url: String): Long? = runCatching {
    if (url.contains("b23.tv")) {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 8000; c.readTimeout = 8000
        val loc = c.getHeaderField("Location"); c.disconnect()
        loc?.let { extractRoomIdFromUrl(it) }
    } else extractRoomIdFromUrl(url)
}.getOrNull()

private fun extractRoomIdFromUrl(url: String): Long? =
    Regex("live\\.bilibili\\.com/(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()

private fun bilibiliQn(sourceId: String): Int =
    app.orchard.tv.settings.AerialSettings.effectiveLiveQn(sourceId)

private fun fetchBilibiliHlsUrl(roomId: Long, sourceId: String, context: android.content.Context): String? = runCatching {
    val buvid3 = fetchBuvid3()
    val targetQn = bilibiliQn(sourceId)
    val api = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=$roomId&protocol=0,1&format=0,1&codec=0,1&qn=$targetQn&platform=web"
    val c = java.net.URL(api).openConnection() as java.net.HttpURLConnection
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
    c.setRequestProperty("Referer", "https://live.bilibili.com/")
    c.setRequestProperty("Cookie", biliCookieHeader(buvid3))
    c.connectTimeout = 10000; c.readTimeout = 10000
    val body = c.inputStream.bufferedReader().readText(); c.disconnect()
    // 检测下播：live_status != 1 表示未开播
    val liveStatus = JSONObject(body).optJSONObject("data")?.optInt("live_status", 1) ?: 1
    if (liveStatus != 1) {
        DiagLog.i("bilibili room $roomId offline (status=$liveStatus)")
        return@runCatching null
    }
    val streams = JSONObject(body).getJSONObject("data").getJSONObject("playurl_info").getJSONObject("playurl").getJSONArray("stream")
    data class StreamItem(val qn: Int, val url: String)
    val candidates = mutableListOf<StreamItem>()
    val accepted = mutableSetOf<Int>()
    for (i in 0 until streams.length()) {
        val s = streams.getJSONObject(i); if (s.optString("protocol_name") != "http_hls") continue
        val fmts = s.getJSONArray("format")
        for (j in 0 until fmts.length()) {
            val f = fmts.getJSONObject(j); if (f.optString("format_name") != "ts") continue
            val codecs = f.getJSONArray("codec")
            for (k in 0 until codecs.length()) {
                val codec = codecs.getJSONObject(k)
                codec.optJSONArray("accept_qn")?.let { arr ->
                    for (a in 0 until arr.length()) accepted.add(arr.optInt(a))
                }
                val ui = codec.getJSONArray("url_info").getJSONObject(0)
                val url = ui.getString("host") + codec.getString("base_url") + ui.getString("extra")
                val qn = codec.optInt("current_qn", 0)
                candidates.add(StreamItem(qn, url))
            }
        }
    }
    LiveQualityOptions.setOptions(sourceId, accepted.sortedDescending())
    // 原画模式选所有流中最高画质; 指定档选不超过目标 qn 的最高画质
    val filtered = if (targetQn == 0) candidates else candidates.filter { it.qn <= targetQn }
    (filtered.ifEmpty { candidates }).maxByOrNull { it.qn }?.url
}.getOrNull()

/** 检测B站直播间是否已下播 */
private fun checkIfOffline(source: LiveSource, context: android.content.Context): Boolean = runCatching {
    if (!source.isBilibiliStream) {
        return@runCatching false
    }
    val roomId = extractBilibiliRoomId(source.url) ?: return@runCatching false
    val buvid3 = fetchBuvid3()
    val api = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=$roomId&protocol=0,1&format=0,1,2&codec=0,1&qn=${bilibiliQn(source.id)}&platform=web"
    val c = java.net.URL(api).openConnection() as java.net.HttpURLConnection
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
    c.setRequestProperty("Referer", "https://live.bilibili.com/")
    c.setRequestProperty("Cookie", biliCookieHeader(buvid3))
    c.connectTimeout = 5000; c.readTimeout = 5000
    val body = c.inputStream.bufferedReader().readText(); c.disconnect()
    val status = JSONObject(body).optJSONObject("data")?.optInt("live_status", 1) ?: 1
    DiagLog.i("checkIfOffline room=$roomId status=$status")
    status != 1
}.getOrDefault(false)

/** 央视网快看(备用源)的固定 id: 本场下播时先换它。 */
private const val CCTV_ID = "preset_2"

/** 30 秒窗口内救场超过 3 次(即第 4 次起), 看门狗才升级成真正的换源。 */
private const val STALL_ESCALATE_TIMES = 4

/** 上面的滑动窗口长度。 */
private const val STALL_WINDOW_MS = 30_000L
