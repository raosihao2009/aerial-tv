package app.orchard.tv.ui.home

import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun ChannelFocusPreview(
    source: LiveSource?,
    modifier: Modifier = Modifier,
    onFallback: () -> String? = { null },
    onRevert: (String) -> String? = { null },
    onReadyChange: (Boolean) -> Unit = {},
    onStatusRefresh: () -> Unit = {},
    onRevertFail: () -> Unit = {},
    onSwitched: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var surface by remember { mutableStateOf<TextureView?>(null) }

    var restartSignal by remember { mutableStateOf(0) }
    // 备用源本身也卡时, 不是再切一次(切来切去同一个), 而是把当前源重新加载一遍
    var reloadTick by remember { mutableStateOf(0) }
    // 看门狗自动切走时, 不弹"已经从X切换至Y", 只留"卡顿严重"那条
    var suppressSwitchToast by remember { mutableStateOf(false) }
    val streamCache = remember { mutableMapOf<String, String?>() }
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    var retryCount by remember { mutableStateOf(0) }
    // 加载遮罩：切换直播源时渐入，新流就绪或失败后渐出
    var loading by remember { mutableStateOf(false) }
    // 回退提示
    var toastMessage by remember { mutableStateOf<String?>(null) }
    // 软件音量回调的注销 token, 跨协程持有以便 onDispose 释放
    // 当前已在播放的源名称(用于切换失败时提示继续观看谁)
    var currentPlayingName by remember { mutableStateOf<String?>(null) }
    // 上一个正在播放的源 id(切换失败时回退焦点)
    var playingSourceId by remember { mutableStateOf<String?>(null) }
    // 加载遮罩：直接显示/隐藏，不用alpha渐变动画(低端GPU fill rate优化)
    val loadingAlpha = if (loading) 1f else 0f

    LaunchedEffect(source?.url, restartSignal, reloadTick) {
        if (source == null) {
            player?.release(); player = null
            return@LaunchedEffect
        }
        // 切回当前正在播放的源(onRevert): 不重新加载, 继续用现有player, 避免画面声音回溯
        // 但 reloadTick > 0 表示看门狗要求强行重载, 要放过这个早退
        if (reloadTick == 0 && source.id == playingSourceId && player != null) {
            return@LaunchedEffect
        }

        // 加载遮罩：始终显示加载图, 直到新流就绪
        loading = true

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
            loading = false
            if (player != null) {
                // 切换源失败：看旧源是否还在直播
                val target = playingSourceId?.let { onRevert(it) }
                toastMessage = if (target != null) {
                    "「${source.name}」暂时无法观看，已切换至「$target」"
                } else {
                    "「${source.name}」暂时无法观看，请继续观看「${currentPlayingName ?: ""}」"
                }
                if (target == null) onRevertFail()
            } else {
                // 首次加载失败: 切换到备用源
                DiagLog.i("channel preview: first load failed, switching to fallback")
                val target = onFallback()
                toastMessage = "「${source?.name ?: ""}」暂时无法观看，已切换至「${target ?: "备用源"}」"
            }
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
            .setTrackSelector(trackSelector)
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(5000, 15000, 2000, 3000)
                    .build()
            )
            .build()
        try {
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(10_000)
        if (source.isBilibili || streamUrl.contains("bilibili.com")) {
            val buvid3 = fetchBuvid3()
            factory.setDefaultRequestProperties(mapOf(
                "Referer" to "https://live.bilibili.com/",
                "Cookie" to "buvid3=$buvid3"
            ))
        }
        val hls = HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)))

        // 等待新流READY且缓冲足够(至少3秒)再切换，避免开播后卡缓冲
        var ready = false
        // 15秒超时, 避免永远卡住
        scope.launch {
            kotlinx.coroutines.delay(15_000)
            if (!ready) {
                DiagLog.i("channel preview: load timeout 15s, switching to fallback")
                loading = false
                val target = onFallback()
                toastMessage = "「${source?.name ?: ""}」暂时无法观看，已切换至「${target ?: "备用源"}」"
            }
        }
        val readyListener = object : Player.Listener {
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
                DiagLog.i("player err: ${error.errorCodeName}, retry $retryCount")
                val p = exo
                // 先快速检测是否B站下播(下播就不重试)
                scope.launch {
                    val offline = withContext(Dispatchers.IO) { checkIfOffline(source, context) }
                    // 是否有旧源在播(player 还不是当前 exo)
                    val hasOld = player != null && player !== exo
                    if (offline || retryCount >= 3) {
                        // 下播或重试耗尽
                        if (hasOld) {
                            // 切换源失败: 看旧源是否还在直播
                            loading = false
                            val target = playingSourceId?.let { onRevert(it) }
                            toastMessage = if (target != null) {
                                "「${source.name}」暂时无法观看，已切换至「$target」"
                            } else {
                                "「${source.name}」暂时无法观看，请继续观看「${currentPlayingName ?: ""}」"
                            }
                            if (target == null) onRevertFail()
                            onStatusRefresh()
                        } else {
                            // 当前源下播: 切换到备用源
                            loading = false
                            DiagLog.i("player err: current source offline, switching to fallback")
                            val target = onFallback()
                            toastMessage = "「${source.name}」暂时无法观看，已切换至「${target ?: "备用源"}」"
                        }
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
        exo.addListener(readyListener)
        exo.setMediaSource(hls)
        exo.prepare()
        // 所有情况都静音缓冲，READY且对齐后再绑定surface开播
        exo.playWhenReady = false

        // 等待新流READY且缓冲够(至少3秒)
        withContext(Dispatchers.Main) {
            val deadline = System.currentTimeMillis() + 15_000
            while (!ready && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(200)
            }
        }

        if (ready) {
            // 新流已对齐：释放旧player，绑定新player后开播
            val old = player
            val oldName = currentPlayingName
            old?.release()
            player = exo
            // 跳到直播边缘(缓冲末尾), 避免音视频从不同位置开始导致画面超前声音
            exo.seekTo(exo.bufferedPosition.coerceAtLeast(exo.currentPosition))
            exo.playWhenReady = true
            currentPlayingName = source.name
            playingSourceId = source.id
            onSwitched(source.id)
            // 切换成功提示(有旧源才提示; 看门狗触发的切换不弹这个, 只留"卡顿严重"那条)
            if (oldName != null && oldName != source.name && !suppressSwitchToast) {
                toastMessage = "已经从「$oldName」切换至「${source.name}」"
            }
            suppressSwitchToast = false
            // 卡顿看门狗: 开播后持续监测, 连续掉帧爆发或画面冻住就自动切备用源
            scope.launch {
                var lastDropped = 0L
                var lastPos = -1L
                var stuckStreak = 0
                var age = 0
                val watchId = source.id
                while (true) {
                    kotlinx.coroutines.delay(2000)
                    if (playingSourceId != watchId) break
                    age += 2000
                    if (age < 12000) continue  // 头 12 秒是正常起播缓冲, 不判
                    val dc: Long = try {
                        val c = exo.videoDecoderCounters
                        c?.ensureUpdated()
                        (c?.droppedBufferCount ?: 0L) as Long
                    } catch (t: Throwable) { 0L }
                    val droppedDelta = dc - lastDropped
                    lastDropped = dc
                    val pos = exo.currentPosition
                    val posDelta: Long = if (lastPos < 0) 1000L else pos - lastPos
                    lastPos = pos
                    // 真冻住: 2 秒内 position 走不到 100ms; 且掉帧 >60(=30fps 全丢) 才算 PPT
                    val frozen = !exo.isPlaying || posDelta < 100L
                    val dropBurst = droppedDelta > 60L
                    val bad = frozen && dropBurst
                    stuckStreak = if (bad) stuckStreak + 1 else 0
                    if (stuckStreak >= 3) {
                        DiagLog.i("channel preview: source $watchId jank (frozen=$frozen dropped=$droppedDelta), handle")
                        if (watchId == "preset_2") {
                            // 已经在备用源上了: 再切还是同一个, 直接重新加载当前流
                            toastMessage = "「央视网快看」也卡了，正在重新加载…"
                            reloadTick++
                        } else {
                            suppressSwitchToast = true
                            val target = onFallback()
                            toastMessage = "「${source.name ?: ""}」卡顿严重，已切换至「${target ?: "备用源"}」"
                        }
                        break
                    }
                }
            }
        } else {
            // 新流超时未就绪
            DiagLog.i("channel preview: new stream not ready")
            exo.release()
            if (player != null) {
                // 切换源失败：看旧源是否还在直播
                loading = false
                val target = playingSourceId?.let { onRevert(it) }
                toastMessage = if (target != null) {
                    "「${source.name}」暂时无法观看，已切换至「$target」"
                } else {
                    "「${source.name}」暂时无法观看，请继续观看「${currentPlayingName ?: ""}」"
                }
                if (target == null) onRevertFail()
            } else {
                // 开机首次加载超时：持续重试直到成功
                loading = true
                toastMessage = "正在加载直播…"
                kotlinx.coroutines.delay(2000)
                restartSignal++
            }
            return@LaunchedEffect
        }
        loading = false
        } finally {
            // 协程被取消(快速切源)时, 释放未被采用的 player
            if (player !== exo) exo.release()
        }
    }

    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> {
                    // 进入其他app时直接销毁播放器, 回到桌面时重新加载(和开机一样)
                    player?.release()
                    player = null
                    loading = false
                }
                Lifecycle.Event.ON_START -> {
                    // 回到桌面: 播放器已销毁, 重新加载当前源
                    loading = true
                    restartSignal++
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            scope.cancel()
            val p = player; player = null
            p?.release()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> TextureView(ctx).also {
                it.isFocusable = false; it.isFocusableInTouchMode = false
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
        // 加载遮罩：整图铺满(图片本身已含右上角黑底文字布局)
        if (loadingAlpha > 0.01f) {
            Image(
                painter = painterResource(R.drawable.live_loading),
                contentDescription = "直播加载中",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                alpha = loadingAlpha,
            )
        }
        // 回退提示(和移动卡片提示同款)
        toastMessage?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(4000)
                toastMessage = null
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
            source.isBilibili || source.url.contains("b23.tv") || source.url.contains("live.bilibili.com") ->
                extractBilibiliRoomId(source.url)?.let { fetchBilibiliHlsUrl(it, context) }
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

private fun bilibiliQn(): Int =
    if (app.orchard.tv.settings.AerialSettings.liveQuality == app.orchard.tv.settings.AerialSettings.LiveQuality.Original) 0 else 800

private fun fetchBilibiliHlsUrl(roomId: Long, context: android.content.Context): String? = runCatching {
    val buvid3 = fetchBuvid3()
    val api = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=$roomId&protocol=0,1&format=0,1&codec=0,1&qn=${bilibiliQn()}&platform=web"
    val c = java.net.URL(api).openConnection() as java.net.HttpURLConnection
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
    c.setRequestProperty("Referer", "https://live.bilibili.com/")
    c.setRequestProperty("Cookie", "buvid3=$buvid3")
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
    for (i in 0 until streams.length()) {
        val s = streams.getJSONObject(i); if (s.optString("protocol_name") != "http_hls") continue
        val fmts = s.getJSONArray("format")
        for (j in 0 until fmts.length()) {
            val f = fmts.getJSONObject(j); if (f.optString("format_name") != "ts") continue
            val codecs = f.getJSONArray("codec")
            for (k in 0 until codecs.length()) {
                val codec = codecs.getJSONObject(k)
                val ui = codec.getJSONArray("url_info").getJSONObject(0)
                val url = ui.getString("host") + codec.getString("base_url") + ui.getString("extra")
                val qn = codec.optInt("current_qn", 0)
                candidates.add(StreamItem(qn, url))
            }
        }
    }
    val maxQn = bilibiliQn()
    // 原画模式选所有流中最高画质; 1080p模式选不超过800的最高画质
    val filtered = if (maxQn == 0) candidates else candidates.filter { it.qn <= maxQn }
    (filtered.ifEmpty { candidates }).maxByOrNull { it.qn }?.url
}.getOrNull()

/** 检测B站直播间是否已下播 */
private fun checkIfOffline(source: LiveSource, context: android.content.Context): Boolean = runCatching {
    if (!source.isBilibili && !source.url.contains("b23.tv") && !source.url.contains("live.bilibili.com")) {
        return@runCatching false
    }
    val roomId = extractBilibiliRoomId(source.url) ?: return@runCatching false
    val buvid3 = fetchBuvid3()
    val api = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=$roomId&protocol=0,1&format=0,1,2&codec=0,1&qn=${bilibiliQn()}&platform=web"
    val c = java.net.URL(api).openConnection() as java.net.HttpURLConnection
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
    c.setRequestProperty("Referer", "https://live.bilibili.com/")
    c.setRequestProperty("Cookie", "buvid3=$buvid3")
    c.connectTimeout = 5000; c.readTimeout = 5000
    val body = c.inputStream.bufferedReader().readText(); c.disconnect()
    val status = JSONObject(body).optJSONObject("data")?.optInt("live_status", 1) ?: 1
    DiagLog.i("checkIfOffline room=$roomId status=$status")
    status != 1
}.getOrDefault(false)
