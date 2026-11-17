package app.orchard.tv.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import android.graphics.PorterDuff
import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.orchard.tv.ui.components.smoothBorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.ClockHours
import app.orchard.tv.wallpaper.Images
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.NetworkStatus
import app.orchard.tv.ui.components.Dissolve
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.frozen
import app.orchard.tv.ui.components.glassEdge
import app.orchard.tv.ui.components.rememberFrostSwap
import app.orchard.tv.ui.components.smoothMask
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.ui.theme.OutfitRegular
import app.orchard.tv.ui.theme.OutfitMedium
import app.orchard.tv.ui.theme.OppoSans
import app.orchard.tv.ui.components.diagLayer
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherKind
import app.orchard.tv.weather.WeatherNow
import app.orchard.tv.weather.ForecastDay
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Tick on the minute and refresh immediately after waking or a system clock correction. */
@Composable
fun rememberNow(): State<LocalDateTime> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now = remember { mutableStateOf(LocalDateTime.now()) }
    DisposableEffect(context, lifecycle) {
        fun refresh() { now.value = LocalDateTime.now() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        var registered = false
        fun stop() {
            if (registered) {
                context.unregisterReceiver(receiver)
                registered = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                    registered = true
                    refresh()
                }
                Lifecycle.Event.ON_RESUME -> refresh()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        // A minute-long coroutine delay survives standby with its old value on Hisense VIDAA.
        // Adding the observer also dispatches ON_START/ON_RESUME if already in the foreground.
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }
    return now
}

@Composable
fun timeText(now: LocalDateTime): String {
    // 制式只看个性化里的「时钟制式」: 24 小时制固定 HH:mm(00:00 带前导零, 与原来一致),
    // 12 小时制 h:mm(不带前导零, 午夜显示 12:xx)。旧值 System 按 24 小时制走 —— 跟着电视语言
    // 跑会被 zh_CN 带成 12 小时制, 这里不再跟随系统设置。
    val twelve = AerialSettings.clockHours == ClockHours.H12
    val pattern = if (twelve) "h:mm" else "HH:mm"
    // 冒号两侧补一个细空格: 数字是等宽 570 的窄字身, 冒号本身只有 270, 字距又是 0, 64 的数字
    // 与冒号黑边之间只剩几个像素, 52sp 上几乎粘在一起(用户反馈"数字与冒号之间没有空隙")。
    // 用 U+2009(thin space, OPPO Sans 子集里 adv=116/1000), 52sp 上每侧约 6dp, 比整空格克制。
    return now.format(DateTimeFormatter.ofPattern(pattern)).replace(":", "\u2009:\u2009")
}

@Composable
fun dateText(now: LocalDateTime): String {
    val locale = Locale.getDefault()
    // zh's best pattern runs the weekday into the date ("M月d日EEEE"); a space keeps them apart.
    val pattern = remember(locale) {
        DateFormat.getBestDateTimePattern(locale, "MMMMdEEEE")
            .replace(Regex("(?<=[^\\s,，'])EEEE"), " EEEE")
            .replace(Regex("EEEE(?=[^\\s,，'])"), "EEEE ")
    }
    // Android's SimpleDateFormat (ICU), not java.time: through desugaring, java.time gave the month
    // its stand-alone form, "23 октябрь" in Russian where a date needs "23 октября".
    val millis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return SimpleDateFormat(pattern, locale).format(Date(millis))
}

/**
 * The home background, in two states as on tvOS 26. Focus in the top row: the picture itself, with
 * a plate of Liquid Glass under the row ([plate], in px at scroll 0). Focus down in the grid: the
 * whole screen turns to the plate's glass, the picture frosted and milky, not darkened (issue
 * #727: a darkened picture read as another background), so the plate spreads into it.
 *
 * Everything follows [scroll] (the grid's scroll, [collapse] being where the grid has fully come
 * in), so the change is exactly as fast as the movement that causes it. Nothing is blurred live:
 * the plate shows the part of a frosted copy baked small ahead of time (Images.frost) that lies
 * under it, stretched over the screen through a shader; the grid draws that same small glass
 * stretched. At rest a frame is one full-screen draw (plus the plate in the top row); both
 * pictures are drawn only through the middle of the scroll, since each frame that draws both costs
 * a second full-screen fill (Xiaomi MiTV-MFTR0: 90th percentile 20 -> 30 ms when the crossfade
 * ran end to end). Not the sharp picture dimmed by a colour filter instead: stretching the
 * full-size picture each frame made scrolling slower (MFTR0: median 40 -> 65 ms).
 */
@Composable
fun HomeBackground(
    backdrop: ImageBitmap?,
    frosted: ImageBitmap?,
    plate: Rect?,
    scroll: () -> Float,
    collapse: Float,
    /** How far the aerial wallpaper's film shows through (LiveWallpaper): picture and plate give way to it. */
    cover: () -> Float = { 0f },
    /**
     * Shade what shows through as a picture is shaded: an input's picture, which cannot be shaded
     * ahead. A film is not shaded, moving or stopped, nor is its picture (Wallpapers): shaded, the
     * aerials looked grey, and the screensaver carrying the film on was shaded twice (the owner,
     * Xiaomi MiTV-MFTR0).
     */
    shadeMoving: Boolean = false,
    /**
     * How far the film is stopped (0 to 1) with its glass ready ([frosted]) but its frame not yet
     * the picture, which takes a moment more where the frame is decoded again (Xiaomi
     * MiTV-MFTR0). Meanwhile the plate stands over the paused film, so the top row is back on
     * its glass with the rest of Home, the same plate it keeps once the picture is in.
     */
    filmStopped: () -> Float = { 0f },
    /** How far Home has given way to the screensaver carrying its wallpaper on: the plate goes. */
    toSaver: () -> Float = { 0f },
    /**
     * The glass for a moving film (Wallpapers.filmFrost, or [frosted] until it has one): the plate
     * stays over the film on it. Null where the film has none (an input's picture): the plate is
     * the smoke and the lit edge alone.
     */
    filmGlass: ImageBitmap? = null,
    /** The wallpaper's picture unshaded, shown [saverReveal] of the way and [saverPush] into its draw-in. */
    saverImage: ImageBitmap? = null,
    saverReveal: () -> Float = { 0f },
    saverPush: () -> Float = { 0f },
    /** How far Home is lifted on its shelf, apart from [scroll]: the plate goes up with the row, nothing fades. */
    lift: () -> Float = { 0f },
    /** The chosen wallpaper: a new [frosted] of the same one waits for the top row (see Pictures). */
    wallpaper: String? = null,
    /**
     * The film is the aerial wallpaper's, whose glass for where it stopped is ready once
     * [filmStopped]: until then the grid shows [filmGlass], the glass it had while it moved.
     */
    glassAwaitsStop: Boolean = false,
    /** The grid's own glass, where it is not the picture's ([frosted]): the aerial film's (Wallpapers.clipGlass). */
    gridGlass: ImageBitmap? = null,
    /** 二创: 直播预览的影片在 UI 下面播, 和视频壁纸一样: 不画整屏壁纸, 底板只留灰色透明那一层。 */
    plateOverVideo: Boolean = false,
    /** 二创: 底板由调用方在直播画面之后单独画(HomePlate), 免得整块壁纸层被直播盖住。 */
    drawPlate: Boolean = true,
) {
    val shade = remember { Images.homeShade.asImageBitmap() }
    // Where the film's frames cannot be had (Wallpapers.filmGlassless), the plate is the one
    // over a still picture, of the picture's glass, over the film moving or stopped: the
    // picture is the frame decoded where the film was let go, and the film goes on from it
    // (LiveWallpaper.goOnFromPicture; owner, TCL MT5879, issue #1238).
    // Its glass follows the film where frames of it are decoded in the app while it moves
    // ([filmGlass], every 15 s), so it is not the colours of a shot long gone.
    val plateStill = !plateOverVideo && Wallpapers.filmGlassless && Wallpapers.moves() && (frosted ?: backdrop) != null
    val plateFilm = if (plateOverVideo) filmGlass ?: frosted ?: backdrop else filmGlass.takeUnless { Wallpapers.filmGlassless }
    val plateFilmPlate = plateOverVideo || plateFilm != null
    val plateDeepSmoke = Wallpapers.filmGlassless && !plateOverVideo
    val plateGlass = if (plateStill) filmGlass ?: frosted ?: backdrop else frosted ?: backdrop
    Box(Modifier.fillMaxSize()) {
        // Over an input's picture, under the pictures: the shading a picture has.
        if (shadeMoving) Canvas(Modifier.fillMaxSize().diagLayer("shade")) {
            val stopped = maxOf(filmStopped(), cover())
            // Deep in the grid its glass covers everything.
            if (stopped < 0.01f || cover() < 0.01f || scroll() > collapse * 0.7f) return@Canvas
            drawImage(shade, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low, alpha = stopped)
        }
        Box(Modifier.fillMaxSize().diagLayer("picture")) {
            Pictures(backdrop, gridGlass ?: frosted, filmGlass.takeIf { glassAwaitsStop }, wallpaper, scroll, collapse, cover, filmStopped, toSaver)
        }
        if (saverImage != null) Canvas(Modifier.fillMaxSize()) {
            val a = saverReveal()
            if (a > 0.01f) drawDrawingIn(saverImage, saverPush(), a)
        }
        if (drawPlate) HomePlate(
            glass = plateGlass,
            film = plateFilm,
            filmPlate = plateFilmPlate,
            deepSmoke = plateDeepSmoke,
            stillOverFilm = plateStill,
            plate = plate,
            scroll = scroll,
            collapse = collapse,
            cover = cover,
            filmStopped = filmStopped,
            toSaver = toSaver,
            lift = lift,
        )
    }
}

/**
 * The sharp picture and the grid's glass. A new wallpaper fades in over the old, which stays whole
 * beneath it: crossfaded, the screen dimmed half-way through.
 *
 * The glass the grid shows stays while it shows (issue #727): a new [frosted] of the same
 * [wallpaper] (the aerial film stopped somewhere new: the exact frame's glass a few seconds after
 * it stopped) waits until the glass is out of sight, on the top row, where the plate takes it over
 * with its own fade. A film that has just stopped shows [filmGlass], the glass it had moving, until
 * the glass of where it stopped is in, which is mostly that same frame's (LiveWallpaper): holding
 * the grid's glass back meanwhile showed the film sharp as the grid came in, then frosted.
 */
@Composable
private fun Pictures(
    backdrop: ImageBitmap?,
    frosted: ImageBitmap?,
    filmGlass: ImageBitmap?,
    wallpaper: String?,
    scroll: () -> Float,
    collapse: Float,
    cover: () -> Float,
    filmStopped: () -> Float,
    toSaver: () -> Float,
) {
    // The grid's glass goes as the film carries on into the screensaver, as the apps do: the
    // screensaver over it is see-through, and the film showed frosted under it.
    fun gridGlass(): Float = (((scroll() / collapse).coerceIn(0f, 1f) - 0.3f) / 0.4f).coerceIn(0f, 1f) * (1f - toSaver())
    fun moving(): Boolean = filmGlass != null && cover() > 0.01f && filmStopped() < 0.99f
    var held by remember { mutableStateOf(frosted to wallpaper) }
    LaunchedEffect(frosted, wallpaper) {
        if (wallpaper == held.second) snapshotFlow { gridGlass() < 0.01f || moving() }.first { it }
        held = frosted to wallpaper
    }
    Dissolve(
        targetState = backdrop,
        durationMs = 450,
        easing = Motion.EaseOut,
        modifier = Modifier.fillMaxSize(),
        label = "ambient",
    ) { bmp ->
        // In "Follow App" mode there is no frosted copy: the app's light is small and soft
        // already, so it makes the glass itself, changing with it.
        val own = remember(bmp, frosted == null) { bmp?.takeIf { frosted == null }?.let(::plateTinted) }
        Canvas(Modifier.fillMaxSize()) {
            val film = cover()
            // On the top row, the film alone through the window: nothing of Compose's full-screen.
            if (film > 0.99f && scroll() < collapse * 0.3f) return@Canvas
            if (bmp == null) {
                drawRect(Color.Black)
                return@Canvas
            }
            val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
            // The sharp picture and the grid's glass cross only through the middle of the
            // scroll; each end is one full-screen draw.
            val g = gridGlass()
            if (g < 0.99f && film < 0.99f) drawImage(bmp, dstSize = full, filterQuality = FilterQuality.Low, alpha = 1f - film)
            if (own != null && g > 0.01f) drawImage(own, dstSize = full, filterQuality = FilterQuality.Low, alpha = g)
        }
    }
    // The glass of the chosen wallpaper: a new wallpaper fades in, a new glass of the same one is
    // put in only where it does not show.
    AnimatedContent(
        targetState = held,
        contentKey = { it.second },
        transitionSpec = {
            (fadeIn(tween(450, easing = Motion.EaseOut)) togetherWith ExitTransition.KeepUntilTransitionsFinished).using(null)
        },
        modifier = Modifier.fillMaxSize(),
        label = "glass",
    ) { (frost, _) ->
        val glass = remember(frost) { frost?.let(::plateTinted) }
        val moved = remember(filmGlass) { filmGlass?.let(::plateTinted) }
        Canvas(Modifier.fillMaxSize()) {
            val g = gridGlass()
            if (g < 0.01f) return@Canvas
            val src = (if (moving()) moved else null) ?: glass ?: return@Canvas
            drawImage(src, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low, alpha = g)
        }
    }
}

/**
 * 二创: 一级菜单那块玻璃底板, 单独一层。直播预览是全屏影片, 壁纸层排在它前面会被整块盖住,
 * 所以这一层要排在直播画面之后画 (HomeScreen)。
 */
@Composable
fun HomePlate(
    glass: ImageBitmap?,
    film: ImageBitmap?,
    filmPlate: Boolean,
    deepSmoke: Boolean,
    stillOverFilm: Boolean,
    plate: Rect?,
    scroll: () -> Float,
    collapse: Float,
    cover: () -> Float,
    filmStopped: () -> Float,
    toSaver: () -> Float,
    lift: () -> Float = { 0f },
) {
    Box(Modifier.fillMaxSize().diagLayer("plate")) {
        Plate(glass, film, filmPlate, deepSmoke, plate, scroll, collapse, cover, filmStopped, toSaver, lift, stillOverFilm)
    }
}

/**
 * The plate under the top row, one layer over whatever is behind it: the picture, or the film
 * once stopped. It gives way only to a moving film, so the swap from the paused film to its
 * picture leaves it as it is. A new glass fades in over the old one, which stays whole beneath
 * it: two plates fading against each other let what is behind show through halfway.
 *
 * Under it, while a film moves, the film's plate ([film]): a film under the window cannot be
 * blurred, so a small key frame of it, frosted, a few seconds old (LiveWallpaper), lies
 * [FILM_FROST] of the way over the film, and a smoke tint and the lit edge over both. The film
 * shows through moving, so the frame's lag reads as the glass's softness. With no [film] (an
 * input's picture) only the smoke and the edge. Drawn only as the plate
 * moves or a new frame fades in, every [GLASS_EVERY_MS]: the film plays with Compose all but still.
 */
@Composable
private fun Plate(glass: ImageBitmap?, film: ImageBitmap?, filmPlate: Boolean, deepSmoke: Boolean, plate: Rect?, scroll: () -> Float, collapse: Float, cover: () -> Float, filmStopped: () -> Float, toSaver: () -> Float, lift: () -> Float, stillOverFilm: Boolean = false) {
    var shown by remember { mutableStateOf(glass) }
    var under by remember { mutableStateOf<ImageBitmap?>(null) }
    val swap = remember { Animatable(1f) }
    fun visibility(): Float {
        val rest = 1f - smooth(((scroll() / collapse).coerceIn(0f, 1f) / 0.55f).coerceIn(0f, 1f))
        // [stillOverFilm]: the still plate over the film too, moving or not.
        return rest * (if (stillOverFilm) 1f else 1f - cover() * (1f - filmStopped())) * (1f - toSaver())
    }
    LaunchedEffect(glass) {
        if (glass === shown) return@LaunchedEffect
        // Not on screen (a moving film, or the grid): the new glass simply takes over. Faded in
        // over the old one, a plate appearing for a stopped film would flash the last film's colours.
        if (visibility() < 0.05f) {
            shown = glass
            under = null
            swap.snapTo(1f)
            return@LaunchedEffect
        }
        under = shown.takeIf { swap.value > 0.5f } ?: under
        shown = glass
        swap.snapTo(0f)
        swap.animateTo(1f, tween(450, easing = Motion.EaseOut))
        under = null
    }
    // Each glass and each new frame is baked off the main thread. Baked while drawing, the film's
    // frames held up a frame every few seconds (Xiaomi MiTV-MFTR0: frames of 50-70 ms), and the
    // glass of a film that had just stopped took the main thread for about 37 ms of a menu's
    // opening. A glass not baked yet is not drawn; until then the one before it stays.
    val density = LocalDensity.current
    var screen by remember { mutableStateOf(Size.Zero) }
    suspend fun baked(glass: ImageBitmap?): ImageBitmap? {
        // A film's plate is never the still glass (below): nothing to bake.
        if (glass == null || plate == null || screen == Size.Zero || filmPlate) return null
        val size = screen
        return withContext(Dispatchers.Default) {
            bakePlate(plateTinted(glass), size, plate, with(density) { PlateCorner.toPx() }, with(density) { 1.2.dp.toPx() })
        }
    }
    // 重烘焙期间保留上一张: 否则 plate 矩形一变(露出下一排、行位移动)的那几帧底板会"消失一下"
    var top by remember { mutableStateOf<ImageBitmap?>(null) }
    var bottom by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(shown, plate, screen) { baked(shown)?.let { top = it } }
    LaunchedEffect(under, plate, screen) { baked(under)?.let { bottom = it } }
    var filmTop by remember { mutableStateOf<ImageBitmap?>(null) }
    var filmBottom by remember { mutableStateOf<ImageBitmap?>(null) }
    var smoke by remember { mutableStateOf<ImageBitmap?>(null) }
    val filmSwap = remember { Animatable(1f) }
    LaunchedEffect(plate, screen, deepSmoke) {
        if (plate == null || screen == Size.Zero) return@LaunchedEffect
        val b = withContext(Dispatchers.Default) { bakePlate(smokeGlass(if (deepSmoke) FILM_SMOKE_ALONE else FILM_SMOKE), screen, plate, with(density) { PlateCorner.toPx() }, with(density) { 1.2.dp.toPx() }) }
        smoke = b
    }
    LaunchedEffect(film, plate, screen) {
        if (film == null || plate == null || screen == Size.Zero) {
            if (film == null) { filmTop = null; filmBottom = null }
            return@LaunchedEffect
        }
        val baked = withContext(Dispatchers.Default) { bakePlate(plateTinted(film), screen, plate, with(density) { PlateCorner.toPx() }, 0f, edge = false) }
        // Not on screen: the new frame simply takes over.
        if (filmTop == null || cover() < 0.01f) {
            filmTop = baked
            filmBottom = null
            filmSwap.snapTo(1f)
            return@LaunchedEffect
        }
        filmBottom = filmTop.takeIf { filmSwap.value > 0.5f } ?: filmBottom
        filmTop = baked
        filmSwap.snapTo(0f)
        filmSwap.animateTo(1f, tween(FILM_SWAP_MS, easing = Motion.EaseOut))
        filmBottom = null
    }
    val frostPaint = remember { androidx.compose.ui.graphics.Paint() }

    Canvas(Modifier.fillMaxSize().onSizeChanged { screen = Size(it.width.toFloat(), it.height.toFloat()) }) {
        if (plate == null) return@Canvas
        // A film's plate stays the film's, moving or stopped: switched to the still glass as the
        // film stopped (a menu, Control Center, the grid), it turned from see-through to frosted
        // all over, another colour (the owner, Xiaomi MiTV-MFTR0).
        val alpha = if (filmPlate) 0f else visibility()
        // The film's plate, while the film shows and the plate over a picture does not hide it.
        // With no frame to frost (an input's picture, which cannot be copied) it is the smoke and
        // the lit edge alone, so the top row keeps a plate over anything.
        val base = (1f - smooth(((scroll() / collapse).coerceIn(0f, 1f) / 0.55f).coerceIn(0f, 1f))) * (1f - toSaver())
        val frame = filmTop
        val edge = smoke
        if (!stillOverFilm && edge != null && (filmPlate || cover() > 0.01f) && alpha < 0.99f && base > 0.01f) {
            val p = (scroll() / collapse).coerceIn(0f, 1f)
            val r = plate.translate(0f, -scroll() - lift()).inflate(PlateSwell.toPx() * smooth((p / 0.55f).coerceIn(0f, 1f)))
            val t = filmSwap.value
            val under = filmBottom
            // The old frame whole under the new, both in one layer laid over the film at FILM_FROST.
            if (frame != null && t < 1f && under != null) {
                frostPaint.alpha = FILM_FROST * base
                drawContext.canvas.saveLayer(r, frostPaint)
                drawBaked(under, r, 1f)
                drawBaked(frame, r, t)
                drawContext.canvas.restore()
            } else if (frame != null) {
                drawBaked(frame, r, FILM_FROST * base)
            }
            drawBaked(edge, r, base)
        }

        // The plate rides with its row and dissolves into the frosted background: it fades
        // over the first half of the scroll, swelling a little as if lifting off, and settles
        // back the same way when focus returns to the top row. Kept small on purpose: the
        // whole screen shrinking back into the plate read as too much.
        val p = (scroll() / collapse).coerceIn(0f, 1f)
        val rest = 1f - smooth((p / 0.55f).coerceIn(0f, 1f))
        if (alpha < 0.01f) return@Canvas
        val grow = PlateSwell.toPx() * (1f - rest)
        val r = plate.translate(0f, -scroll() - lift()).inflate(grow)
        val t = swap.value
        val below = bottom.takeIf { under != null }
        if (t < 1f) below?.let { drawBaked(it, r, alpha) }
        top?.let { drawBaked(it, r, if (below != null) alpha * t else alpha) }
    }
}

/** A plate baked at its resting place, drawn stretched to [at]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBaked(image: ImageBitmap, at: Rect, alpha: Float) = drawImage(
    image,
    dstOffset = IntOffset(at.left.roundToInt(), at.top.roundToInt()),
    dstSize = IntSize(at.width.roundToInt(), at.height.roundToInt()),
    alpha = alpha,
    filterQuality = FilterQuality.Low,
)

private fun smooth(x: Float) = x * x * (3f - 2f * x)

/** The film plate's tint over the film and its frosted frame, as one pixel the plate stretches. */
private fun smokeGlass(color: Int): ImageBitmap =
    android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        .apply { eraseColor(color) }.asImageBitmap()

/** How much of the film plate is its frosted key frame; the rest is the film itself, moving. */
private const val FILM_FROST = 0.55f

/**
 * The film plate's smoke: a little black, so the tiles keep their contrast over a bright film.
 * Deeper since the film is not shaded (18% before): the shade had darkened the film under it too.
 */
private val FILM_SMOKE = android.graphics.Color.argb(72, 0, 0, 0)

/**
 * With no frosted frame under it (Wallpapers.filmGlassless): a milky white veil, not smoke. Black
 * (40%) made the plate a dark slab over the film, nothing like glass (the owner, TCL MT5879,
 * 2026-11-01). White lifts it as glass lit from within, as Tigris and CleanTV do with no blur; the
 * lit edge does the rest. The plate with a frosted frame (FILM_SMOKE) the owner keeps as it is.
 */
private val FILM_SMOKE_ALONE = android.graphics.Color.argb(38, 255, 255, 255)

/** A new key frame fades in over the last: slow, as the glass is soft, and seldom (GLASS_EVERY_MS). */
private const val FILM_SWAP_MS = 700

/** The Liquid Glass plate under the top row: how far it reaches past the tiles, and its corners. */
val PlatePadH = 26.dp
val PlatePadV = 20.dp
private val PlateCorner = 32.dp

/** Panels' background (PanelBackdrop): the frosted picture under this much black. */
private const val GRID_SHADE = 0.55f

/** How much the plate swells as it dissolves into the grid's background. */
private val PlateSwell = 14.dp

/** The plate's tint: the frosted picture a little darker, lifted with white, so it reads as milky glass. */
private val PlateTint = floatArrayOf(
    0.8f, 0f, 0f, 0f, 30f,
    0f, 0.8f, 0f, 0f, 30f,
    0f, 0f, 0.8f, 0f, 30f,
    0f, 0f, 0f, 1f, 0f,
)

/** The glass picture with the plate's tint applied ahead of time, so the plate is one plain draw. */
private fun plateTinted(src: ImageBitmap): ImageBitmap {
    val out = src.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val paint = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(PlateTint) }
    android.graphics.Canvas(out).drawBitmap(src.asAndroidBitmap(), 0f, 0f, paint)
    return out.frozen().asImageBitmap()
}

/**
 * The part of [glass] (stretched over [screen]) under [plate], cut to the plate's outline, with its
 * lit edge: glass, continuous outline and lit edge baked into one picture at the plate's resting
 * place and size, stretched as it swells and riding up with its row as it fades. The GPU draws one
 * image. Composing them in shaders while drawing (1.27) came out solid white on older GPUs (Xiaomi
 * MiTV-ASTP0, Android 9, Mali-G31, issue #183; a HuiQu projector, #181) and drawn apart they cost
 * 3 ms more a frame than the plain rounded plate.
 */
private fun bakePlate(glass: ImageBitmap, screen: Size, plate: Rect, radius: Float, rim: Float, edge: Boolean = true): ImageBitmap {
    val w = plate.width.roundToInt().coerceAtLeast(1)
    val h = plate.height.roundToInt().coerceAtLeast(1)
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawBitmap(
        glass.asAndroidBitmap(),
        Matrix().apply { setScale(screen.width / glass.width, screen.height / glass.height); postTranslate(-plate.left, -plate.top) },
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
    )
    canvas.drawBitmap(smoothMask(w, h, radius), 0f, 0f, android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
    if (edge) canvas.drawBitmap(glassEdge(w, h, radius, rim).asAndroidBitmap(), 0f, 0f, null)
    return out.frozen().asImageBitmap()
}

/**
 * 二创: 直播菜单(二级菜单)的底板, 和一级菜单同一套配方: 磨砂帧 55% + 烟灰 + 亮边,
 * 这样两块底板看起来才是一块玻璃。玻璃图片取的是底板下面那一块壁纸。
 */
/**
 * 一级/二级菜单底板共用的那块灰色: 取壁纸磨砂帧的平均亮度(不带壁纸的颜色)。
 * 一级走 plateTinted(0.8 scale + 30 提亮), 所以这里反算回去, 保证烘焙出来的亮度
 * 和二级菜单那块灰板一模一样。
 */
internal fun frostGreyLevel(): Int {
    val frost = app.orchard.tv.wallpaper.Wallpapers.frosted ?: return 176
    return synchronized(greyLevels) {
        greyLevels.getOrPut(System.identityHashCode(frost)) {
            val small = android.graphics.Bitmap.createScaledBitmap(frost.asAndroidBitmap(), 16, 9, true)
            var sum = 0f
            for (y in 0 until 9) for (x in 0 until 16) {
                val c = small.getPixel(x, y)
                sum += 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }
            (sum / 144f).roundToInt().coerceIn(0, 255)
        }
    }
}

private val greyLevels = HashMap<Int, Int>()

/** 一张纯灰的"玻璃底图", 喂给 Plate 的烘焙流程: 出来的就是和二级菜单同一块灰板。 */
internal fun frostGreyGlass(): ImageBitmap {
    val level = frostGreyLevel()
    // 反算 plateTinted: 0.8*x + 30 = level
    val raw = (((level - 30) / 0.8f).roundToInt()).coerceIn(0, 255)
    return android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        .apply { eraseColor(android.graphics.Color.rgb(raw, raw, raw)) }
        .asImageBitmap()
}

internal fun bakeLivePlate(
    glass: ImageBitmap?,
    screen: androidx.compose.ui.geometry.Size,
    plate: androidx.compose.ui.geometry.Rect,
    radius: Float,
    rim: Float,
): ImageBitmap? {
    glass ?: return null
    val tinted = plateTinted(glass)
    val w = plate.width.roundToInt().coerceAtLeast(1)
    val h = plate.height.roundToInt().coerceAtLeast(1)
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val matrix = android.graphics.Matrix().apply {
        setScale(screen.width / tinted.width, screen.height / tinted.height)
        postTranslate(-plate.left, -plate.top)
    }
    // 磨砂帧 55%
    canvas.drawBitmap(tinted.asAndroidBitmap(), matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
        alpha = (FILM_FROST * 255f).roundToInt()
    })
    // 烟灰压暗, 和一级菜单一样
    canvas.drawColor(FILM_SMOKE)
    canvas.drawBitmap(smoothMask(w, h, radius), 0f, 0f, android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
    canvas.drawBitmap(glassEdge(w, h, radius, rim).asAndroidBitmap(), 0f, 0f, null)
    return out.frozen().asImageBitmap()
}

/**
 * What Home shows behind panels. Folders and Hidden Apps open on Home frosted, as on tvOS: the
 * grid's frosted background fading over it [frost] of the way, one full-screen draw of a small
 * picture (in "Follow App" mode, which has none, a dark veil). Control Center keeps Home sharp
 * under a dark [veil], its own panel the glass (1.41 frosted all of Home behind it too, which
 * owners took for everything gone blurry).
 */
@Composable
fun PanelBackdrop(frosted: ImageBitmap?, frost: () -> Float, veil: () -> Float) {
    val (shown, under, swap) = rememberFrostSwap(frosted)
    val dark = remember(shown) { shown?.let { darkened(it, GRID_SHADE) } }
    val darkUnder = remember(under) { under?.let { darkened(it, GRID_SHADE) } }
    Canvas(Modifier.fillMaxSize()) {
        val f = frost()
        if (f >= 0.01f) {
            val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val t = swap()
            if (dark == null) drawRect(Color.Black, alpha = 0.55f * f)
            else if (darkUnder != null && t < 1f) {
                drawImage(darkUnder, dstSize = full, filterQuality = FilterQuality.Low, alpha = f)
                drawImage(dark, dstSize = full, filterQuality = FilterQuality.Low, alpha = f * t)
            } else drawImage(dark, dstSize = full, filterQuality = FilterQuality.Low, alpha = f)
        }
        val v = veil()
        if (v >= 0.01f) drawRect(Color.Black, alpha = 0.45f * v)
    }
}

/** A small picture darkened ahead of time, so the grid's background costs no tint per frame. */
private fun darkened(src: ImageBitmap, alpha: Float): ImageBitmap {
    val out = src.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    android.graphics.Canvas(out).drawColor(android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0))
    return out.asImageBitmap()
}

/**
 * The large clock, as on the lock screen: the date (in Chinese, with the lunar one) over the time
 * in heavy digits, and under it the weather, the city it is for (issue #59) and today's high and
 * low. Left or right, the three lines share one edge, the tiles' edge. Each line's box is its
 * capitals' height ([capBox]), so the gaps between them are the gaps a reader sees. A soft shadow
 * keeps them readable on bright photos. The clock screensaver shows the same.
 */
@Composable
fun ShelfClock(
    now: LocalDateTime,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    toSaver: () -> Float = { 0f },
    saving: Boolean = false,
    away: Boolean = false,
) {
    val drift = heldDrift(saving, now)
    val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 10f)
    // 上游 v1.75 修复：副文案用更细字重（W500）而非半透明白，避免在无阴影影片上褪色
    // (Xiaomi MiTV-MFTR0)。保留二创的 detail.isNotEmpty() 判空。
    val second = SpanStyle(fontWeight = FontWeight.W500)
    val reduced = LocalReducedMotion.current
    val asideGone by animateFloatAsState(
        if (away) 1f else 0f,
        if (reduced) snap() else if (away) tween(400, easing = Motion.EaseOut) else tween(400, delayMillis = CLOCK_BACK_MS * 7 / 10, easing = Motion.EaseOut),
        label = "clock-aside",
    )
    val aside = Modifier.graphicsLayer {
        alpha = 1f - asideGone
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        val lunar = if (Locale.getDefault().language == "zh") Lunar.text(now.toLocalDate()) else null
        BasicText(
            buildAnnotatedString {
                append(dateText(now))
                if (lunar != null) withStyle(second) { append("  $lunar") }
            },
            style = Type.ClockDate.copy(shadow = shadow),
            modifier = aside.capBox(Type.ClockDate.fontSize),
        )
        BasicText(
            timeText(now),
            style = Type.Clock.copy(shadow = shadow),
            modifier = Modifier.padding(top = 16.dp)
                .toSaverClock(toSaver, Type.Clock.fontSize, 0.dp, drift)
                .capBox(Type.Clock.fontSize),
        )
        Weather.now?.takeIf { AerialSettings.showWeather }?.let { w ->
            val place = Weather.place?.takeIf { it.length <= 12 }
            val range = if (w.high != null && w.low != null) "${w.low}° ~ ${w.high}°" else null
            val detail = listOfNotNull(place, stringResource(conditionName(w.kind)), range).joinToString(" · ")
            Row(aside.padding(top = 18.dp).capBox(Type.ClockDetail.fontSize), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(glyphFor(w), Color.White, 18.dp, Modifier.padding(end = 7.dp))
                BasicText(
                    buildAnnotatedString {
                        append("${w.celsius}°")
                        if (detail.isNotEmpty()) withStyle(second) { append("  $detail") }
                    },
                    style = Type.ClockDetail.copy(shadow = shadow),
                )
            }
        }
    }
}
private fun greetingText(hour: Int): String = when (hour) {
    in 0..5 -> "晚上好"
    in 6..8 -> "早上好"
    in 9..11 -> "上午好"
    12 -> "中午好"
    in 13..17 -> "下午好"
    else -> "晚上好"
}
/**
 * How far the clock has wandered with the screensaver ([saverDrift]), held once it ends: the time
 * goes back from where it was, the wander shrinking with the rest of the way ([toSaverClock]).
 * Dropped at once on waking, the time jumped up to 24 dp in the first frame of its way back.
 */
@Composable
private fun heldDrift(saving: Boolean, now: LocalDateTime): DpOffset {
    var held by remember { mutableStateOf(DpOffset.Zero) }
    if (saving) held = saverDrift(now)
    return held
}

/** Inter's cap top below the top of a line of natural height, as a share of the font size. */
private const val CAP_TOP = 0.242f

/** Inter's cap height (and its digits' height), as a share of the font size. */
private const val CAP_HEIGHT = 0.727f

/**
 * Carries Home's time to where the screensaver shows it, top right and at its size, as [progress]
 * goes from 0 to 1: when the screensaver carries on the aerial wallpaper's film, Home's clock
 * becomes the screensaver's rather than one fading out and another in. [fontSize] is the time's
 * size on Home and [capTop] where its digits' cap top lies in this element's box.
 */
private fun Modifier.toSaverClock(progress: () -> Float, fontSize: TextUnit, capTop: Dp, drift: DpOffset = DpOffset.Zero): Modifier = composed {
    // Where Home lays the time out, read before this element's own transform.
    var placed by remember { mutableStateOf<Pair<Rect, Float>?>(null) }
    onGloballyPositioned { placed = it.boundsInRoot() to it.findRootCoordinates().size.width.toFloat() }
        .graphicsLayer {
            val p = progress()
            val (box, rootW) = placed ?: return@graphicsLayer
            if (p <= 0f) return@graphicsLayer
            val s = SaverClockStyle.fontSize.toPx() / fontSize.toPx()
            val k = 1f + (s - 1f) * p
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = k
            scaleY = k
            val targetCapTop = SaverClockTop.toPx() + SaverClockStyle.fontSize.toPx() * CAP_TOP
            val targetLeft = if (AerialSettings.clockRight) rootW - SaverClockSide.toPx() - box.width * s else SaverClockSide.toPx()
            translationX = p * (targetLeft - box.left + drift.x.toPx())
            translationY = p * (targetCapTop - s * capTop.toPx() - box.top + drift.y.toPx())
            // The screensaver shows no time: Home's goes as the screensaver comes.
            if (!AerialSettings.screensaverClock) alpha = 1f - p
            // Its shadow lies outside the capBox: an offscreen buffer would cut it as it fades.
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
}

/**
 * Lays a line of text out as just its capitals' height, from their top to the baseline, drawn where
 * it was: so a line's box is the ink a reader sees, and lines line up by their tops and baselines.
 * Descenders and accents hang outside the box.
 */
private fun Modifier.capBox(fontSize: TextUnit) = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val baseline = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
    val top = (baseline - fontSize.toPx() * CAP_HEIGHT).roundToInt().coerceAtLeast(0)
    layout(p.width, baseline - top) { p.place(0, -top) }
}

private fun conditionName(kind: WeatherKind): Int = when (kind) {
    WeatherKind.Clear -> R.string.weather_clear
    WeatherKind.PartlyCloudy -> R.string.weather_partly_cloudy
    WeatherKind.Cloudy -> R.string.weather_cloudy
    WeatherKind.Fog -> R.string.weather_fog
    WeatherKind.Haze -> R.string.weather_haze
    WeatherKind.Drizzle -> R.string.weather_drizzle
    WeatherKind.LightRain -> R.string.weather_light_rain
    WeatherKind.ModerateRain -> R.string.weather_moderate_rain
    WeatherKind.HeavyRain -> R.string.weather_heavy_rain
    WeatherKind.ViolentRain -> R.string.weather_heavy_rain
    WeatherKind.LightSnow -> R.string.weather_light_snow
    WeatherKind.ModerateSnow -> R.string.weather_moderate_snow
    WeatherKind.HeavySnow -> R.string.weather_heavy_snow
    WeatherKind.SnowStorm -> R.string.weather_heavy_snow
    WeatherKind.Sleet -> R.string.weather_light_snow
    WeatherKind.FreezingRain -> R.string.weather_moderate_rain
    WeatherKind.Hail -> R.string.weather_thunder
    WeatherKind.Thunder -> R.string.weather_thunder
    WeatherKind.ThunderHail -> R.string.weather_thunder
    WeatherKind.Dust -> R.string.weather_fog
    WeatherKind.Sand -> R.string.weather_fog
    WeatherKind.SandStorm -> R.string.weather_thunder
    WeatherKind.Wind -> R.string.weather_cloudy
    WeatherKind.Typhoon -> R.string.weather_heavy_rain
    WeatherKind.Tornado -> R.string.weather_thunder
}

/**
 * The small clock: the time and the weather in one quiet line in the top left, level with the status
 * pill, so the wallpaper keeps the rest of the screen. A soft shadow keeps it readable on photos.
 */
@Composable
fun CornerClock(now: LocalDateTime, modifier: Modifier = Modifier, toSaver: () -> Float = { 0f }, saving: Boolean = false) {
    val drift = heldDrift(saving, now)
    val dim = Color.White.copy(alpha = 0.92f)
    val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 8f)
    val aside = Modifier.graphicsLayer {
        alpha = 1f - (toSaver() * 2.5f).coerceIn(0f, 1f)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
    val lunar = if (Locale.getDefault().language == "zh") Lunar.text(now.toLocalDate()) else null
    val dateStr = remember(now) {
        SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(
            now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
    }
    Column(
        modifier = modifier.then(aside),
        horizontalAlignment = Alignment.Start,
    ) {
        // 问候语: "城市，xx好！" 后面依次跟 节气 / 时令 / 节日 三颗胶囊, 缺哪颗就不显示哪颗。
        // 节日多于一个时, 跟天气预报一样停留 9 秒、1 秒向上轮播。
        // 问候语: "城市，xx好！" 后面跟 节气 / 时令 / 节日 三颗胶囊。
        // 节气常驻; 时令与节日只在当天命中才出现(不做整段常驻)。零点换代时:
        // 节气有变化 → 三颗整组滚动; 只有时令/节日变化 → 后两颗一起滚动。
        val place = Weather.place ?: "南昌"
        val date = now.toLocalDate()
        val term = Lunar.currentTerm(date)
        val season = Lunar.seasonal(date)
        val fests = Lunar.festivals(date)
        val greetingStyle = Type.Caption.copy(
            color = Color.White,
            shadow = shadow,
            letterSpacing = 0.08.em,
            fontSize = 15.sp,
            fontFamily = OutfitMedium,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("$place，${greetingText(now.hour)}！", style = greetingStyle)
            // 节气: targetState 变了才动画, 所以只在换节气那一帧滚动。
            AnimatedContent(
                targetState = term,
                label = "greeting-term",
                // 裁到槽位边界: 滚动时滚出槽位的那一半不画, 否则会压到上下相邻的行, 看起来像重叠。
                modifier = Modifier.clipToBounds(),
                transitionSpec = { capsuleScroll() },
            ) { t -> TermTag(t, greetingStyle) }
            // 时令 + 节日: 非空↔非空时整组上下滚动; 空↔非空时按汉字数做横向收放。
            //
            // 收放改由 AnimatedVisibility 的 expand/shrink 驱动, 不再靠 AnimatedContent 的空槽位:
            // 那条路里退场内容要等 SizeTransform 起算(还要等目标侧的 0 宽占位先量好),
            // 所以"变空"要慢半拍才看到收窄, 而"变出"却是立刻展开。
            // expand/shrink 在两个方向上都在状态变化的那一帧起步, 时长仍按汉字数算。
            val tail = remember(season, fests) { GreetingTail(season, fests) }
            // 最近一次非空内容: 收起时要按它画, 也拿它算收起时长
            var shownTail by remember { mutableStateOf(tail) }
            if (!tail.isEmpty() && shownTail != tail) shownTail = tail
            val revealed = !tail.isEmpty()
            val animMs = capsuleShrinkMs(shownTail.text())
            Box(Modifier.clipToBounds()) {
                // 恒高占位: 只占高度、不占宽度, 让"空 / 有时令 / 有节日"三种状态整行一样高
                CapsuleBand {
                    Box(
                        Modifier.layout { measurable, constraints ->
                            val p = measurable.measure(Constraints(0, Constraints.Infinity, 0, Constraints.Infinity))
                            layout(0, p.height) {}
                        }
                    ) { GreetingTag("时", greetingStyle) }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = revealed,
                    enter = expandHorizontally(
                        animationSpec = tween(animMs),
                        expandFrom = Alignment.Start,
                    ),
                    exit = shrinkHorizontally(
                        animationSpec = tween(animMs),
                        shrinkTowards = Alignment.Start,
                    ),
                ) {
                    AnimatedContent(
                        targetState = shownTail,
                        label = "greeting-tail",
                        modifier = Modifier.clipToBounds(),
                        transitionSpec = { capsuleScroll() },
                    ) { t ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (t.season.isNotEmpty()) {
                                CapsuleBand { GreetingTag(t.season.joinToString("和"), greetingStyle) }
                            }
                            if (t.fests.isNotEmpty()) FestivalCarousel(t.fests, greetingStyle)
                        }
                    }
                }
            }
        }
        // 时间行: 大时间 + 时段 | 日期+农历
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicText(
                timeText(now),
                style = Type.SmallClock.copy(
                    fontSize = 52.sp,
                    color = Color.White,
                    shadow = shadow,
                    letterSpacing = 0.em,
                    // 700/600 在 52sp 上仍然发粗, 400 又偏细: 取中间档 500。
                    // 数字轮廓是 OPPO Sans 4.0 可变字体按 wght=500 实例化, 等宽 570, 3 已修好。
                    fontWeight = FontWeight.W500,
                    fontFamily = OppoSans,
                ),
                modifier = Modifier.toSaverClock(toSaver, 52.sp, 52.sp.value.dp * CAP_TOP, drift),
            )
            val period = when (now.hour) {
                in 6..8 -> "早上"
                in 9..11 -> "上午"
                12 -> "中午"
                in 13..17 -> "下午"
                in 18..23 -> "晚上"
                0 -> "午夜"
                in 1..4 -> "凌晨"
                5 -> "清晨"
                else -> ""
            }
            BasicText(
                period,
                style = Type.Caption.copy(
                    color = Color.White,
                    shadow = shadow,
                    fontSize = 15.sp,
                    letterSpacing = 0.08.em,
                    fontFamily = OutfitMedium,
                ),
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
            // 竖线分隔
            Box(
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .width(1.dp)
                    .height(40.dp)
                    .background(Color.White.copy(alpha = 0.3f)),
            )
            Column(
                modifier = Modifier.padding(bottom = 7.dp),
            ) {
                BasicText(
                    dateStr,
                    style = Type.Caption.copy(
                        color = Color.White,
                        shadow = shadow,
                        fontSize = 14.sp,
                        letterSpacing = 0.04.em,
                        fontFamily = OppoSans,
                    ),
                )
                if (lunar != null) {
                    val lunarYear = Lunar.yearText(now.toLocalDate())
                    BasicText(
                        buildString {
                            if (lunarYear != null) append("$lunarYear ")
                            append(lunar)
                        },
                        style = Type.Caption.copy(
                            color = Color.White,
                            shadow = shadow,
                            fontSize = 14.sp,
                            letterSpacing = 0.04.em,
                            fontFamily = OutfitRegular,
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Weather.now?.takeIf { AerialSettings.showWeather }?.let { w ->
            val cond = stringResource(conditionName(w.kind))
            val fc = w.forecast
            // 左右互换: 实时天气固定在左侧; 右侧是今天/明天/后天预报, 10 秒一轮向上滚动。
            // 两组之间不再放竖线, 靠 14dp 留白与胶囊形状区分。
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val weatherStyle = Type.Caption.copy(
                    color = Color.White, shadow = shadow, fontSize = 16.sp,
                    fontFamily = OppoSans, letterSpacing = 0.em, lineHeight = 22.sp,
                )
                WeatherTag("现在", weatherStyle, now = true)
                WeatherIcon(w.kind, isDayNow(), 20.dp, Modifier.padding(start = 6.dp))
                BasicText("$cond ${w.celsius}°", style = weatherStyle)
                ForecastCarousel(fc, weatherStyle)
            }
        }
    }
}
/** 今天/明天/后天预报轮播: 滑动 1 秒、停留 9 秒, 新一天从下方滚上来, 旧一天向上走。 */
@Composable
private fun ForecastCarousel(fc: List<ForecastDay>, weatherStyle: TextStyle) {
    val days = fc.take(3)
    if (days.isEmpty()) return
    var idx by remember { mutableStateOf(0) }
    // 各槽位上次展示的条目。预报中途刷新时, 不在当前可见槽位上硬切,
    // 等轮到该槽位再取最新数据, 跨天/换批随滚动逐槽自然更新。
    // 先按天数补齐, 避免首帧 LaunchedEffect 还没跑、空列表按下标取值越界。
    val slots = remember(days.size) {
        mutableStateListOf<ForecastDay?>().apply { repeat(days.size) { add(null) } }
    }
    // 轮播协程长期存活, 必须始终读最新预报; 否则闭口号住首帧旧列表, 跨天后槽位不换代。
    val latest by rememberUpdatedState(days)
    val bandPx = with(LocalDensity.current) { CAPSULE_FADE_BAND.roundToPx() }
    LaunchedEffect(days) {
        while (slots.size < days.size) slots.add(null)
        // 新数据到达: 非当前可见槽位先换代; 当前可见槽位保持旧条目, 轮到它再换。
        days.forEachIndexed { i, d -> if (i != idx || slots[i] == null) slots[i] = d }
    }
    LaunchedEffect(days.size) {
        if (days.size > 1) {
            while (true) {
                // 滑动 1 秒, 停 9 秒, 一轮仍是 10 秒。
                delay(9_000)
                idx = (idx + 1) % latest.size
                slots[idx] = latest.getOrNull(idx)
            }
        }
    }
    // 槽位宽度直接写死一个够长够宽的定值, 不做任何测量: 容器宽度永不变化,
    // 长短天气互相切换时既不会往左回缩, 也不会因为算窄把温度挤成两行。
    AnimatedContent(
        targetState = idx,
        label = "forecast",
        modifier = Modifier
            .padding(start = 14.dp)
            .width(FORECAST_SLOT_WIDTH)
            .capsuleEdgeFade(bandPx),
        transitionSpec = { capsuleSlideScroll() },
    ) { i ->
        // slots 初始为空, 首轮组合时 effect 还没填充, 必须走兜底, 否则越界崩溃。
        // 预报中途可能被清空, 兜底同样要容错: 取不到就不画这一帧, 绝不能按空列表下标取值。
        val d = slots.getOrNull(i) ?: days.getOrNull(forecastIndex(i, days.size))
        // 上下各留一条渐隐带: 胶囊本体在带内, 滚动的内容从带里进出才淡掉, 胶囊本身不落在带里。
        Box(Modifier.padding(vertical = CAPSULE_FADE_BAND), contentAlignment = Alignment.CenterStart) {
            if (d != null) ForecastRow(d, forecastLabel(i), weatherStyle)
        }
    }
}

/** 槽位下标: 数据条数少于三日时按实际条数取模, 不越界。 */
private fun forecastIndex(i: Int, size: Int): Int = if (size <= 0) 0 else i % size

private fun forecastLabel(i: Int): String = when (i) { 0 -> "今天"; 1 -> "明天"; else -> "后天" }

/** 一行预报: 日期标签 + 天气图标 + 天气文字 + 气温。文字不折行, 宽度由外层槽位负责。 */
@Composable
private fun ForecastRow(d: ForecastDay, label: String, weatherStyle: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WeatherTag(label, weatherStyle, now = false)
        // 预报只表示"这一天", 图标一律用白天版本, 不跟当前时段走。
        WeatherIcon(d.dayKind, true, 20.dp, Modifier.padding(start = 6.dp))
        if (d.display.contains("转")) {
            WeatherIcon(d.nightKind, true, 20.dp, Modifier.padding(start = 1.dp))
        }
        if (d.display.isNotEmpty()) {
            BasicText(d.display, style = weatherStyle, maxLines = 1, softWrap = false)
        }
        BasicText(" ${d.low}° / ${d.high}°", style = weatherStyle, maxLines = 1, softWrap = false)
    }
}

/** 同一天多个节日: 与预报一致, 停留 9 秒、1 秒向上滚动轮换; 单个节日直接静态显示。 */
@Composable
private fun FestivalCarousel(items: List<String>, style: TextStyle) {
    if (items.isEmpty()) return
    var idx by remember { mutableStateOf(0) }
    val slots = remember(items.size) {
        mutableStateListOf<String?>().apply { repeat(items.size) { add(null) } }
    }
    val latest by rememberUpdatedState(items)
    val bandPx = with(LocalDensity.current) { CAPSULE_FADE_BAND.roundToPx() }
    LaunchedEffect(items) {
        while (slots.size < items.size) slots.add(null)
        items.forEachIndexed { i, s -> if (i != idx || slots[i] == null) slots[i] = s }
    }
    LaunchedEffect(items.size) {
        if (items.size > 1) {
            while (true) {
                delay(9_000)
                idx = (idx + 1) % latest.size
                slots[idx] = latest.getOrNull(idx)
            }
        }
    }
    // 节日槽位同样写死宽度: 长名滚到短名时容器宽度不动, 不会回缩。
    // 过渡与天气预报同一套(带默认 SizeTransform 裁剪), 两张内容各占一半, 滚动中不会叠在一起;
    // 槽位上下边缘在滚动时渐隐, 滚出去的那张是淡掉而不是被裁出平口。
    AnimatedContent(
        targetState = idx,
        label = "festival",
        modifier = Modifier
            .width(FESTIVAL_SLOT_WIDTH)
            .capsuleEdgeFade(bandPx),
        transitionSpec = { capsuleSlideScroll() },
    ) { i ->
        val text = slots.getOrNull(i) ?: items.getOrNull(i)
        Box(Modifier.padding(vertical = CAPSULE_FADE_BAND), contentAlignment = Alignment.CenterStart) {
            if (text != null) GreetingTag(text, style)
        }
    }
}

/**
 * 胶囊槽位的上下渐隐留白: 所有会进问候行的胶囊都套这一层, 保证"只有节气 / 带时令 / 带节日"
 * 三种状态下整行高度完全一致 —— 行高一变就会顶动时钟行和天气行。
 */
@Composable
private fun CapsuleBand(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.padding(vertical = CAPSULE_FADE_BAND),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/** 节日胶囊槽位的固定宽度: 够放任何一天的多个节日名, 定宽是为了切换时不回缩不撑开。 */
private val FESTIVAL_SLOT_WIDTH = 1000.dp

/** 渐隐带高度: 槽位上下各留一条, 落在胶囊本体之外, 胶囊不在带里。 */
private val CAPSULE_FADE_BAND = 4.dp

/**
 * 上下两条渐隐带: 内容滚进带里逐渐变透明, 换页是"溶解"而不是被裁出一条平口。
 * 遮罩固定在槽位最外侧 [bandPx] 内: 槽位上下各留了同样宽的空白(胶囊本体在空白之内),
 * 静止时带里什么都没有, 胶囊一点不受影响, 也就没有"滚完突然变亮";
 * 离屏层自身仍按边界裁剪, 所以滚出槽位的那一半不会压到相邻的行。
 */
private fun Modifier.capsuleEdgeFade(bandPx: Int): Modifier =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (bandPx <= 0) return@drawWithContent
            val stop = (bandPx.toFloat() / size.height).coerceIn(0f, 0.5f)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    stop to Color.White,
                    (1f - stop) to Color.White,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/**
 * 轮播用的上下滚动: 1 秒, 新内容从下方滚入、旧内容向上滚出。
 * 保留默认的 SizeTransform(clip = true), 过渡期间内容被容器边界裁掉, 进出两张各占一半、不会重叠;
 * 天气预报与节日轮播都用这一套, 节奏和观感保持一致。
 */
private fun capsuleSlideScroll(): ContentTransform {
    val slide = tween<IntOffset>(CAPSULE_SCROLL_MS)
    // 淡入淡出都用线性、且各自占满自己能看见的那段行程:
    // 进场那一半先钻进槽位(此时在裁剪区外, 看不见), 所以淡入要延后一点才开始, 否则等它露头时
    // 已经不透明了 —— 看上去"上面有淡出、下面没有淡入"。退场则从头就开始淡.
    val fadeInSpec = tween<Float>(CAPSULE_FADE_IN_MS, delayMillis = CAPSULE_FADE_IN_DELAY_MS, easing = LinearEasing)
    val fadeOutSpec = tween<Float>(CAPSULE_FADE_OUT_MS, easing = LinearEasing)
    return (slideInVertically(animationSpec = slide) { h -> h } + fadeIn(fadeInSpec))
        .togetherWith(slideOutVertically(animationSpec = slide) { h -> -h } + fadeOut(fadeOutSpec))
}

/** 上下滚动时长: 1 秒。 */
private const val CAPSULE_SCROLL_MS = 1000

/** 进场淡入的时长与起跑延迟: 等新胶囊钻进槽位再开始变实, 才看得见"淡入"。 */
private const val CAPSULE_FADE_IN_MS = 700
private const val CAPSULE_FADE_IN_DELAY_MS = 250

/** 退场淡出时长: 旧胶囊一边往上走一边变淡, 整个可见行程都在变。 */
private const val CAPSULE_FADE_OUT_MS = 700

/**
 * 零点换代用的滚动: 新内容从下方滚上来、旧内容向上走, 1 秒走完, 与天气轮播同一节奏。
 * using(null) 去掉默认的 SizeTransform 与裁剪, 滚动中的字不会被槽位边界切掉;
 * using 是 AnimatedContentTransitionScope 上的成员, 所以这里按接收者扩展定义。
 */
private fun <S> AnimatedContentTransitionScope<S>.capsuleScroll(): ContentTransform {
    val slide = tween<IntOffset>(CAPSULE_SCROLL_MS)
    val fadeInSpec = tween<Float>(CAPSULE_FADE_IN_MS, delayMillis = CAPSULE_FADE_IN_DELAY_MS, easing = LinearEasing)
    val fadeOutSpec = tween<Float>(CAPSULE_FADE_OUT_MS, easing = LinearEasing)
    return (slideInVertically(animationSpec = slide) { h -> h } + fadeIn(fadeInSpec))
        .togetherWith(slideOutVertically(animationSpec = slide) { h -> -h } + fadeOut(fadeOutSpec))
        .using(null)
}

/** 问候行后两颗胶囊(时令 + 节日)的一组状态: 作为一条 AnimatedContent 的 targetState, 变了两颗一起滚。 */
private data class GreetingTail(val season: List<String>, val fests: List<String>) {
    fun isEmpty(): Boolean = season.isEmpty() && fests.isEmpty()

    /** 这一组胶囊的全部文字: 只用来数汉字, 定收缩时长。 */
    fun text(): String = (season + fests).joinToString("")
}

/**
 * 变成空(或从空变出)时的收缩: 不做淡入淡出/上下滚, 只让容器宽度收到 0(或从 0 展开)。
 * 内容左对齐、容器按宽度裁剪, 看上去就是胶囊从右边往左缩短直到消失;
 * 旧内容用 KeepUntilTransitionsFinished 留住, 否则一进过渡就被丢掉, 看不到收缩过程。
 */
/**
 * 胶囊通用外壳: 全圆形状 + 底色或渐变 + 1dp 不透明白色描边 + 文字。
 * 四类胶囊(节气 / 时令 / 节日 / 天气)都走这里, 形状与描边因此完全一致。
 * 文字整体上抬一点点: 22sp 行高里 CJK 字形本身就压得偏低, 不抬看起来像贴着下沿。
 */
@Composable
private fun Capsule(
    text: String,
    style: TextStyle,
    textColor: Color,
    fill: Brush,
    weight: FontWeight? = null,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .smoothBorder(1.dp, Color.White, 999.dp)
            .padding(horizontal = 6.dp),
    ) {
        BasicText(
            text,
            style = style.copy(color = textColor, fontSize = 13.sp, lineHeight = 22.sp, fontWeight = weight),
            modifier = Modifier.offset(y = CAPSULE_TEXT_LIFT),
        )
    }
}

/** 文字在胶囊里的竖直微调: 上抬 1.5dp 让字形视觉居中。只挪绘制, 不改尺寸与行距。 */
private val CAPSULE_TEXT_LIFT = (-1.5).dp

/** 常驻的节气胶囊: 淡白底 + 白描边 + 白字。 */
@Composable
private fun TermTag(text: String, style: TextStyle) = Capsule(
    text = text,
    style = style,
    textColor = Color.White,
    fill = SolidColor(Color.White.copy(alpha = TERM_TAG_ALPHA)),
)

/** 常驻胶囊(节气)底色不透明度: 比实心白淡, 靠白描边立住形状。 */
private const val TERM_TAG_ALPHA = 0.16f

/** 时令 / 节日胶囊: 实心半透明白底 + 黑字, 同样带白描边。 */
@Composable
private fun GreetingTag(text: String, style: TextStyle) = Capsule(
    text = text,
    style = style,
    textColor = Color.Black,
    fill = SolidColor(Color.White.copy(alpha = TAG_ALPHA)),
)

/**
 * 天气胶囊(现在 / 今天 / 明天 / 后天): 与问候胶囊同款圆角与白描边, 底色换成斜向渐变。
 * "现在"红系: 左上偏红白、右下偏中国红; "今天/明天/后天"蓝系: 左上偏蓝白、右下偏天蓝。
 */
@Composable
private fun WeatherTag(text: String, style: TextStyle, now: Boolean) = Capsule(
    text = text,
    style = style,
    textColor = Color.White,
    fill = Brush.linearGradient(
        listOf(
            if (now) WEATHER_RED_LIGHT else WEATHER_BLUE_LIGHT,
            if (now) WEATHER_RED else WEATHER_BLUE,
        )
    ),
    weight = FontWeight.W600,
)

/** 天气胶囊半透明底(与问候胶囊同一档 0.72)与两套渐变端色。 */
private const val WEATHER_TAG_ALPHA = 0.72f
private val WEATHER_RED_LIGHT = Color(0xFFF3D8D6).copy(alpha = WEATHER_TAG_ALPHA)   // 左上: 偏红白
private val WEATHER_RED = Color(0xFFC8102E).copy(alpha = WEATHER_TAG_ALPHA)         // 右下: 中国红
private val WEATHER_BLUE_LIGHT = Color(0xFFD9EAFB).copy(alpha = WEATHER_TAG_ALPHA)  // 左上: 偏蓝白
private val WEATHER_BLUE = Color(0xFF2F9BE0).copy(alpha = WEATHER_TAG_ALPHA)        // 右下: 天蓝

/** 问候胶囊底色的不透明度: 1.0 实心白最扎眼, 越低越不抢眼。 */
private const val TAG_ALPHA = 0.70f

/** 预报槽位的固定宽度: 直接给一个远超实际需要的定值, 容器永不回缩, 也不会有任何测量误差。 */
private val FORECAST_SLOT_WIDTH = 1000.dp

private fun isDayNow(): Boolean {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return h in 6..18
}

private fun glyphFor(w: WeatherNow): Glyph = glyphForKind(w.kind, isDayNow())

private fun glyphForKind(kind: WeatherKind, day: Boolean): Glyph = when (kind) {
    WeatherKind.Clear -> if (day) Glyph.Sun else Glyph.Moon
    WeatherKind.PartlyCloudy -> if (day) Glyph.CloudSun else Glyph.Cloud
    WeatherKind.Cloudy -> Glyph.Cloud
    WeatherKind.Fog, WeatherKind.Haze, WeatherKind.Dust, WeatherKind.Sand -> Glyph.CloudFog
    WeatherKind.Drizzle, WeatherKind.LightRain, WeatherKind.FreezingRain -> Glyph.CloudLightRain
    WeatherKind.ModerateRain, WeatherKind.Sleet -> Glyph.CloudRain
    WeatherKind.HeavyRain, WeatherKind.ViolentRain, WeatherKind.Typhoon -> Glyph.CloudHeavyRain
    WeatherKind.LightSnow, WeatherKind.ModerateSnow, WeatherKind.HeavySnow, WeatherKind.SnowStorm -> Glyph.CloudSnow
    WeatherKind.Thunder, WeatherKind.ThunderHail, WeatherKind.Hail, WeatherKind.SandStorm, WeatherKind.Tornado -> Glyph.CloudBolt
    WeatherKind.Wind -> Glyph.Cloud
}

/** OPPO风格天气图标，直接用drawable渲染 */
@Composable
private fun WeatherIcon(kind: WeatherKind, day: Boolean, iconSize: Dp, modifier: Modifier = Modifier) {
    val resId = when (kind) {
        WeatherKind.Clear -> if (day) R.drawable.weather_clear else R.drawable.weather_night
        WeatherKind.PartlyCloudy -> if (day) R.drawable.weather_partly_cloudy else R.drawable.weather_cloudy_night
        WeatherKind.Cloudy -> R.drawable.weather_cloudy
        WeatherKind.Fog -> R.drawable.weather_fog
        WeatherKind.Haze -> R.drawable.weather_haze
        WeatherKind.Drizzle, WeatherKind.LightRain -> R.drawable.weather_light_rain
        WeatherKind.ModerateRain -> R.drawable.weather_rain
        WeatherKind.HeavyRain -> R.drawable.weather_heavy_rain
        WeatherKind.ViolentRain -> R.drawable.weather_storm
        WeatherKind.Thunder -> R.drawable.weather_thunder
        WeatherKind.ThunderHail -> R.drawable.weather_thunder_hail
        WeatherKind.LightSnow, WeatherKind.ModerateSnow -> R.drawable.weather_snow
        WeatherKind.HeavySnow -> R.drawable.weather_snow_heavy
        WeatherKind.SnowStorm -> R.drawable.weather_snow_storm
        WeatherKind.Sleet -> R.drawable.weather_sleet
        WeatherKind.FreezingRain -> R.drawable.weather_freezing_rain
        WeatherKind.Hail -> R.drawable.weather_hail
        WeatherKind.Dust -> R.drawable.weather_dust
        WeatherKind.Sand -> R.drawable.weather_sand
        WeatherKind.SandStorm -> R.drawable.weather_sandstorm
        WeatherKind.Wind -> R.drawable.weather_wind
        WeatherKind.Typhoon -> R.drawable.weather_typhoon
        WeatherKind.Tornado -> R.drawable.weather_tornado
    }
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(resId),
        contentDescription = null,
        modifier = modifier.size(iconSize),
    )
}

/** "VPN" in a thin frame beside the network glyph while a VPN is up, as iOS's status bar has it (issue #809). */
@Composable
fun VpnBadge(color: Color, modifier: Modifier = Modifier, fontSize: TextUnit = 10.sp) {
    BasicText(
        stringResource(R.string.net_vpn),
        style = Type.Caption.copy(color = color, fontSize = fontSize, fontWeight = FontWeight.W700, letterSpacing = 0.04.em),
        modifier = modifier
            .smoothBorder(1.2.dp, color, 4.dp)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/**
 * Top right: network and, once the shelf clock has scrolled away, the time. Focusable: it is
 * where Up from the top row leads, and it opens Control Center.
 */
@Composable
fun StatusPill(
    now: LocalDateTime,
    // 传取值函数而不是值: 网络状态现在每 5 秒刷新一次, 在 HomeScreen 的组合里直接读
    // vm.network 会让整棵桌面树每 5 秒重组一次(低端盒子上会掉帧), 读放在胶囊自己身上,
    // 失效范围就只有这颗胶囊。
    network: () -> NetworkStatus,
    showTime: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Pressable(
        onClick = onClick,
        enabled = enabled,
        focusRequester = focusRequester,
        focusedScale = 1.08f,
        modifier = modifier,
    ) { state ->
        LaunchedEffect(state.focused) { onFocused(state.focused) }
        val net = network()
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Color.Transparent,
            tween(160, easing = Motion.EaseOut), label = "pill-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Color.White.copy(alpha = 0.9f)
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .drawBehind { drawRect(bg) }
                .height(34.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val glyph = when (net.kind) {
                NetKind.Wifi -> Glyph.Wifi
                NetKind.Ethernet -> Glyph.Ethernet
                NetKind.Offline -> Glyph.Offline
            }
            GlyphIcon(glyph, fg, 17.dp, level = net.level)
            if (net.vpn) VpnBadge(fg)
            if (showTime) {
                BasicText(timeText(now), style = Type.Headline.copy(color = fg))
            }
            GlyphIcon(Glyph.Switches, fg, 17.dp)
        }
    }
}
