package app.orchard.tv.ui.home

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.CcPage
import app.orchard.tv.ui.BootVeil
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.LiveSource
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.Restricted
import app.orchard.tv.guard.KidsWatch
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.control.ControlCenter
import app.orchard.tv.ui.wallpaper.SheetCovers
import app.orchard.tv.ui.wallpaper.WallpaperSheet
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.ui.components.diagLayer
import androidx.compose.runtime.snapshotFlow
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.LaunchFallback
import app.orchard.tv.system.SystemProbe
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The plate's distance from the bottom edge with no second row peeking: as from the sides. */
private const val SAVER_FADE_MS = 500
private const val UI_FADE_MS = 500

/** 一级菜单与屏保过渡时下沉的距离, 和屏保里卡片的 18dp 一致。 */

private val PlateMarginBottom = 30.dp

/**
 * The gap between the two rows on one plate in two-row mode, with no names under them there: the
 * 50 dp the grid keeps for names left a wide empty band across the glass, and with four a row
 * pushed the plate up into the weather line.
 */
private val ShelfRowGap = 30.dp

/**
 * Under the second row in two-row mode without a plate: its names and a margin. As much as the
 * plate's margin and padding, so with and without the plate the rows take the same height: at
 * 70 dp the rows without one stood 20 dp higher and, four a row, pushed the clock up.
 */
private val RowNameRoom = 50.dp

/** Where the large clock's date sits: just under the status pill. */
private val LargeClockTop = 80.dp

/**
 * From the large clock's top to where the shelf may start: its weather line ends some 136 dp down
 * (216 dp from the top, MiTV-MFTR0, 1.65), and a gap.
 */
private val LargeClockHeight = 152.dp

/**
 * The highest the large clock may go to make room for two rows, level with the status pill. Only
 * at the left: at the right it would run into the pill.
 */
private val LargeClockTopMin = 28.dp

/** How much taller the glass plate grows to hold the top row's names, with the second row kept clear of it. */
private val PlateNameRoom = 28.dp

/** Control Center has been drawn once in this process (HomeScreen's warm-up). */
private object ControlCenterWarmed { var done = false }

private const val CC_WARM_AFTER_MS = 3_000L

private val GridMargin = 56.dp

/** The highest the row above may sit once Home has scrolled into the grid: clear of the status pill. */
private val RowAboveTop = 72.dp

/**
 * The gap between tiles for [columns] a row (AerialSettings.homeColumns): about a fifth of a tile,
 * as tvOS keeps it when it fits six, so four or six look like five scaled rather than packed.
 */
internal fun gridGap(columns: Int): Dp = when (columns) {
    4 -> 34.dp
    6 -> 24.dp
    else -> 28.dp
}

/** A tile's width at rest on a screen [screenW] wide; TileFaces works out the same size. */
internal fun restTileWidth(screenW: Dp, columns: Int): Dp = (screenW - GridMargin * 2 - gridGap(columns) * (columns - 1)) / columns

/** Grid geometry for the screen at hand. On a 1080p TV this is a 960×540 dp canvas. */
private class GridMetrics(screenW: Dp, screenH: Dp, val columns: Int, oneRow: Boolean, plateNames: Boolean, peek: Boolean, plate: Boolean, largeClock: Boolean, clockRight: Boolean) {
    val margin = GridMargin
    val gap = gridGap(columns)
    val tileW = restTileWidth(screenW, columns)
    val tileH = tileW * 9f / 16f
    val pitchX = tileW + gap
    val pitchY = tileH + 50.dp

    /**
     * Extra room under the top row while it is the one in focus, for names on a plate grown to
     * hold them. Down in the grid the plate is gone and the rows close up to even spacing.
     */
    // 底板上始终给名字留 28dp: 只显示选中的名称时, 第一排那个名字也画在底板上,
    // 不留位置就会掉到板子外面去
    val firstGap = if (plate) PlateNameRoom else 0.dp

    /**
     * How far the second row sits under the top row in two-row mode while both are on the shelf:
     * a grid row where names go between them (no plate, or every name shown), closer on a plate
     * with none. Not the grid's row plus [firstGap]: that stacked two names' room between them.
     */
    val shelfSecond = if (plate && !plateNames) tileH + ShelfRowGap else pitchY

    /**
     * Two rows, laid from the bottom up: under the second row the plate's margin (and its names'
     * room), or without a plate room for names; then both rows.
     */
    private val twoRowFit = screenH - (if (plate) PlateMarginBottom + PlatePadV + firstGap else RowNameRoom) - shelfSecond - tileH

    /**
     * Top of the large clock: just under the status pill, left or right, in both layouts, as on the
     * lock screen. Hung from the row it floated mid-screen in one-row mode and pressed on the plate
     * in two-row mode; up here the middle of the screen stays the wallpaper's. This is the date's
     * cap top: the clock's lines are boxed to their capitals. Two rows that do not fit under it
     * (four a row with every name) take it up beside the pill, at the left.
     */
    val clockY = if (oneRow || !largeClock) LargeClockTop else
        (twoRowFit - (if (plate) PlatePadV else 0.dp) - LargeClockHeight)
            .coerceIn(if (clockRight) LargeClockTop else LargeClockTopMin, LargeClockTop)

    /** The highest two rows may start: under the large clock's weather line. What does not fit lifts in on the second row ([shelfLift]). */
    private val twoRowFloor = if (largeClock) clockY + LargeClockHeight + if (plate) PlatePadV else 0.dp else 0.dp

    /**
     * In two-row mode both rows sit low, laid from the bottom up ([twoRowFit]). In one-row mode, or
     * when every app fits in one row, the row sits low, with only the top of any second row showing
     * under the focused app's name. Every name shown changes nothing there: the names fit in the gap
     * between the rows, or on the plate, which grows by [firstGap]. Lifting the row for them as
     * well showed the second row nearly three times as far (issue #540).
     */
    val topRowY = when {
        // 双排: 选了"下一排/露出一点"时, 把两排整体放在让第三排上沿露出 12dp 的位置
        !oneRow && peek -> maxOf(twoRowFloor, screenH - pitchY * 2 - 12.dp - firstGap)
        !oneRow -> maxOf(twoRowFit, twoRowFloor)
        peek -> screenH - pitchY - 12.dp - firstGap
        // Nothing peeks under the row, so it goes as low as it fits: the plate as far from the
        // bottom edge as from the sides, or without a plate, room for the focused app's name.
        plate -> screenH - tileH - (PlateMarginBottom + PlatePadV) - firstGap
        else -> screenH - tileH - 70.dp
    }

    /**
     * Where a row rests once you go down into the grid: the row above stays in view, its top under
     * the status pill (28 dp down, 34 tall). Four a row are tall enough that 37% of the screen put
     * that row over the pill; six still rest at 37%, five some 5 dp lower.
     */
    val restY = maxOf(screenH * 0.37f, RowAboveTop + pitchY)
    val screenH = screenH

    /**
     * How far the grid scrolls before the shelf has given way: the clock and the plate fade over
     * it, the grid's glass comes in, the film stops past half of it. The top row's way up to
     * [restY], but never less than half a row: two rows four a row sit as high as [restY] or
     * higher, and at zero or less Home counted as scrolled past the shelf while still on it,
     * and the aerial film never played.
     */
    val collapse = (topRowY - restY).coerceAtLeast(pitchY / 2)

    /**
     * How far Home lifts, still on its shelf, for the second row in two-row mode: what did not fit
     * under the large clock, so the second row and its names clear the bottom edge. Nothing in
     * almost every layout; it used to lift whenever a name went under the second row.
     */
    // 只用于"两排塞不进时钟下面时整体上抬"; 露出下一排时 topRowY 比 twoRowFit 还高,
    // 差值会变成负数把整块往下推, 第三排就被推出屏幕(焦点到第二排时不露了) -> 不往下抬
    val shelfLift = if (oneRow) 0.dp else (topRowY - twoRowFit).coerceAtLeast(0.dp)

    /** The rows on the shelf, over the plate: the top row, and in two-row mode the second too. */
    val shelfRows = if (oneRow) 1 else 2

    fun slot(index: Int, onTop: Boolean): Pair<Dp, Dp> {
        val row = index / columns
        val y = when {
            row == 1 && onTop && shelfRows > 1 -> topRowY + shelfSecond
            else -> topRowY + pitchY * row + if (row > 0 && onTop) firstGap else 0.dp
        }
        return Pair(margin + pitchX * (index % columns), y)
    }

    fun scrollFor(row: Int, rows: Int): Dp {
        if (row <= 0) return 0.dp
        val wanted = topRowY + pitchY * row - restY
        val bottom = topRowY + pitchY * rows + 36.dp - screenH
        return minOf(wanted, maxOf(bottom, topRowY + pitchY - restY))
    }
}

/** How long the screensaver waits for the wallpaper's film to move before playing its own. */
private const val FILM_SAVER_WAIT_MS = 10_000L

/** How long the large clock's time takes back from the screensaver's corner (ShelfClock waits for it). */
internal const val CLOCK_BACK_MS = 1000

/** How long Control Center takes to come in before a picture of Home stands in for it. */
private const val HOME_SHOT_AFTER_MS = 450L

/** How often that picture is taken again while Control Center stays open, for the clock. */
private const val HOME_SHOT_EVERY_MS = 30_000L

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onOpen: (Intent) -> Unit,
    onAppInfo: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onStartScreensaver: () -> Unit,
) {
    val apps = vm.apps
    val overlay = vm.overlay
    val moving = vm.movingKey
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val now by rememberNow()
    val scope = rememberCoroutineScope()

    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val pillRequester = remember { FocusRequester() }
    val appContext = LocalContext.current
    val firmwareInputPanel = remember {
        SystemProbe.tclInputPanel(appContext) != null || SystemProbe.coocaaInputPanel(appContext) != null
    }

    // 上一个浮层: 从二级菜单回来时, 焦点要落在直播卡片上(不是右上角控制中心)
    var lastOverlay by remember { mutableStateOf<Overlay?>(null) }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    // Where focus goes when the overlay closes, when that is not the tile it was on. Kept apart
    // from focusedKey: a closing menu hands focus to whatever tile lies under it first.
    var focusAfterOverlay by remember { mutableStateOf<String?>(null) }
    var indexAfterOverlay by remember { mutableStateOf(0) }
    var pillFocused by remember { mutableStateOf(false) }
    var lastFocusedIndex by remember { mutableStateOf(0) }
    val columns = AerialSettings.homeColumns
    // 用derivedStateOf避免每次重组都遍历apps列表
    val focusedIndex by remember(apps, focusedKey) {
        derivedStateOf { apps.indexOfFirst { it.key == focusedKey } }
    }
    val focusedRow by remember(apps, focusedKey, pillFocused, columns) {
        derivedStateOf {
            if (pillFocused || focusedIndex < 0) 0 else focusedIndex / columns
        }
    }
    val focusedPkg by remember(apps, focusedIndex) {
        derivedStateOf { apps.getOrNull(focusedIndex)?.packageName ?: "" }
    }
    // Kids mode's lock covers Home: nothing under it takes focus.
    val kidsLock = if (Restricted.active) Restricted.lock else null
    val gridEnabled = overlay == Overlay.None && kidsLock == null

// ===== 直播预览和UI隐藏状态 =====
    // liveReady/liveStatusTick 用 State 持有者暴露，而不是 by 委托：
    // 主 composable 不在 composition 阶段读它们的值，避免直播就绪/状态轮询
    // 这两个仅驱动 LaunchedEffect 的状态变化触发整棵 HomeScreen 树重组（低端盒子上明显卡顿）。
    
    val liveReadyState = remember { mutableStateOf(false) }
    var liveLoadingState = remember { mutableStateOf(false) }
    var liveMenuOpen by remember { mutableStateOf(false) }
    var liveMoving by remember { mutableStateOf(false) }
    // ccFromLiveMenu 已移到 ViewModel
    val liveStatusTickState = remember { mutableStateOf(0) }
    var lastKeyTime by remember { mutableStateOf(0L) }
    val channelPreviewVisible = AerialSettings.livePreviewEnabled &&
        focusedPkg in AerialSettings.liveAppPackages &&
        focusedRow == 0 &&
        !vm.screensaverVisible && moving == null
    val uiAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = when {
            overlay is Overlay.LiveSourceMenu || overlay is Overlay.ControlCenter || overlay is Overlay.Wallpaper -> 0f
            channelPreviewVisible && vm.uiHidden -> 0f
            else -> 1f
        },
        animationSpec = androidx.compose.animation.core.tween(UI_FADE_MS, easing = Motion.EaseInOut), label = "uiAlpha")
    LaunchedEffect(channelPreviewVisible) { vm.livePlaying = channelPreviewVisible; liveReadyState.value = false }
    // 焦点在IPTV卡片时立即检测一次直播状态, 然后每30秒刷新; 其他场景刷新后重置计时
    // 抽到独立 composable 里读 liveStatusTickState，tick 变化只重组这个小节点，不带动整棵树。
    LiveStatusPollingEffect(channelPreviewVisible, liveStatusTickState, vm)
    // 二级菜单打开时如果有未知状态的源, 也触发一次检测
    LaunchedEffect(overlay is Overlay.LiveSourceMenu) {
        if (overlay is Overlay.LiveSourceMenu) {
            val unknown = vm.getAllLiveSources().any { it.liveStatus == 0 }
            if (unknown) {
                DiagLog.i("LiveStatus: LiveSourceMenu opened, refreshing unknown sources")
                vm.checkLiveStatuses()
                liveStatusTickState.value++
            }
        }
    }
    // 10秒无操作自动隐藏UI（直播加载成功后才开始计时）
    // 抽到独立 composable 里读 liveReadyState，就绪 false→true 只重组这个小节点，不带动整棵树。
    LiveUiAutoHideEffect(
        focusedPkg = focusedPkg,
        overlay = overlay,
        uiHidden = vm.uiHidden,
        onUiHiddenChange = { vm.uiHidden = it },
        lastKeyTime = lastKeyTime,
        channelPreviewVisible = channelPreviewVisible,
        liveMenuOpen = liveMenuOpen,
        liveMoving = liveMoving,
        liveReadyState = liveReadyState,
        vm = vm,
    )
    // HOME键（onHomePressed）被按下时恢复UI显示
    LaunchedEffect(vm.homePressCount) {
        if (vm.homePressCount > 0 && vm.uiHidden) vm.uiHidden = false
    }
    // The aerial wallpaper's film, and how far it shows through.
    val filmCover = remember { Animatable(0f) }
    val liveFilm = remember { LiveFilm() }
    DisposableEffect(liveFilm) {
        LiveFilm.onHome = liveFilm
        onDispose { if (LiveFilm.onHome === liveFilm) LiveFilm.onHome = null }
    }
    // A screensaver of aerials over the aerial wallpaper carries the wallpaper's film on, one shot
    // from Home to screensaver: the apps fade, the clock moves to the corner. Whether the film is
    // moving already or still on its frame (Back on the top row), as long as Home shows it: on the
    // top row with nothing over it. A film that does not move within FILM_SAVER_WAIT_MS hands over
    // to the screensaver's own films.
    var filmFailed by remember(vm.screensaverVisible) { mutableStateOf(false) }
    val homeVideo = remember { HomeVideo() }
    val filmSaverChosen = remember(vm.screensaverVisible) {
        vm.screensaverVisible && Wallpapers.selectedId == Wallpapers.LIVE && Wallpapers.screensaver == ScreensaverMode.Aerial &&
            AerialFormats.screensaverCarriesWallpaper(appContext) &&
            (filmCover.value > 0.5f || focusedRow == 0 && overlay == Overlay.None && moving == null)
    }
    val filmSaver = filmSaverChosen && !filmFailed
    LaunchedEffect(filmSaverChosen) {
        if (!filmSaverChosen) return@LaunchedEffect
        val moved = withTimeoutOrNull(FILM_SAVER_WAIT_MS) { snapshotFlow { filmCover.value > 0.99f }.first { it } }
        if (moved == null) {
            DiagLog.i("wallpaper film did not move for the screensaver; its own films instead")
            filmFailed = true
        }
    }
    // The plate over a stopped film, once its glass is ready; out as the film moves again.
    val filmStopped by animateFloatAsState(
        if (liveFilm.stopped) 1f else 0f,
        tween(if (liveFilm.stopped) 250 else 900, easing = Motion.EaseOut), label = "film-stopped",
    )
    // A photo screensaver over a picture wallpaper carries the picture on the same way: its shading
    // lifts and it slowly draws in from just as Home shows it (HomeBackground), and the photos move
    // on from it.
    val stillSaver = remember(vm.screensaverVisible) {
        if (vm.screensaverVisible && focusedRow == 0 && overlay == Overlay.None && moving == null && Wallpapers.backdrop != null) {
            Wallpapers.screensaverPicture()
        } else null
    }
    val stillCarry = remember(stillSaver) { stillSaver?.let { SaverCarry.Still(it) } }
    // 二创: 视频壁纸进视频屏保时, 不另起一个播放器(那样会黑一下、卡一下),
    // 直接让 Home 的影片接着播, 只把一级菜单淡出——和直播菜单打开时一个思路。
    var videoFailed by remember(vm.screensaverVisible) { mutableStateOf(false) }
    val videoCarry = remember(vm.screensaverVisible) {
        vm.screensaverVisible && Wallpapers.isVideo() && Wallpapers.screensaver == ScreensaverMode.Videos &&
            focusedRow == 0 && overlay == Overlay.None && moving == null
    }
    val videoSaver = videoCarry && !videoFailed
    LaunchedEffect(videoCarry) {
        if (!videoCarry) return@LaunchedEffect
        // 影片没能接上(解码失败之类): 退回屏保自己的播放器, 别让屏幕空着
        val shown = withTimeoutOrNull(FILM_SAVER_WAIT_MS) { snapshotFlow { filmCover.value > 0.99f }.first { it } }
        if (shown == null) {
            DiagLog.i("video wallpaper did not carry on for the screensaver; its own films instead")
            videoFailed = true
        }
    }
    var saverImage by remember { mutableStateOf<ImageBitmap?>(null) }
    val saverReveal = remember { Animatable(0f) }
    val saverPush = remember { Animatable(0f) }
    LaunchedEffect(stillSaver) {
        if (stillSaver != null) {
            saverPush.snapTo(0f)
            saverImage = withContext(Dispatchers.Default) {
                Wallpapers.picture(stillSaver, Wallpapers.screenW, Wallpapers.screenH)?.asImageBitmap()
            } ?: return@LaunchedEffect
            coroutineScope {
                val pushing = if (reduced) null else launch { drawIn(saverPush) }
                launch { saverReveal.animateTo(1f, tween(1800, easing = Motion.EaseInOut)) }
                // Covered by the next photo: nothing of it shows, so it stops drawing.
                snapshotFlow { stillCarry?.covered == true }.first { it }
                pushing?.cancel()
                saverReveal.snapTo(0f)
                saverPush.snapTo(0f)
            }
        } else if (saverImage != null) {
            // Back to Home: the picture settles back as the shading returns.
            coroutineScope {
                launch { saverPush.animateTo(0f, tween(380, easing = Motion.EaseOut)) }
                saverReveal.animateTo(0f, tween(380, easing = Motion.EaseOut))
            }
            saverImage = null
        }
    }
    val carrying = filmSaver || stillSaver != null || videoSaver
    val toSaver = remember { Animatable(0f) }
    LaunchedEffect(carrying) {
        when {
            reduced -> toSaver.snapTo(if (carrying) 1f else 0f)
            carrying -> toSaver.animateTo(1f, tween(SAVER_FADE_MS, easing = Motion.EaseInOut))
            // A key woke it: Home comes back at once and settles in, the way it went. At 380 ms the
            // apps' part of it (60% of the way) was over in a blink and read as a cut (issue #357).
            else -> toSaver.animateTo(0f, tween(SAVER_FADE_MS, easing = Motion.EaseInOut))
        }
    }
    // The large clock's time goes back the way it came, at half the pace. On Home's 650 ms ease-out
    // it was most of the way back in two or three frames (Xiaomi MiTV-MFTR0: 45-57 ms a frame as it
    // wakes) and leapt; out of the corner and back are one motion run both ways.
    val clockToSaver = remember { Animatable(0f) }
    LaunchedEffect(carrying) {
        when {
            reduced -> clockToSaver.snapTo(if (carrying) 1f else 0f)
            carrying -> clockToSaver.animateTo(1f, tween(SAVER_FADE_MS, easing = Motion.EaseInOut))
            else -> clockToSaver.animateTo(0f, tween(SAVER_FADE_MS, easing = Motion.EaseInOut))
        }
    }

    /**
     * 屏保过渡那一整套动效的驱动值: 进屏保时是 [toSaver], 打开一级/二级菜单、隐藏 UI 时
     * 借用同一个值(1 - uiAlpha)。这样菜单的显隐不是单纯的透明度: 卡片逐列错峰淡出、
     * 往下沉一点、底板和时钟一起退场, 和"视频壁纸↔屏保"是同一套动作、同样 0.5 秒。
     */
    val overlayAmt = 1f - uiAlpha
    val saverLike = maxOf(toSaver.value, overlayAmt)

    // Grid scroll offset (px). The remote sets it from the focused row; a finger drags it freely
    // (issue #60), and then it stays where the finger left it until the next key press.
    val scroll = remember { Animatable(0f) }
    var dragged by remember { mutableStateOf(false) }
    val movingNow by rememberUpdatedState(moving)

    fun focusApp(index: Int) {
        val app = apps.getOrNull(index.coerceIn(0, (apps.size - 1).coerceAtLeast(0))) ?: return
        runCatching { requester(app.key).requestFocus() }
    }

    // 记住上一个浮层: 从二级菜单回到一级时要用它判断焦点落点
    LaunchedEffect(overlay) { if (overlay != Overlay.None) lastOverlay = overlay }

    // First focus once the apps are in; afterwards, put focus back wherever it was when an overlay closes.
    LaunchedEffect(vm.loaded, overlay) {
        if (!vm.loaded || overlay != Overlay.None) return@LaunchedEffect
        withFrameNanos { }
        val wanted = focusAfterOverlay
        focusAfterOverlay = null
        if (wanted != null) { focusedKey = wanted; pillFocused = false }
        // 从二级菜单回来、或者根本没有记住焦点: 焦点一律落在直播频道卡片上,
        // 不要落到右上角的控制中心按钮(以前会偶发/经常落在那里)
        val liveIndex = apps.indexOfFirst { it.packageName in AerialSettings.liveAppPackages }
        // 只有"从二级菜单回来"才强制落到直播卡片; 从控制中心回来要回到原来那张卡片
        if (lastOverlay is Overlay.LiveSourceMenu) {
            pillFocused = false
            val i = if (liveIndex >= 0) liveIndex else apps.indexOfFirst { it.key == focusedKey }
            if (i >= 0) { focusApp(i); focusedKey = apps[i].key }
            return@LaunchedEffect
        }
        // 兜底: 几帧之后如果焦点还是不在任何卡片上(落到控制中心), 拉回直播卡片
        repeat(3) { withFrameNanos { } }
        if (focusedKey == null && liveIndex >= 0) { focusApp(liveIndex); focusedKey = apps[liveIndex].key }
        val index = apps.indexOfFirst { it.key == focusedKey }
        // A tile that went while the overlay was up (hidden, put in a folder, a folder ungrouped)
        // leaves focus where it was.
        // 兜底: 没记住焦点(首次进入/卡片消失)时优先落在直播频道卡片上, 避免焦点跑到右上角控制中心
        val liveFallback = if (index < 0) apps.indexOfFirst { it.packageName in AerialSettings.liveAppPackages } else -1
        focusApp(when {
            index >= 0 -> index
            liveFallback >= 0 -> liveFallback
            wanted != null -> indexAfterOverlay
            else -> lastFocusedIndex
        })
    }
    // 二级菜单淡出结束后, 如果焦点还停在右上角控制中心, 拉回直播频道卡片
    LaunchedEffect(overlay, vm.loaded) {
        if (!vm.loaded || overlay != Overlay.None) return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        if (pillFocused && lastOverlay is Overlay.LiveSourceMenu) {
            val live = apps.indexOfFirst { it.packageName in AerialSettings.liveAppPackages }
            pillFocused = false
            if (live >= 0) focusApp(live)
        }
    }

    // 冷启动遮罩(SplashVeil): 等卡片和底板真的画出来了再揭开。两帧: 一帧让内容绘制,
    // 再一帧让壁纸/玻璃层的首帧也落地, 然后才把黑幕推开。
    LaunchedEffect(vm.loaded, apps.size) {
        if (!vm.loaded || apps.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        BootVeil.reveal()
    }

    // A folder whose last app left closes.
    LaunchedEffect(overlay, vm.folderApps) {
        val open = (overlay as? Overlay.Folder)?.id ?: (overlay as? Overlay.AppMenu)?.inFolder ?: return@LaunchedEffect
        if (vm.folderApps[open].isNullOrEmpty()) vm.overlay = Overlay.None
    }
    // An app vanished from under the focus (uninstalled or hidden): take its neighbour.
    LaunchedEffect(apps) {
        if (focusedKey != null && apps.none { it.key == focusedKey } && overlay == Overlay.None) {
            withFrameNanos { }
            focusApp(lastFocusedIndex)
        }
        // While moving, focus follows the app to its new slot.
        moving?.let { withFrameNanos { }; runCatching { requester(it).requestFocus() } }
    }
    // Moving starts from the menu, which is still on screen for a frame: focus the app once it is gone.
    LaunchedEffect(moving) {
        val key = moving ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester(key).requestFocus() }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(windowFocused, vm.homeFocusTarget, vm.loaded, apps.size) {
        if (windowFocused && vm.loaded && vm.homeFocusTarget >= -2 && vm.homeFocusTarget != -1) {
            // 等两帧: 避开HOME键onPause/onResume生命周期切换, 确保焦点请求在稳定窗口执行
            withFrameNanos { }
            withFrameNanos { }
            dragged = false
            val target = vm.homeFocusTarget
            when {
                target == 0 -> {
                    scroll.snapTo(0f)
                    focusApp(0)
                }
                target == -2 -> {
                    val liveIdx = apps.indexOfFirst { it.packageName in AerialSettings.liveAppPackages }
                    focusApp(if (liveIdx >= 0) liveIdx else 0)
                }
            }
            vm.homeFocusTarget = -1
            initialFocusDone = true
        } else if (windowFocused && vm.loaded && apps.isNotEmpty() && !initialFocusDone) {
            // 启动时直接聚焦第一个卡片, 不要先聚焦右上角pill再跳过去
            withFrameNanos { }
            focusApp(0)
            initialFocusDone = true
        }
    }

    BackHandler(enabled = overlay == Overlay.None && moving == null && !vm.uiHidden) {
        // tvOS: Back from inside the grid returns to the top row; Back on the top row starts the
        // screensaver. A grid scrolled by touch counts as being inside it.
        val scrolled = dragged && scroll.value > 1f
        dragged = false
        // Handled even when that is turned off: unhandled, Back would close Home itself.
        if (focusedRow > 0 || scrolled) focusApp(focusedIndex.coerceAtLeast(0) % columns)
        else if (AerialSettings.backStartsScreensaver) onStartScreensaver()
    }

    // ===== 手动快速隐藏UI：10秒内按返回键立刻藏 =====
    BackHandler(enabled = channelPreviewVisible && focusedPkg in AerialSettings.liveAppPackages && !vm.uiHidden && overlay == Overlay.None && moving == null) {
        vm.uiHidden = true
    }

    // 二级菜单返回键关闭，回到纯直播画面（不显示一级UI）
    BackHandler(enabled = overlay is Overlay.LiveSourceMenu) {
        DiagLog.i("CC-debug: LiveMenu BackHandler closing to None")
        vm.overlay = Overlay.None
        vm.uiHidden = true // 关闭二级菜单后保持UI隐藏
    }

    BackHandler(enabled = channelPreviewVisible && vm.uiHidden && overlay == Overlay.None && focusedPkg in AerialSettings.liveAppPackages) {
        vm.uiHidden = false
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                lastKeyTime = System.currentTimeMillis()
                // ===== UI隐藏时按键逻辑 =====
                if (channelPreviewVisible && vm.uiHidden && focusedPkg in AerialSettings.liveAppPackages) {
                    // 任何浮层(CC/个性化/隐藏应用等)都透传，不能被"恢复UI"逻辑吞掉第一个键
                    if (overlay !is Overlay.None && overlay !is Overlay.LiveSourceMenu) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) {
                        when (e.nativeKeyEvent.keyCode) {
                            // 菜单键：二级菜单没开就开，开了就透传给卡片呼出上下文菜单
                            NativeKeyEvent.KEYCODE_MENU -> {
                                if (overlay is Overlay.LiveSourceMenu) return@onPreviewKeyEvent false
                                vm.overlay = Overlay.LiveSourceMenu
                            }
                            // 返回键：二级菜单开着就透传给BackHandler关菜单，没开才恢复UI
                            NativeKeyEvent.KEYCODE_BACK -> {
                                if (overlay is Overlay.LiveSourceMenu) return@onPreviewKeyEvent false
                                vm.uiHidden = false
                            }
                            // 音量键透传
                            NativeKeyEvent.KEYCODE_VOLUME_UP, NativeKeyEvent.KEYCODE_VOLUME_DOWN,
                            NativeKeyEvent.KEYCODE_VOLUME_MUTE -> return@onPreviewKeyEvent false
                            // 主页键：回到一级菜单
                            NativeKeyEvent.KEYCODE_HOME -> {
                                if (overlay is Overlay.LiveSourceMenu) vm.overlay = Overlay.None
                                vm.uiHidden = false
                            }
                            // 方向键/确认键：二级菜单开着就透传给卡片，没开才恢复UI
                            else -> {
                                if (overlay is Overlay.LiveSourceMenu) return@onPreviewKeyEvent false
                                vm.uiHidden = false
                            }
                        }
                    } else {
                        // KeyUp：二级菜单开着时透传给卡片（确定键需要KeyUp触发）
                        if (overlay is Overlay.LiveSourceMenu) return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }

                // The remote takes the grid back from the finger: scroll follows focus again.
                // Back leaves it to BackHandler, which needs to know the grid was dragged
                // (gesture navigation sends Back as a key too).
                if (e.nativeKeyEvent.keyCode != NativeKeyEvent.KEYCODE_BACK) dragged = false
                // Moving takes the remote here rather than on the moving tile: the tile does not
                // always get focus back when the menu closes, and focus then landed on the first
                // tile, which moved focus instead of the app (#58).
                if (moving != null) {
                    val code = e.nativeKeyEvent.keyCode
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent code != NativeKeyEvent.KEYCODE_HOME
                    when (code) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> vm.moveBy(-1)
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> vm.moveBy(1)
                        NativeKeyEvent.KEYCODE_DPAD_UP -> vm.moveBy(-columns)
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> vm.moveBy(columns)
                        NativeKeyEvent.KEYCODE_BACK, NativeKeyEvent.KEYCODE_DPAD_CENTER,
                        NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> vm.endMove()
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_MENU -> {
                        if (kidsLock != null) return@onPreviewKeyEvent true
                        // On an app tile, Menu opens that app's menu (AppTile): remotes whose held
                        // OK never reads as held had no other way to it (issue #28). Control
                        // Center stays one press up, on the status pill, where Menu still opens it.
                        if (overlay == Overlay.None && !pillFocused && focusedIndex >= 0) return@onPreviewKeyEvent false
                        // Inside a folder, Menu is the focused app's, as on Home.
                        if (overlay is Overlay.Folder) return@onPreviewKeyEvent false
                        vm.overlay = if (overlay is Overlay.ControlCenter) {
                            if (liveMenuOpen) Overlay.LiveSourceMenu else Overlay.None
                        } else Overlay.ControlCenter()
                        true
                    }
                    NativeKeyEvent.KEYCODE_SETTINGS -> {
                        // The remote's gear key reaches Home where no firmware answers it: a stock
                        // Android TV launcher opens the settings on it, and with Aerial as Home it did
                        // nothing (Riviera RLED-GLT32TPQ10, MediaTek, Android 14, issue #1190).
                        // Kids and Elders mode keeps the settings shut, as Control Center does.
                        if (kidsLock != null || Restricted.active) return@onPreviewKeyEvent true
                        vm.whenUnlocked {
                            LaunchFallback.arm(SystemProbe.settingsFallbacks(appContext))
                            onOpen(SystemProbe.settingsIntent(appContext))
                        }
                        true
                    }
                    NativeKeyEvent.KEYCODE_TV_INPUT -> {
                        // Kids and Elders mode switches only to the inputs it shows, as tiles.
                        if (Restricted.active && !firmwareInputPanel) return@onPreviewKeyEvent true
                        // TCL's firmware opens its own input panel on this key; answering it here
                        // as well drew both panels at once (TCL MT5879, PR #334).
                        if (firmwareInputPanel) return@onPreviewKeyEvent false
                        vm.overlay = Overlay.ControlCenter(CcPage.Inputs)
                        true
                    }
                    else -> false
                }
            },
    ) {
        val rows = (apps.size + columns - 1) / columns
        // With a single row of apps the two-row layout would leave the bottom of the screen empty.
        val oneRow = AerialSettings.oneRowHome || rows <= 1
        // In two rows the second is on screen already: down to it stays on the shelf, under the
        // same picture and plate, and only the row after it goes into the grid (issue #633: the
        // grid's frosted picture flashed up on the way to an app in the second row).
        val inGrid = focusedRow >= if (oneRow) 1 else 2
        // With every name shown, the top row's go on its glass plate, which grows to hold them.
        val plateNames = AerialSettings.showAllNames && AerialSettings.homePlate
        val peek = AerialSettings.peekNextRow
        val hasPlate = AerialSettings.homePlate
        val largeClock = AerialSettings.largeClock
        val clockRightNow = AerialSettings.clockRight
        val metrics = remember(maxWidth, maxHeight, columns, oneRow, plateNames, peek, hasPlate, largeClock, clockRightNow) {
            GridMetrics(maxWidth, maxHeight, columns, oneRow, plateNames, peek, hasPlate, largeClock, clockRightNow)
        }
        val collapsePx = with(density) { metrics.collapse.toPx() }

        // 上游 v1.75 修复：两行书架时滚动距离少一行，避免第一行被 pill 切掉一半
        val target = with(density) { metrics.scrollFor(if (inGrid) focusedRow - (metrics.shelfRows - 1) else 0, rows).toPx() }
        // shelf lift 动画值不用 by 委托：它只在 offset {} 布局阶段读取，
        // 动画期间（spring ~300ms）不应每帧重组整个 BoxWithConstraints 内容树，
        // 而是触发 relayout（和 scroll 一样，只重排不重组）。
        val liftState = animateFloatAsState(
            with(density) { if (focusedRow > 0 && !inGrid) metrics.shelfLift.toPx() else 0f },
            if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 280f), label = "shelf-lift",
        )
        val maxScroll = with(density) { metrics.scrollFor(rows - 1, rows).toPx() }.coerceAtLeast(0f)
        LaunchedEffect(maxScroll) { scroll.updateBounds(0f, maxScroll) }
        val pastShelf by remember(collapsePx) { derivedStateOf { scroll.value > collapsePx / 2 } }
        val decay = rememberSplineBasedDecay<Float>()
        LaunchedEffect(target, dragged) {
            if (dragged) return@LaunchedEffect
            if (reduced) scroll.snapTo(target)
            // A spring keeps its velocity when a quick second press retargets it mid-flight. At 170
            // a row took about 300 ms to get 90% of the way and half a second to settle.
            else scroll.animateTo(target, spring(dampingRatio = 1f, stiffness = 280f))
        }
// The folder open, or under its app's menu.
        val openFolder = (overlay as? Overlay.Folder)?.id ?: (overlay as? Overlay.AppMenu)?.inFolder
        val folderOpen = overlay is Overlay.HiddenApps || overlay is Overlay.HiddenLiveSources || openFolder != null
        val ccOpen = overlay is Overlay.ControlCenter
        var sheetIn by remember { mutableStateOf(false) }
        var sheetWarm by remember { mutableStateOf(false) }
        // Folders open on Home frosted, as on tvOS; Control Center keeps Home sharp, dimmed.
        val backdropFrost by animateFloatAsState(
            if (folderOpen) 1f else 0f,
            tween(320, easing = Motion.EaseOut), label = "backdrop-frost",
        )
        val backdropVeil by animateFloatAsState(
            if (ccOpen) 1f else 0f,
            tween(320, easing = Motion.EaseOut), label = "backdrop-veil",
        )
        // With Control Center open, Home is still: once the panel is in, a picture of it stands in
        // for it, one full-screen image a frame instead of every tile again under the panel
        // (MiTV-MFTR0: GPU 18-21 ms a frame drawing Home under it, as against 9-15 ms frosted).
        // Taken again now and then for the clock. Not before Android 7: on Android 6 the picture's
        // ImageReader surface leaves a RenderNode that the finalizer thread later frees together
        // with GPU layers, and hwui aborts with "Wrong thread!" (MiTV4-ANSM0, Android 6.0.1,
        // issue #466: the reporter rebuilt the same steps in a small app and got the same 16 frames).
        val homeLayer = rememberGraphicsLayer()
        var homeShot by remember { mutableStateOf<ImageBitmap?>(null) }
        val sourceInput = Wallpapers.sourceInput
        LaunchedEffect(ccOpen, sourceInput) {
            homeShot = null
            if (!ccOpen || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@LaunchedEffect
            // CC打开时不截图，直接显示实时视频(无论直播透流还是动态壁纸)
            return@LaunchedEffect
        }

        val ambientApp = apps.getOrNull(focusedIndex) ?: apps.firstOrNull()

        val dragState = rememberDraggableState { delta ->
            dragged = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) { scroll.snapTo(scroll.value - delta) }
        }
        // 上游 v1.75：移动壁纸的影片渲染（从 Box 内挪到 Box 外，在最底层绘制）
        // 个性化面板打开时壁纸照旧播: 面板是模糊的, 后面停一张图反而明显 (owner, 2026-11-11)
        // 上下文菜单(长按卡片)只是压在 Home 上的一个小面板, 壁纸照旧播
        val filmMoves = vm.filmFront && (overlay == Overlay.None || overlay is Overlay.ControlCenter ||
            overlay is Overlay.Wallpaper || overlay is Overlay.AppMenu || overlay is Overlay.Folder) &&
            (!vm.screensaverVisible || videoSaver) && !pastShelf && !inGrid && moving == null &&
            // 直播画面真的放出来之后才停壁纸影片: 否则视频壁纸的影片层会压住直播画面;
            // 直播还在加载(或没源)的时候壁纸照旧播, 不能因为停在直播卡片上就冻住
            !(channelPreviewVisible && liveReadyState.value)
        // Nothing over Home blurs it: a paused film stays.
        val filmStandsIn = overlay == Overlay.None || overlay is Overlay.ControlCenter || overlay is Overlay.Wallpaper ||
            (overlay is Overlay.AppMenu && overlay.inFolder == null)
        when {
            Wallpapers.selectedId == Wallpapers.LIVE -> LiveWallpaper(
                moving = filmMoves,
                keep = vm.filmFront && (!vm.screensaverVisible || filmSaver),
                cover = filmCover,
                hold = filmSaver && vm.filmFront,
                standIn = filmStandsIn,
                panel = overlay != Overlay.None,
                otherFormatSaver = vm.screensaverVisible && !filmSaver && Wallpapers.screensaver == ScreensaverMode.Aerial &&
                    !AerialFormats.screensaverCarriesWallpaper(appContext),
                lastInputAt = { vm.lastInputAt },
                live = liveFilm,
            )
            Wallpapers.isVideo() -> key(Wallpapers.selectedId) {
                VideoWallpaper(
                    moving = filmMoves,
                    keep = vm.filmFront && (!vm.screensaverVisible || filmSaver || videoSaver),
                    hold = (filmSaver || videoSaver) && vm.filmFront,
                    home = homeVideo,
                    standIn = filmStandsIn,
                    lastInputAt = vm.lastInputAt,
                    cover = filmCover,
                )
            }
            sourceInput != null -> SourceWallpaper(sourceInput, show = vm.resumed && !vm.screensaverVisible, cover = filmCover)
        }
        // 二创保留：Control Center 打开时用 home 快照 + 磨砂面板替代实时 Home
        homeShot?.let { shot -> Canvas(Modifier.fillMaxSize()) { drawImage(shot) } }
        PanelBackdrop(Wallpapers.frosted.takeIf { Wallpapers.backdrop != null }, { backdropFrost }, { backdropVeil })
        Box(
            Modifier
                .fillMaxSize()
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    enabled = gridEnabled && rows > 1,
                    onDragStopped = { velocity ->
                        if (!reduced) scroll.animateDecay(-velocity, decay)
                    },
                )
                // While moving an app, a tap on empty space ends the move, as OK does.
                .pointerInput(Unit) { detectTapGestures { if (movingNow != null) vm.endMove() } }
                .graphicsLayer {
                    alpha = if (backdropFrost > 0.999f || homeShot != null) 0f else 1f
                    // UI隐藏时整个桌面所有元素都透明，只留直播画面
                }
                // Recorded while Control Center is open, for the picture that stands in for Home.
                .drawWithContent {
                    if (ccOpen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        homeLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(homeLayer)
                    } else if (sheetIn) {
                        // Under Personalize's settled sheet, only what shows above it.
                        clipRect(bottom = size.height - SheetCovers.toPx()) { this@drawWithContent.drawContent() }
                    } else drawContent()
                },
        ) {
            // The glass plate under the top row (tvOS 26), the row's full width however few apps
            // there are, which sit at its left: sized to three apps it read as a stray panel (#284).
            // In two rows it holds both: under the top row alone, the second sat beside it on the
            // bare wallpaper, as if left off the shelf.
            // 控制中心和个性化(壁纸)打开时, 玻璃底板不画出来, 免得面板后面还露着一层。
            val plateHidden = overlay is Overlay.ControlCenter || sheetIn
            // 底板的位置照常算出来: 打开控制中心/个性化时不再直接"去掉它", 而是让它跟着
            // uiAlpha 淡出(和二级菜单打开控制中心时一样有过渡), 恢复时同样淡回来。
            val plateRect = if (apps.isEmpty() || !AerialSettings.homePlate) null else with(density) {
                val second = if (metrics.shelfRows > 1) metrics.shelfSecond else 0.dp
                Rect(
                    (metrics.margin - PlatePadH).toPx(),
                    (metrics.topRowY - PlatePadV).toPx(),
                    (metrics.margin + metrics.pitchX * (columns - 1) + metrics.tileW + PlatePadH).toPx(),
                    (metrics.topRowY + metrics.tileH + PlatePadV + metrics.firstGap + second).toPx(),
                )
            }
            val plate = if (!plateHidden) plateRect else null
            // Another wallpaper chosen: nothing shows through until the new one has a frame.
            LaunchedEffect(Wallpapers.selectedId) { filmCover.snapTo(0f) }
            // The aerial film's own glass for the grid: the one playing or stopped, else the one
            // the picture was taken from.
            val pictureClip = remember(Wallpapers.backdrop) { Wallpapers.livePictureSpot()?.first }
            val clip = if (Wallpapers.selectedId == Wallpapers.LIVE) liveFilm.film?.id ?: pictureClip else null
            LaunchedEffect(clip) { Wallpapers.showClipGlass(clip) }
            val clipGlass = Wallpapers.clipGlass?.takeIf { it.first == clip }?.second
            HomeBackground(
                // The chosen wallpaper; in "Follow App" mode, light from the focused app.
                backdrop = Wallpapers.backdrop ?: ambientApp?.art?.backdrop,
                frosted = Wallpapers.frosted.takeIf { Wallpapers.backdrop != null },
                plate = plate,
                scroll = { scroll.value },
                collapse = collapsePx,
                // 只有壁纸本身是视频时, 直播预览才"接管"影片层(遮住壁纸的影片);
                // 静态壁纸没有影片要接管: 永远 0, 既不遮壁纸, 底板也不会因为 cover 被算成看不见。
                cover = { if (!Wallpapers.moves()) 0f else if (channelPreviewVisible) 1f else filmCover.value },
                // An input's picture gets the shade a picture has; in the grid it gives way to the
                // glass, as any wallpaper does (it cannot be blurred). Not a film, moving or not
                // (the owner, 2026-10-29): since the aerial wallpaper keeps moving under keys
                // (Wallpapers.keepsMoving) it was still shaded here, a full-screen draw each frame
                // (Xiaomi MiTV-MFTR0: GPU 9.8 -> 6.9 ms a frame moving focus over the film).
                shadeMoving = sourceInput != null,
                filmStopped = { filmStopped },
                toSaver = { saverLike },
                filmGlass = (Wallpapers.filmFrost ?: Wallpapers.frosted).takeIf { Wallpapers.moves() && Wallpapers.backdrop != null },
                saverImage = saverImage,
                saverReveal = { saverReveal.value },
                saverPush = { saverPush.value },
                lift = { liftState.value },
                wallpaper = Wallpapers.selectedId,
                glassAwaitsStop = Wallpapers.selectedId == Wallpapers.LIVE && clipGlass == null,
                gridGlass = clipGlass,
                plateOverVideo = channelPreviewVisible,
                // 底板统一由预览之后那一层画 (压在直播画面上, 且跟着 UI 一起隐藏)
                drawPlate = false,
            )

            // Before Android 7 there is no picture of Home under Control Center (issue #466), so the
            // GPU draws every tile again under the panel each frame. 上游 v1.75 修复 #823：
            // Lite 下不再让时钟/图标在面板后淡出（否则 Home 看起来消失了）。
            // ===== 二创：直播预览，只在焦点选中第一排目标应用时组合 =====
            // 用 remember 缓存当前源的查找结果：HomeScreen 因 now 分钟 tick / 焦点变化等
            // 重组时，不必每次都重新遍历 liveSources 列表找 firstOrNull。
            val previewSource = remember(channelPreviewVisible, vm.liveSources, vm.pendingLiveSourceId, vm.currentLiveSourceId) {
                if (channelPreviewVisible) {
                    vm.liveSources.firstOrNull {
                        it.id == (vm.pendingLiveSourceId ?: vm.currentLiveSourceId)
                    }
                } else null
            }
            if (channelPreviewVisible) {
                ChannelFocusPreview(
                    source = previewSource,
                    onFallback = {
                        val fb = vm.liveSources.find { it.id == "preset_2" } ?: vm.liveSources.firstOrNull()
                        fb?.let { vm.pickLiveSource(it.id) }
                        fb?.name
                    },
                    onRevert = { oldId ->
                        // 新源失败: 丢弃挂起, 选中态和高亮留在旧源(本来就在播)
                        vm.cancelPendingLiveSource()
                        null
                    },
                    onReadyChange = { liveReadyState.value = it },
                    onLoadingChange = { liveLoadingState.value = it },
                    onStatusRefresh = { vm.checkLiveStatuses(); liveStatusTickState.value++ },
                    onSwitched = { vm.commitPendingLiveSource() },
                    onRevertFail = {
                        // 新源失败回退到旧源时, 自动隐藏的 UI 可能还躲着, 把高亮拉回来
                        vm.uiHidden = false
                        focusApp(lastFocusedIndex)
                    },
                    onActuallyPlayingChange = { vm.liveActuallyPlaying = it },
                )
            }
            // 静态壁纸: 直播画面还没出帧时 SurfaceView 是黑的, 先盖一层壁纸,
            // 出帧后再淡出 —— 观感是"直播画面直接覆盖在壁纸上", 不会黑一下。
            if (channelPreviewVisible && !Wallpapers.moves()) {
                val still = Wallpapers.backdrop ?: Wallpapers.frosted
                val ready by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (liveReadyState.value) 1f else 0f,
                    animationSpec = androidx.compose.animation.core.tween(320),
                    label = "previewStillCover",
                )
                if (still != null && ready < 0.999f) {
                    Canvas(Modifier.fillMaxSize().diagLayer("preview-still")) {
                        drawImage(
                            still,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            alpha = 1f - ready,
                            filterQuality = FilterQuality.Low,
                        )
                    }
                }
            }
            // 直播加载图: 盖在壁纸和直播预览影片之上, 出帧后淡出。
            // 抽成小节点读 liveReadyState, 就绪翻转只重组这一层, 不带动整棵树。
            LiveLoadingOverlay(channelPreviewVisible, liveLoadingState)

            // 底板: 只有这一处, 排在直播画面之后(压得住它), 又在 UI 内容之下;
            // 单实例 + 跟着 uiAlpha 走, 焦点在普通卡片和直播预览卡片之间移动时不会闪,
            // 隐藏 UI 时也会跟着一起消失。
            // 直播画面是否已经真的显示(出帧)出来
            val overLive = channelPreviewVisible && liveReadyState.value
            if (plateRect != null) {
                // 静态壁纸: 底板照旧从壁纸取色(磨砂帧那一块)。
                // 视频壁纸: 底板用二级菜单那块中性灰(不带影片/壁纸的颜色)。
                val videoWall = Wallpapers.selectedId == Wallpapers.LIVE ||
                    Wallpapers.selectedId.startsWith(Wallpapers.VIDEO_PREFIX)
                val plateFrost = Wallpapers.frosted.takeIf { Wallpapers.backdrop != null }
                val greyGlass = remember(Wallpapers.backdrop) { frostGreyGlass() }
                // 跟着 uiAlpha 一起走: 隐藏 UI / 打开浮层时和卡片同时淡出, 不会单独留在屏幕上
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = uiAlpha }) {
                    HomePlate(
                        // 压在直播画面上时, 底板要变成半透明的玻璃(磨砂+烟灰+亮边),
                        // 不管壁纸本身是静态还是视频, 不能盖一层不透明的壁纸板
                        // 静态壁纸切到直播预览时不换玻璃图: 沿用同一张磨砂片, 避免底板闪一下;
                        // 只在上面叠烟灰和亮边(半透明地压在直播画面上)。
                        glass = if (videoWall) greyGlass else (plateFrost ?: greyGlass),
                        // 压在直播画面上时磨砂片必须是中性灰, 不能是壁纸色。
                        // 静态壁纸: 等直播画面真的出来(overLive)再切, 画面没换之前底板不先变。
                        film = if (videoWall || overLive) greyGlass else (plateFrost ?: greyGlass),
                        filmPlate = videoWall || overLive,
                        deepSmoke = false,
                        stillOverFilm = false,
                        plate = plateRect,
                        scroll = { scroll.value },
                        collapse = collapsePx,
                        cover = { if (!Wallpapers.moves()) 0f else if (channelPreviewVisible) 1f else filmCover.value },
                        filmStopped = { filmStopped },
                        toSaver = { saverLike },
                        lift = { liftState.value },
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, -scroll.value.roundToInt()) },
                // 淡出不在这里整层做: 每张卡片按屏保那一套逐列错峰淡出、下沉(见 saverFade)
            ) {
                val clockRight = AerialSettings.clockRight
                // The large clock stands over the top row; the small one is the time and weather in
                // one line, top left or beside the pill, so the wallpaper keeps the screen.
                if (AerialSettings.largeClock) {
                    ShelfClock(
                        now = now,
                        alignEnd = clockRight,
                        modifier = Modifier
                            .diagLayer("clock")
                            .align(if (clockRight) Alignment.TopEnd else Alignment.TopStart)
                            .padding(horizontal = metrics.margin)
                            .padding(top = metrics.clockY)
                            // No offscreen buffer while it fades: it would be the clock's capBox, and the
                            // Chinese, the icon and the shadows above and below it were cut off (#727).
                            .graphicsLayer {
                                alpha = 1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f)
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                        toSaver = { maxOf(clockToSaver.value, overlayAmt) },
                        saving = vm.screensaverVisible,
                        away = carrying,
                    )
                } else if (!clockRight) {
                    CornerClock(
                        now = now,
                        modifier = Modifier
                            .diagLayer("clock")
                            .padding(start = metrics.margin, top = 28.dp)
                            .graphicsLayer {
                                alpha = 1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f)
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                        toSaver = { saverLike },
                        saving = vm.screensaverVisible,
                    )
                }

                // The rows below the shelf stay out of sight until Home scrolls to them: in one row
                // unless set to peek; in two rows always, as the second row already shows there are more.
                val hiddenFrom = when {
                    // 双排 + "下一排": 第三排也要组合出来, 否则底部没有卡片可露
                    !oneRow -> if (peek) columns * 3 else columns * 2
                    !peek -> columns
                    else -> Int.MAX_VALUE
                }
                apps.forEachIndexed { index, app ->
                    key(app.key) {
                        val (x, y) = metrics.slot(index, onTop = !inGrid)
                        val slotPx = with(density) { IntOffset(x.roundToPx(), y.roundToPx()) }
                        val slot = animateIntOffsetAsState(
                            slotPx,
                            spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                            label = "slot",
                        )
                        val saverFade = { ((saverLike - 0.05f * (index % columns)) / 0.6f).coerceIn(0f, 1f) }
                        AppTile(
                            app = app,
                            width = metrics.tileW,
                            height = metrics.tileH,
                            focusRequester = requester(app.key),
                            enabled = gridEnabled,
                            moving = moving == app.key,
                            opacity = { 1f - saverFade() },
                            onFocused = {
                                focusedKey = app.key
                                lastFocusedIndex = index
                                pillFocused = false
                            },
                            onClick = { bounds ->
                                val mover = moving
                                if (mover == null) {
                                    val folder = app.folder
                                    if (folder == null) onLaunch(app, bounds)
                                    else {
                                        // Closing the folder comes back to its tile.
                                        focusAfterOverlay = app.key
                                        indexAfterOverlay = index
                                        vm.overlay = Overlay.Folder(folder.id)
                                    }
                                }
                                // While moving, tapping a tile puts the app there; tapping the app
                                // itself is done.
                                else if (mover == app.key) vm.endMove()
                                else {
                                    vm.moveBy(index - apps.indexOfFirst { it.key == mover })
                                    runCatching { requester(mover).requestFocus() }
                                }
                            },
                            onLongPress = { bounds ->
                                if (moving == null && !vm.uiHidden && !Restricted.active) {
                                    focusAfterOverlay = app.key
                                    indexAfterOverlay = index
                                    vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero)
                                }
                            },
                            modifier = Modifier
                                .diagLayer("tiles")
                                // Lifted on the shelf with the plate; the clock above stays put.
                                .offset { slot.value - IntOffset(0, liftState.value.roundToInt()) }
                                // Into the screensaver, the apps go left to right, sinking a little.
                                // Opacity lives in the tile's own layers: another layer around it dropped
                                // the focus zoom and rounded clip on Android 9 (issue #725).
                                .offset { IntOffset(0, (saverFade() * 18.dp.toPx()).roundToInt()) }
                                .then(
                                    if (index >= hiddenFrom) Modifier.graphicsLayer {
                                        alpha = (scroll.value / collapsePx * 2.5f).coerceIn(0f, 1f)
                                        // No offscreen buffer: it would clip the focus frame to the tile.
                                        compositingStrategy = CompositingStrategy.ModulateAlpha
                                    } else Modifier,
                                ),
                            rowEnd = index % columns == columns - 1 || index == apps.lastIndex,
                            // On the glass plate only with every name shown (an earlier build showed
                            // the focused one alone there, but it sat oddly on the glass).
                            showName = plate == null || index >= columns * metrics.shelfRows || plateNames,
                            nameOnPlate = plate != null && index < columns * metrics.shelfRows,
                        )
                    }
                }
            }
        }



        // The pill holds only the network and Control Center. The time joins it once the clock
        // above the top row has scrolled away, so the screen never shows the time twice. The small
        // clock on the right puts the time and weather just left of the pill.
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = metrics.margin - 14.dp)
                .graphicsLayer {
                    alpha = when {
                        overlay is Overlay.ControlCenter -> 0f
                        channelPreviewVisible && vm.uiHidden && focusedPkg in AerialSettings.liveAppPackages -> 0f
                        else -> 1f
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!AerialSettings.largeClock && AerialSettings.clockRight) {
                CornerClock(
                    now = now,
                    modifier = Modifier
                        .diagLayer("clock")
                        .padding(end = 14.dp)
                        .graphicsLayer {
                            alpha = 1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                    toSaver = { saverLike },
                    saving = vm.screensaverVisible,
                )
            }
            StatusPill(
                now = now,
                network = vm.network,
                showTime = if (dragged) pastShelf else inGrid,
                enabled = gridEnabled && moving == null && initialFocusDone,
                focusRequester = pillRequester,
                modifier = Modifier
                    .diagLayer("pill")
                    // Down goes back to the top-row tile Up came from, not to the nearest one,
                    // which under the pill is the last of the row (issue #703).
                    .focusProperties {
                        val key = focusedKey
                        if (key != null && apps.indexOfFirst { it.key == key } in 0 until columns) down = requester(key)
                    }
                    .graphicsLayer {
                        alpha = 1f - (saverLike * 2f).coerceIn(0f, 1f)
                        // No offscreen buffer: it would cut the focused pill's 8% growth to a box.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                onFocused = { gained ->
                    DiagLog.i("LiveMenu-debug: home pill onFocused gained=$gained focusedKey=$focusedKey overlay=$overlay")
                    // The window hands its first focus to the top-most control; the top row is where Home starts.
                    if (gained) {
                        if (focusedKey == null) {
                            if (apps.isNotEmpty()) focusApp(0)
                        } else {
                            pillFocused = true
                        }
                    }
                },
                onClick = {
                    // A tap focuses the pill and opens Control Center in the same frame, before
                    // onFocused hears of it: note it here so focus comes back to the pill.
                    pillFocused = true
                    vm.overlay = Overlay.ControlCenter()
                },
            )
        }

        AnimatedVisibility(
            visible = moving != null,
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + slideInVertically(tween(260, easing = Motion.EaseOut)) { it / 2 },
            exit = fadeOut(tween(150, easing = Motion.EaseOut)) + slideOutVertically(tween(150, easing = Motion.EaseOut)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        ) {
            // Screens that can be touched also say how to move with a finger.
            val touch = LocalConfiguration.current.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH
            BasicText(
                stringResource(if (touch) R.string.move_hint_touch else R.string.move_hint),
                style = Type.Body,
                modifier = Modifier
                    .liquidGlass(20.dp)
                    .height(40.dp)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        // Once per process, while Home is idle: Control Center, then Personalize, drawn for a few
        // frames, nearly transparent, so that their first opening is not their first draw (see
        // ControlCenter, WallpaperSheet).
        var ccWarm by remember { mutableStateOf(false) }
        LaunchedEffect(vm.loaded) {
            if (!vm.loaded || ControlCenterWarmed.done) return@LaunchedEffect
            while (true) {
                delay(CC_WARM_AFTER_MS)
                val idle = SystemClock.uptimeMillis() - vm.lastInputAt > CC_WARM_AFTER_MS
                // 允许在LiveSourceMenu打开时也预热CC：CC面板在右上角1%透明度，
                // 不会干扰下半屏的卡片；从二级菜单打开CC时就不需要冷启动619ms了
                if (idle && (vm.overlay == Overlay.None || vm.overlay is Overlay.LiveSourceMenu) && !vm.screensaverVisible && vm.resumed) break
            }
            ControlCenterWarmed.done = true
            ccWarm = true
            repeat(3) { withFrameNanos { } }
            ccWarm = false
            // A frame apart, so that the two first draws do not land in the same frame.
            withFrameNanos { }
            if (vm.overlay != Overlay.None) return@LaunchedEffect
            sheetWarm = true
            repeat(3) { withFrameNanos { } }
            sheetWarm = false
        }

        FolderView(
            name = openFolder?.let { id -> apps.firstOrNull { it.folder?.id == id }?.label },
            apps = openFolder?.let { vm.folderApps[it] }.orEmpty(),
            visible = openFolder != null,
            enabled = overlay is Overlay.Folder,
            tileW = metrics.tileW,
            tileH = metrics.tileH,
            gap = metrics.gap,
            columns = columns,
            onLaunch = onLaunch,
            onLongPress = { app, bounds -> vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero, inFolder = openFolder) },
            onDismiss = { vm.overlay = Overlay.None },
        )

        val menu = overlay as? Overlay.AppMenu
        // A menu opened in a folder closes back to the folder.
        fun closeMenu() { vm.overlay = menu?.inFolder?.let { Overlay.Folder(it) } ?: Overlay.None }
        AppMenu(
            app = menu?.let { vm.entry(it.key) },
            anchor = menu?.anchor,
            inFolder = menu?.inFolder != null,
            folders = vm.folders(),
            onMove = { vm.startMove(it) },
            onHide = { key ->
                closeMenu()
                vm.hide(key)
            },
            onInfo = { closeMenu(); onAppInfo(it) },
            onUninstall = { closeMenu(); onUninstall(it) },
            onUnpin = { key ->
                closeMenu()
                vm.entry(key)?.source?.let { vm.setSourcePinned(it, false) }
            },
            onResetIcon = { key ->
                closeMenu()
                vm.resetIcon(key)
            },
            onBootTarget = { key ->
                closeMenu()
                val entry = vm.entry(key)
                val source = entry?.source
                when {
                    source != null -> vm.chooseBootTarget(sourceId = source.id.takeIf { it != AerialSettings.bootSource })
                    entry != null -> vm.chooseBootTarget(pkg = key.takeIf { it != AerialSettings.bootApp })
                }
            },
            onLiveApp = { key ->
                closeMenu()
                val label = vm.entry(key)?.label ?: key
                val wasIn = key in AerialSettings.liveAppPackages
                AerialSettings.toggleLiveApp(key)
                val msg = if (wasIn) "「$label」已经离开直播预览应用，可重新选择"
                          else "「$label」已经成为直播预览应用，在第一排时有效"
                vm.showToast(msg)
            },
            onSourceDevice = { id, device ->
                closeMenu()
                SourceLooks.chooseDevice(id, device)
            },
            onToFolder = { key, id ->
                val folderKey = vm.putInFolder(key, id)
                // From Home, focus goes to the folder the app went into.
                if (menu?.inFolder == null) focusAfterOverlay = folderKey
                closeMenu()
            },
            onOutOfFolder = { key ->
                vm.takeOutOfFolder(key)
                closeMenu()
            },
            onRenameFolder = { id, name ->
                vm.renameFolder(id, name)
                closeMenu()
            },
            onUngroup = { id ->
                vm.ungroup(id)
                closeMenu()
            },
            onDismiss = { closeMenu() },
        )

        KidsSetupSheet(
            visible = overlay is Overlay.Kids,
            candidates = vm.kidsCandidates,
            onStart = {
                if (!Restricted.enabled) Restricted.chooseEnabled(true)
                Restricted.start()
                vm.overlay = Overlay.None
                vm.homeFocusTarget = 0
                KidsWatch.poke()
            },
            onDismiss = { vm.overlay = Overlay.Wallpaper },
        )

        // Locked mid-panel (the day's time ran out with Control Center open): the lock is all there is.
        LaunchedEffect(kidsLock != null) {
            if (kidsLock != null && vm.overlay !is Overlay.Pin) vm.overlay = Overlay.None
        }
        KidsLockScreen(
            lock = kidsLock,
            pinOpen = overlay is Overlay.Pin,
            onParent = { right -> vm.overlay = Overlay.Pin(PinMode.Verify) { vm.overlay = Overlay.None; right() } },
            onLeave = { Restricted.suspend(); KidsWatch.poke() },
        )

        HiddenAppsSheet(
            visible = overlay is Overlay.HiddenApps,
            apps = vm.hiddenApps,
            onUnhide = { key ->
                vm.unhide(key)
                if (vm.hiddenApps.isEmpty()) vm.overlay = Overlay.Wallpaper
            },
            // Hidden apps are reached from Personalization, and Back returns there.
            onDismiss = { vm.overlay = Overlay.Wallpaper },
        )

        HiddenLiveSourcesSheet(
            visible = overlay is Overlay.HiddenLiveSources,
            sources = vm.hiddenLiveSources,
            onUnhide = { id ->
                vm.unhideLiveSource(id)
                if (vm.hiddenLiveSources.isEmpty()) vm.overlay = Overlay.Wallpaper
            },
            onDismiss = { vm.overlay = Overlay.Wallpaper },
        )

        WallpaperSheet(
            visible = overlay is Overlay.Wallpaper,
            onSettled = { sheetIn = it },
            warm = sheetWarm,
            onDismiss = { vm.overlay = Overlay.ControlCenter() },
            hiddenCount = vm.hiddenApps.size,
            onHiddenApps = { vm.whenUnlocked { vm.overlay = Overlay.HiddenApps } },
            hiddenLiveCount = vm.hiddenLiveSources.size,
            onHiddenLiveSources = { vm.whenUnlocked { vm.overlay = Overlay.HiddenLiveSources } },
            onKids = vm::setUpKidsMode,
            onStartScreensaver = {
                vm.overlay = Overlay.None
                onStartScreensaver()
            },
            onPin = { on ->
                if (on) vm.overlay = Overlay.Pin(PinMode.Create) { vm.overlay = Overlay.Wallpaper }
                // Kids mode cannot be left without a PIN: it goes with it.
                else vm.whenUnlocked { AerialSettings.choosePin(null); Restricted.chooseEnabled(false); vm.overlay = Overlay.Wallpaper }
            },
        )

        val pin = overlay as? Overlay.Pin
        PinPad(
            visible = pin != null,
            mode = pin?.mode,
            onDone = { pin?.onDone?.invoke() },
            onCancel = { vm.overlay = Overlay.None },
        )

        NoticeBanner(vm.notice, Modifier.align(Alignment.TopCenter).padding(top = 24.dp), vm.noticeGlyph)

        Screensaver(visible = vm.screensaverVisible, apps = apps, now = now, skips = vm.screensaverSkips, carry = when {
                filmSaver -> SaverCarry.Film(liveFilm)
                videoSaver -> SaverCarry.Video(homeVideo)
                stillCarry != null -> stillCarry
                else -> null
            },
        )

        // ===== 新增：直播源选择菜单组件 =====
        LiveSourceMenu(
            visible = overlay is Overlay.LiveSourceMenu,
            sources = vm.getAllLiveSources(),
            currentId = vm.currentLiveSourceId,
            onSelect = { s ->
                DiagLog.i("CC-debug: LiveMenu onSelect source=$s")
                vm.pickLiveSource(s)
                vm.overlay = Overlay.None
            },
            onMove = { from, to -> vm.moveLiveSource(from, to) },
            onHide = { vm.hideLiveSource(it) },
            onDelete = { vm.removeLiveSource(it) },
            onDismiss = { DiagLog.i("CC-debug: LiveMenu onDismiss"); vm.overlay = Overlay.None },
            onMenuOpenChange = { liveMenuOpen = it },
            onMovingChange = { liveMoving = it },
            onOpenControlCenter = { vm.ccFromLiveMenu = true; vm.overlay = Overlay.ControlCenter() },
            controlCenterOpen = overlay is Overlay.ControlCenter,
            network = vm.network,
        )

        ControlCenter(
            visible = overlay is Overlay.ControlCenter,
            page = (overlay as? Overlay.ControlCenter)?.page ?: CcPage.Main,
            vm = vm,
            now = now,
            onOpen = { vm.overlay = Overlay.None; onOpen(it) },
            onClose = { vm.overlay = if (vm.ccFromLiveMenu) { vm.ccFromLiveMenu = false; Overlay.LiveSourceMenu } else Overlay.None },
            warm = ccWarm,
        )

        // 全局玻璃胶囊轻提示(和直播切换同款)
        vm.globalToast?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(4000)
                vm.globalToast = null
            }
            androidx.compose.foundation.text.BasicText(
                msg,
                style = androidx.compose.ui.text.TextStyle(
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp),
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

/**
 * 直播状态轮询：焦点在 IPTV 卡片时立即检测一次，之后每 30 秒刷新；
 * onStatusRefresh 回调或二级菜单打开时手动 +1 tick 会重置计时并立即再查一次。
 *
 * 抽成独立 composable：liveStatusTickState.value 在这里被读取，tick 变化只重组这个小节点，
 * 不带动 HomeScreen 整棵网格/时钟/背景树（Mali-G31 上 fill rate 是瓶颈，整树重组代价高）。
 */
@Composable
private fun LiveStatusPollingEffect(
    channelPreviewVisible: Boolean,
    liveStatusTickState: State<Int>,
    vm: HomeViewModel,
) {
    val tick = liveStatusTickState.value
    LaunchedEffect(channelPreviewVisible, tick, vm) {
        if (channelPreviewVisible) {
            vm.checkLiveStatuses()
            while (true) {
                delay(30_000)
                vm.checkLiveStatuses()
            }
        }
    }
}

/**
 * 10 秒无操作自动隐藏 UI：直播就绪（liveReadyState true）且无浮层、无按键时开始计时；
 * 二级菜单打开时同理自动关闭并隐藏。
 *
 * 抽成独立 composable：liveReadyState.value 在这里被读取，就绪 false→true 只重组这个小节点，
 * 不带动整棵树。其余 key（focusedPkg/overlay/uiHidden/lastKeyTime/...）本就是 HomeScreen 主 UI
 * 状态，主树本来就会因它们重组，不会新增开销。
 */
@Composable
private fun LiveUiAutoHideEffect(
    focusedPkg: String,
    overlay: Overlay,
    uiHidden: Boolean,
    onUiHiddenChange: (Boolean) -> Unit,
    lastKeyTime: Long,
    channelPreviewVisible: Boolean,
    liveMenuOpen: Boolean,
    liveMoving: Boolean,
    liveReadyState: State<Boolean>,
    vm: HomeViewModel,
) {
    val liveReady = liveReadyState.value
    LaunchedEffect(focusedPkg, overlay, vm.uiHidden, lastKeyTime, channelPreviewVisible, liveMenuOpen, liveMoving, liveReady, vm) {
        if (channelPreviewVisible && focusedPkg in AerialSettings.liveAppPackages && overlay == Overlay.None && !vm.uiHidden && liveReady) {
            delay(10_000)
            onUiHiddenChange(true)
        }
        // 二级菜单打开时也10秒自动隐藏（上下文菜单/移动模式时不自动隐藏）
        if (overlay is Overlay.LiveSourceMenu && !liveMenuOpen && !liveMoving) {
            delay(10_000)
            vm.overlay = Overlay.None
            onUiHiddenChange(true)
        }
    }
}

/** 直播加载图: 铺满整屏, 压在壁纸/预览影片(都是 SurfaceView)之上, 直播出帧后淡出。 */
@Composable
private fun LiveLoadingOverlay(visible: Boolean, loading: androidx.compose.runtime.MutableState<Boolean>) {
    // 用"正在加载"而不是"未出帧": 切换直播源时画面本来是就绪的,
    // 只看就绪位就永远不会显示加载图
    val on = visible && loading.value
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(if (on) 100 else 250),
        label = "liveLoading",
    )
    if (alpha > 0.01f) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.live_loading),
            contentDescription = "直播加载中",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            alpha = alpha,
        )
    }
}
