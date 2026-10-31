package app.orchard.tv.ui.home

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.Dispatchers
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
    // 记录最近一次用的 mediaItem，用于错误重试
    var lastMediaSource by remember { mutableStateOf<androidx.media3.exoplayer.hls.HlsMediaSource?>(null) }
    var retryCount by remember { mutableStateOf(0) }
    LaunchedEffect(source?.url, restartSignal) {
        player?.release()
        player = null
        lastMediaSource = null
        retryCount = 0
        if (source == null) return@LaunchedEffect

        var streamUrl: String? = streamCache[source.url ?: ""]
        if (streamUrl == null) {
            for (attempt in 1..10) {
                streamUrl = withContext(Dispatchers.IO) { resolveStreamUrl(source) }
                if (streamUrl != null) break
                kotlinx.coroutines.delay(3000)
            }
            streamCache[source.url ?: ""] = streamUrl
        }
        DiagLog.i("channel preview: url = $streamUrl")
        if (streamUrl != null) {
            val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
                setParameters(buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE))
            }
            val exo = ExoPlayer.Builder(context).setTrackSelector(trackSelector).build()
            player = exo
            val factory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .setConnectTimeoutMs(10_000).setReadTimeoutMs(10_000)
            if (source.isBilibili || streamUrl.contains("bilibili.com"))
                factory.setDefaultRequestProperties(mapOf(
                    "Referer" to "https://live.bilibili.com/",
                    "Cookie" to "buvid3=placeholder"
                ))
            val hls = HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl)))
            lastMediaSource = hls
            exo.setMediaSource(hls)
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    DiagLog.i("player err: ${error.errorCodeName}, retry $retryCount")
                    // 自动重试：断开/缓冲失败/网络抖动时重新 prepare，最多 3 次
                    val p = player ?: return
                    val src = lastMediaSource ?: return
                    if (retryCount < 3) {
                        retryCount++
                        val attempt = retryCount
                        scope.launch {
                            kotlinx.coroutines.delay(1500L * attempt)
                            runCatching {
                                p.setMediaSource(src)
                                p.prepare()
                                p.playWhenReady = true
                            }
                        }
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) retryCount = 0
                }
            })
            surface?.let { exo.setVideoTextureView(it) }
            exo.prepare()
            exo.playWhenReady = true
            exo.seekToDefaultPosition()
        }
    }

    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> {
                    // 离开桌面(进 IPTV app)：暂停但保留 player，不释放
                    player?.pause()
                    player?.volume = 0f
                }
                Lifecycle.Event.ON_START -> {
                    // 回到桌面：恢复播放；直播流直接跳到直播最新位置，不从头缓冲
                    player?.let {
                        it.volume = 1f
                        surface?.let { s -> it.setVideoTextureView(s) }
                        // 只有在 prepared 完成后才直接 resume；未 prepared 过才 prepare
                        if (it.playbackState == Player.STATE_IDLE) {
                            it.prepare()
                        }
                        it.playWhenReady = true
                        // 直播流追最新：如果停在很久之前的位置，跳到 live edge
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

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx -> TextureView(ctx).also {
            it.isFocusable = false; it.isFocusableInTouchMode = false
            surface = it; player?.setVideoTextureView(it)
        }},
        onRelease = { surface = null },
    )
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
    val api = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=$roomId&protocol=0,1&format=0,1,2&codec=0&qn=1000&platform=web"
    val c = java.net.URL(api).openConnection() as java.net.HttpURLConnection
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
    c.setRequestProperty("Referer", "https://live.bilibili.com/")
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
