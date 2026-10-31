package app.orchard.tv.ui.home

import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.viewinterop.AndroidView
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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var surface by remember { mutableStateOf<TextureView?>(null) }

    var restartSignal by remember { mutableStateOf(0) }
    val streamCache = remember { mutableMapOf<String, String?>() }
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    var retryCount by remember { mutableStateOf(0) }
    // 加载遮罩：切换直播源时渐入，新流就绪或失败后渐出
    var loading by remember { mutableStateOf(false) }
    val loadingAlpha by animateFloatAsState(
        targetValue = if (loading) 1f else 0f,
        animationSpec = tween(if (loading) 300 else 500),
        label = "loadingAlpha",
    )

    LaunchedEffect(source?.url, restartSignal) {
        if (source == null) {
            player?.release(); player = null
            return@LaunchedEffect
        }

        // 第一次加载(无旧 player)：直接准备，不显示遮罩
        val firstLoad = player == null
        if (!firstLoad) {
            loading = true
        }

        var streamUrl: String? = streamCache[source.url ?: ""]
        if (streamUrl == null) {
            // 解析新流地址，最多重试 10 次
            for (attempt in 1..10) {
                streamUrl = withContext(Dispatchers.IO) { resolveStreamUrl(source) }
                if (streamUrl != null) break
                kotlinx.coroutines.delay(3000)
            }
            streamCache[source.url ?: ""] = streamUrl
        }
        DiagLog.i("channel preview: url = $streamUrl, first=$firstLoad")

        if (streamUrl == null) {
            // 解析失败：保持旧流，渐出遮罩
            DiagLog.i("channel preview: stream resolve failed, keep old")
            loading = false
            return@LaunchedEffect
        }

        // 准备新流
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                    .setForceHighestSupportedBitrate(true)
            )
        }
        val exo = ExoPlayer.Builder(context).setTrackSelector(trackSelector).build()
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

        // 等待新流 READY
        var ready = false
        val readyListener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    ready = true
                    retryCount = 0
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                DiagLog.i("player err: ${error.errorCodeName}, retry $retryCount")
                val p = exo
                if (retryCount < 3) {
                    retryCount++
                    val attempt = retryCount
                    scope.launch {
                        kotlinx.coroutines.delay(1500L * attempt)
                        runCatching {
                            p.setMediaSource(hls)
                            p.prepare()
                            p.playWhenReady = true
                        }
                    }
                }
            }
        }
        exo.addListener(readyListener)
        exo.setMediaSource(hls)
        exo.prepare()
        exo.playWhenReady = true
        exo.seekToDefaultPosition()

        // 等待最多 15 秒让新流就绪
        withContext(Dispatchers.Main) {
            val deadline = System.currentTimeMillis() + 15_000
            while (!ready && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(200)
            }
        }

        if (ready) {
            // 新流就绪：释放旧 player，切换到新 player（LaunchedEffect 会自动绑定 surface）
            val old = player
            player = exo
            old?.release()
        } else {
            // 新流超时未就绪：放弃新 player，保持旧流，渐出遮罩
            DiagLog.i("channel preview: new stream not ready, keep old")
            exo.release()
        }
        loading = false
    }

    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> {
                    player?.pause()
                    player?.volume = 0f
                }
                Lifecycle.Event.ON_START -> {
                    player?.let {
                        it.volume = 1f
                        surface?.let { s -> it.setVideoTextureView(s) }
                        if (it.playbackState == Player.STATE_IDLE) {
                            it.prepare()
                        }
                        it.playWhenReady = true
                        if (it.isCurrentMediaItemLive) {
                            it.seekToDefaultPosition()
                        }
                    } ?: run { restartSignal++ }
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
        // 加载遮罩：16:9 图片居中
        if (loadingAlpha > 0.01f) {
            Image(
                painter = painterResource(R.drawable.live_loading),
                contentDescription = "直播加载中",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                alpha = loadingAlpha,
            )
        }
    }
}

private suspend fun resolveStreamUrl(source: LiveSource): String? = withContext(Dispatchers.IO) {
    try {
        when {
            source.url.endsWith(".m3u8") -> source.url
            source.isBilibili || source.url.contains("b23.tv") || source.url.contains("live.bilibili.com") ->
                extractBilibiliRoomId(source.url)?.let { fetchBilibiliHlsUrl(it) }
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

private fun fetchBilibiliHlsUrl(roomId: Long): String? = runCatching {
    val buvid3 = fetchBuvid3()
    val api = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=$roomId&protocol=0,1&format=0,1,2&codec=0,1&qn=10000&platform=web"
    val c = java.net.URL(api).openConnection() as java.net.HttpURLConnection
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
    c.setRequestProperty("Referer", "https://live.bilibili.com/")
    c.setRequestProperty("Cookie", "buvid3=$buvid3")
    c.connectTimeout = 10000; c.readTimeout = 10000
    val body = c.inputStream.bufferedReader().readText(); c.disconnect()
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
    candidates.maxByOrNull { it.qn }?.url
}.getOrNull()
