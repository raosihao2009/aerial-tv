package app.orchard.tv.ui.wallpaper

import android.os.Build
import app.orchard.tv.apps.AppEntry
import android.os.SystemClock
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.orchard.tv.R
import app.orchard.tv.apps.BiliAccount
import app.orchard.tv.apps.BiliLogin
import app.orchard.tv.apps.BiliLoginState
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.Restricted
import app.orchard.tv.settings.RestrictedKind
import app.orchard.tv.settings.FocusStyle
import app.orchard.tv.settings.UiSounds
import app.orchard.tv.settings.Effects
import app.orchard.tv.settings.ClockHours
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.bubbleGlass
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.components.smoothBackground
import app.orchard.tv.ui.components.smoothBorder
import app.orchard.tv.ui.components.smoothCut
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.wallpaper.PhoneServer
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.AerialQuality
import app.orchard.tv.wallpaper.AerialSet
import app.orchard.tv.wallpaper.AerialStream
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.system.SystemScreensaver
import app.orchard.tv.wallpaper.Wallpaper
import app.orchard.tv.wallpaper.WallpaperKind
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherKind
import app.orchard.tv.ui.home.MiniTile
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Size
import app.orchard.tv.LiveSource
import app.orchard.tv.ui.home.liveSourceIconResId
import app.orchard.tv.ui.home.liveColors

/**
 * Wallpaper and screensaver, chosen on the TV. A sheet rises from the bottom and leaves the top of
 * the screen showing Home, so a new wallpaper can be seen the moment it is chosen. A QR code on the
 * right opens the same settings on a phone, where sending photos is easy.
 */
@Composable
fun WallpaperSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    /** 收起来的卡片。入口按钮按下去浮出一个列出它们的小窗口, 和首页一样带应用图标。 */
    hiddenApps: List<AppEntry> = emptyList(),
    hiddenLiveSources: List<LiveSource> = emptyList(),
    onUnhideApp: (String) -> Unit = {},
    onUnhideLiveSource: (String) -> Unit = {},
    onStartScreensaver: () -> Unit,
    onPin: (on: Boolean) -> Unit,
    /**
     * 锁定主页的开关。上锁不需要验证; 解锁在设了家长密码时要先输密码, 由调用方走 whenUnlocked,
     * 所以这里拿到的是回调而不是直接写设置。
     */
    onHomeLock: (on: Boolean) -> Unit = { AerialSettings.chooseHomeLocked(it) },
    /** Kids and Elders mode's setup (behind the PIN). */
    onKids: () -> Unit,
    /** The sheet is all the way in (true) or not: Home need not draw under it then ([SheetCovers]). */
    onSettled: (Boolean) -> Unit = {},
    /** Draw the closed sheet once, nearly clear, so that its first opening is not its first draw (HomeScreen). */
    warm: Boolean = false,
    /** One line at the top of Home, as the rest of the settings do. */
    onToast: (String) -> Unit = {},
    /**
     * 面板被家长密码框盖住(密码框画在面板上层, 面板不收起来)。这段时间面板交出焦点,
     * 密码框关掉后再把焦点还给进来之前那颗按钮 —— 否则 Compose 会重新搜一遍,
     * 一路滚到「屏保」分类, 焦点还落在「类型」上。
     */
    holdFocus: Boolean = false,
) {
    val context = LocalContext.current
    // 记"面板里最后一颗拿到焦点的按钮": 被密码框打断再回来, 按它原路还回去。
    val focusMemory = remember { SheetFocusMemory() }
    var wasHolding by remember { mutableStateOf(holdFocus) }
    LaunchedEffect(holdFocus, visible) {
        val holding = holdFocus
        focusMemory.holding = holding
        val released = wasHolding && !holding
        wasHolding = holding
        if (!visible) {
            focusMemory.last = null
            return@LaunchedEffect
        }
        if (released) {
            // 密码框撤掉之后再还焦点: 早一帧还, 密码框的节点还在, 请求会被吞。
            repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            focusMemory.last?.let { runCatching { it.requestFocus() } }
        }
    }
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    // B 站扫码的状态提到面板这一层: 右侧那张卡要跟着它换内容。
    val biliMirror = remember { BiliMirror() }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }

    // 密码框盖在面板上时, 面板不能接返回键: 否则在密码框里按一下返回, 面板跟着一起关掉。
    BackHandler(enabled = visible && !holdFocus, onBack = onDismiss)

    // The phone page listens all along (PhoneServer.listen); the address is read again each time,
    // as the TV's may have changed.
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        val url = withContext(Dispatchers.IO) { PhoneServer.start(context) }
        phoneUrl = url
        qr = url?.let { withContext(Dispatchers.Default) { PhoneServer.qr(it).asImageBitmap() } }
    }

    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    val settled = shown.isIdle && shown.currentState
    LaunchedEffect(settled) { onSettled(settled) }

    // 全屏面板: 不再从小面板回弹, 改成从屏底略微升起 + 淡入; 关闭时往回落 + 淡出。
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(240, easing = Motion.EaseOut)) +
            slideInVertically(tween(280, easing = Motion.EaseOut)) { it / 10 },
        exit = fadeOut(tween(170, easing = Motion.EaseOut)) +
            slideOutVertically(tween(220, easing = Motion.EaseOut)) { it / 12 },
        modifier = Modifier.fillMaxSize(),
    ) {
        // A tap above the sheet closes it, back to Control Center as Back does. Once the sheet is
        // in, the dimming stops where it starts: the sheet is opaque.
        Box(
            Modifier.fillMaxSize().dismissOnTap(onDismiss).drawBehind {
                val h = if (settled) size.height - SheetCovers.toPx() else size.height
                drawRect(Color.Black.copy(alpha = 0.25f), size = Size(size.width, h))
            }.onPreviewKeyEvent { e ->
                if (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) true else false
            }.focusProperties {
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
        )
    }

    // The sheet as it opens, or (takeFocus false) drawn once nearly clear for the warm-up.
    val sheet: @Composable (takeFocus: Boolean) -> Unit = { takeFocus ->
        val menuWindow = remember { MenuWindow() }
        // 「隐藏的卡片」的两颗入口各自一个小窗口, 和选项窗口同一套气泡。
        val popupHost = remember { PopupHost() }
        Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalMenuWindow provides menuWindow,
            LocalPopupHost provides popupHost,
            LocalSheetFocus provides focusMemory,
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .consumeTaps()
                // Glass made from the wallpaper itself, opaque: Home is not blurred behind
                // this sheet, so the wallpaper above it stays sharp while you choose, and the
                // app grid must not show through.
                // 全屏: 四边不留圆角, 也不画那圈亮边。
                .liquidGlass(0.dp, bottomCorners = false, edge = false)
        ) {
            // 全屏之后, 标题常驻在最上面, 下面压一条分隔线; 分隔线以下才是可滚动的那一页。
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 12.dp)
                    .drawBehind {
                        val y = size.height + 12.dp.toPx()
                        drawRect(Palette.Hairline, topLeft = Offset(0f, y), size = Size(size.width, 1.dp.toPx()))
                    },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Person, Palette.Label, 26.dp)
                    Spacer(Modifier.width(14.dp))
                    BasicText(stringResource(R.string.wp_title), style = Type.LargeTitle)
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 32.dp, end = 32.dp)) {
            val leftScroll = rememberScrollState()
            // 一页往下走: 每个分类是"图标 + 分类名"的标题, 标题下就是那一组选项, 不再做左栏
            // 焦点切换。分类图标保留, 是为了在这一长页里一眼认出走到哪一组。
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(leftScroll).latestBringIntoView(leftScroll)) {
                Spacer(Modifier.height(14.dp))
                CategoryTitle(Glyph.Photo, R.string.wp_wallpaper)
                WallpaperSection(takeFocus)
                Spacer(Modifier.height(18.dp))
                CategoryTitle(Glyph.Home, R.string.per_home)
                HomeSection(
                    hiddenApps, hiddenLiveSources, onUnhideApp, onUnhideLiveSource, onHomeLock,
                )
                Spacer(Modifier.height(18.dp))
                CategoryTitle(Glyph.Play, R.string.per_live)
                LiveSection(biliMirror)
                Spacer(Modifier.height(18.dp))
                CategoryTitle(Glyph.Sun, R.string.per_look)
                LookSection()
                Spacer(Modifier.height(18.dp))
                CategoryTitle(Glyph.Moon, R.string.per_screensaver)
                ScreensaverSection(onStartScreensaver)
                Spacer(Modifier.height(18.dp))
                CategoryTitle(Glyph.CloudSun, R.string.wp_weather)
                WeatherSection()
                Spacer(Modifier.height(18.dp))
                CategoryTitle(Glyph.Shield, R.string.per_family)
                FamilySection(onPin, onKids)
                Spacer(Modifier.height(24.dp))
            }
            Spacer(Modifier.width(18.dp))
            // 右侧二维码: 在"分隔线以下、屏幕底部以上"这块区域里上下居中。
            Box(Modifier.width(200.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                PhoneCard(phoneUrl, qr, biliMirror)
            }
            }
        }
        }
        // 迷你窗口画在最上层: 不用独立窗口, 焦点始终在入口按钮上。
        MenuWindowLayer(menuWindow)
        // 小窗口里不再带标题行: 「隐藏的卡片·应用 / ·直播」那两行字去掉, 直接从第一张卡片开始。
        ListWindowLayer(
            popupHost.apps,
            stringResource(R.string.hidden_empty), hiddenApps.map { HiddenRow(it.key, it.label, it) },
        )
        ListWindowLayer(
            popupHost.live,
            stringResource(R.string.st_hidden_none), hiddenLiveSources.map { HiddenRow(it.id, it.name, live = it) },
        )
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // The first opening composed the sheet and gave the GPU its pictures, glyphs and shaders
        // for the first time: frames of 250 and 316 ms, 1.16 s for the slide-in, against about
        // 140 ms at worst on later openings (Xiaomi MiTV-MFTR0). Drawn once at 1% while Home is
        // idle, without focus (HomeScreen, as Control Center), the first opening is like the next.
        if (warm && !visible) Box(Modifier.graphicsLayer { alpha = 0.01f }) { sheet(false) }
        AnimatedVisibility(
            visibleState = shown,
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + slideInVertically(tween(380, easing = Motion.Drawer)) { it / 3 },
            exit = fadeOut(tween(160, easing = Motion.EaseOut)) + slideOutVertically(tween(200, easing = Motion.EaseOut)) { it / 4 },
        ) {
            sheet(true)
        }
    }
}

/**
 * Every wallpaper in one row, in the order most people want them: the moving aerial, Bing's photo
 * of the day, the owner's own photos, then a few of the built-in pictures and "Follow App". Past
 * daily photos and the rest of the built-ins are left out unless one of them is the current
 * choice. The focused card's note is under the row, as under a group of settings.
 */
/** 分类标题: 图标 + 分类名, 这一组选项就在它下面。 */
@Composable
private fun CategoryTitle(glyph: Glyph, label: Int) {
    Row(
        Modifier.padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlyphIcon(glyph, Palette.SecondaryLabel, 20.dp)
        BasicText(stringResource(label), style = Type.Title)
    }
    Spacer(Modifier.height(10.dp))
}

/** 壁纸: 卡片 + 只在这类壁纸下才问的两个问题。 */
@Composable
private fun WallpaperSection(takeFocus: Boolean) {
    WallpaperRows(takeFocus)
    // Only for the owner's videos: whether keys stop them. The aerial films always move.
    if (Wallpapers.isVideo()) {
        Row2({
            ChoiceCell(
                R.string.st_play, stringResource(R.string.st_play_hint),
                listOf(false to R.string.st_play_still, true to R.string.st_play_always),
                AerialSettings.wallpaperAlwaysMoves, AerialSettings::chooseWallpaperAlwaysMoves,
            )
        })
        Spacer(Modifier.height(6.dp))
    }
    // 上游 #1314: 只有"我的照片, 轮流"才需要问多久换一张, 别的壁纸没有下一张
    if (Wallpapers.selectedId == Wallpapers.ROTATE_PHOTOS) {
        Row2({
            ChoiceCell(
                R.string.st_photo_every, stringResource(R.string.st_photo_every_hint),
                AerialSettings.PHOTO_MINUTES.map { m ->
                    m to when {
                        m >= AerialSettings.DAY_MINUTES -> stringResource(R.string.st_photo_every_day)
                        m >= 60 -> stringResource(R.string.kids_h, m / 60)
                        else -> stringResource(R.string.kids_m, m)
                    }
                },
                AerialSettings.photoMinutes,
                { AerialSettings.choosePhotoMinutes(it); Wallpapers.rotateIfDue() },
            )
        })
        Spacer(Modifier.height(6.dp))
    }
    Spacer(Modifier.height(6.dp))
}

/** 主屏幕: 怎么摆、叫什么、露多少、哪些卡片收起来了。 */
@Composable
private fun HomeSection(
    hiddenApps: List<AppEntry>,
    hiddenLiveSources: List<LiveSource>,
    onUnhideApp: (String) -> Unit,
    onUnhideLiveSource: (String) -> Unit,
    onHomeLock: (on: Boolean) -> Unit,
) {
    val popup = LocalPopupHost.current
    val appsChip = if (hiddenApps.isEmpty()) stringResource(R.string.st_hidden_app_none)
    else stringResource(R.string.st_hidden_app_count, hiddenApps.size)
    val liveChip = if (hiddenLiveSources.isEmpty()) stringResource(R.string.st_hidden_live_none)
    else stringResource(R.string.st_hidden_live_count, hiddenLiveSources.size)
    Group {
        Row2(
            {
                ChoiceCell(
                    R.string.st_columns, stringResource(R.string.st_columns_hint),
                    AerialSettings.HOME_COLUMNS.map { it to it.toString() },
                    AerialSettings.homeColumns, AerialSettings::chooseHomeColumns,
                )
            },
            {
                ChoiceCell(
                    R.string.st_names, stringResource(R.string.st_names_hint),
                    listOf(false to R.string.st_names_focused, true to R.string.st_names_all),
                    AerialSettings.showAllNames, AerialSettings::chooseShowAllNames,
                )
            },
        )
        Row2(
            {
                ChoiceCell(
                    R.string.st_plate, stringResource(R.string.st_plate_hint),
                    listOf(true to R.string.st_plate_glass, false to R.string.st_plate_none),
                    AerialSettings.homePlate, AerialSettings::chooseHomePlate,
                )
            },
            {
                // 双排布局下也保留这个选项: 它也控制二级菜单(直播源)露出下一排的效果。
                ChoiceCell(
                    R.string.st_peek, stringResource(R.string.st_peek_hint),
                    listOf(true to R.string.st_peek_show, false to R.string.st_peek_hide),
                    AerialSettings.peekNextRow, AerialSettings::choosePeekNextRow,
                )
            },
        )
        Row2(
            {
                ChoiceCell(
                    R.string.st_layout, stringResource(R.string.st_home_layout_hint),
                    listOf(true to R.string.st_home_one_row, false to R.string.st_home_standard),
                    AerialSettings.oneRowHome, AerialSettings::chooseOneRowHome,
                )
            },
            {
                // 上锁: 长按 OK / Menu 不再弹应用菜单, 免得误移动、误隐藏、误卸载
                ChoiceCell(
                    R.string.st_home_lock, stringResource(R.string.st_home_lock_hint),
                    listOf(true to R.string.st_on, false to R.string.st_off),
                    AerialSettings.homeLocked, onHomeLock,
                )
            },
        )
        Row2(
            {
                // 一颗格子两颗按钮(应用 / 直播), 各自开自己的小窗口, 不拆成两格。
                HiddenCell(
                    R.string.st_hidden, stringResource(R.string.st_hidden_hint),
                    listOf(
                        HiddenEntry(
                            popup?.apps, appsChip,
                            hiddenApps.map { HiddenRow(it.key, it.label, it) }, onUnhideApp,
                        ),
                        HiddenEntry(
                            popup?.live, liveChip,
                            hiddenLiveSources.map { HiddenRow(it.id, it.name, live = it) }, onUnhideLiveSource,
                        ),
                    ),
                )
            },
        )
    }
    Spacer(Modifier.height(12.dp))
}

/** 直播与卡片: 背景预览、卡顿灵敏度、B 站账号。 */
@Composable
private fun LiveSection(biliMirror: BiliMirror) {
    Group {
        // 二创：焦点选中 IPTV 应用时背景播放相关直播
        Row2(
            {
                ChoiceCell(
                    R.string.st_live_preview, stringResource(R.string.st_live_preview_hint),
                    listOf(true to R.string.st_on, false to R.string.st_off),
                    AerialSettings.livePreviewEnabled, AerialSettings::chooseLivePreview,
                )
            },
            { BiliCell(biliMirror) },
        )
        Row2(
            {
                // 画质不再有全局开关: 每张直播卡的画质在直播菜单里按台选。
                ChoiceCell(
                    R.string.st_live_jank, stringResource(R.string.st_live_jank_hint),
                    listOf(
                        AerialSettings.JankSensitivity.Off to "关闭",
                        AerialSettings.JankSensitivity.Low to "低",
                        AerialSettings.JankSensitivity.Medium to "中",
                        AerialSettings.JankSensitivity.High to "高",
                    ),
                    AerialSettings.jankSensitivity, AerialSettings::chooseJankSensitivity,
                )
            },
        )
    }
    Spacer(Modifier.height(12.dp))
}

/** 外观与声音: 焦点框、界面效果、开场遮罩、按键音与它的音量。 */
@Composable
private fun LookSection() {
    Group {
        Row2(
            {
                ChoiceCell(
                    R.string.st_focus_style,
                    stringResource(if (AerialSettings.focusStyle == FocusStyle.HighContrast) R.string.st_focus_high_hint else R.string.st_focus_standard_hint),
                    listOf(FocusStyle.HighContrast to R.string.st_focus_high, FocusStyle.Standard to R.string.st_focus_standard),
                    AerialSettings.focusStyle, AerialSettings::chooseFocusStyle,
                )
            },
            {
                ChoiceCell(
                    R.string.st_effects,
                    stringResource(
                        if (AerialSettings.effects == Effects.Auto && AerialSettings.lowEnd) R.string.st_effects_auto_lite_hint
                        else R.string.st_effects_hint,
                    ),
                    listOf(Effects.Auto to R.string.st_effects_auto, Effects.Full to R.string.st_effects_full, Effects.Lite to R.string.st_effects_lite),
                    AerialSettings.effects, AerialSettings::chooseEffects,
                )
            },
        )
        Row2(
            {
                ChoiceCell(
                    R.string.st_splash_veil, stringResource(R.string.st_splash_veil_hint),
                    listOf(true to R.string.st_on, false to R.string.st_off),
                    AerialSettings.splashVeilEnabled, AerialSettings::chooseSplashVeil,
                )
            },
            {
                ChoiceCell(
                    R.string.st_sounds,
                    stringResource(if (AerialSettings.uiSounds == UiSounds.Tv) R.string.st_sounds_tv_hint else R.string.st_sounds_hint),
                    listOf(UiSounds.Tv to R.string.st_sounds_tv, UiSounds.Aerial to R.string.st_sounds_aerial, UiSounds.Off to R.string.st_off),
                    AerialSettings.uiSounds, { AerialSettings.chooseUiSounds(it); Sounds.prepare() },
                )
            },
        )
        // Aerial 自带按键音的独立音量: 只在选「Aerial」时出现, 和电视音量滑块无关。
        if (AerialSettings.uiSounds == UiSounds.Aerial) {
            Row2({ KeyVolumeCell() })
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** 屏幕保护程序: 类型、画质、开始时间、返回键、时钟。 */
@Composable
private fun ScreensaverSection(onStartScreensaver: () -> Unit) {
    val context = LocalContext.current
    val kindHint = when {
        Wallpapers.screensaver == ScreensaverMode.Videos -> stringResource(R.string.ss_videos_hint)
        Wallpapers.screensaver == ScreensaverMode.System -> stringResource(R.string.ss_system_hint)
        Wallpapers.screensaverFollows -> stringResource(R.string.ss_follow_hint)
        AerialSettings.backStartsScreensaver -> stringResource(R.string.ss_back_hint)
        else -> null
    }
    Group {
        Row2(
            {
                // 类型: 入口按钮(当前类型 + ">")在右, 左边是"立即开始"。
                MenuCell(
                    R.string.ss_kind, kindHint,
                    listOf(
                        ScreensaverMode.Aerial to R.string.ss_aerial,
                        ScreensaverMode.Photos to R.string.ss_photos,
                        ScreensaverMode.Clock to R.string.ss_clock,
                        ScreensaverMode.Videos to R.string.ss_videos,
                        ScreensaverMode.System to R.string.ss_system,
                    ),
                    Wallpapers.screensaver,
                    { Wallpapers.chooseScreensaver(it) },
                    leading = CellAction(stringResource(R.string.ss_start), chevron = false, icon = Glyph.Play) { onStartScreensaver() },
                )
            },
            {
                // Only the qualities this TV can play; a 1080p-only TV has nothing to choose.
                val qualities = remember { AerialFormats.supported(context) }
                if (qualities.size > 1) {
                    ChoiceCell(
                        R.string.ss_quality, stringResource(R.string.ss_quality_hint),
                        listOf(AerialQuality.Auto to R.string.ss_quality_auto, AerialQuality.Hd to R.string.ss_quality_hd) +
                            listOf(AerialQuality.Uhd to R.string.ss_quality_uhd, AerialQuality.DolbyVision to R.string.ss_quality_dv)
                                .filter { it.first in qualities },
                        AerialSettings.aerialQuality, AerialSettings::chooseAerialQuality,
                    )
                }
            },
        )
        Row2(
            {
                // The firmware's screensaver starts on the firmware's timer: 只显示它的值。
                if (Wallpapers.screensaver == ScreensaverMode.System) {
                    val delay = remember { SystemScreensaver.firmwareDelayMs(context) }
                    val shown = when {
                        delay == null -> stringResource(R.string.ss_delay_system)
                        delay == 0L -> stringResource(R.string.ss_delay_system_off)
                        else -> stringResource(R.string.ss_delay_system_minutes, ((delay + 30_000) / 60_000).coerceAtLeast(1).toInt())
                    }
                    Cell(R.string.ss_delay, stringResource(R.string.ss_delay_hint)) { onFocus ->
                        ChipButton(shown, on = true, check = false, onClick = {}, onFocus = onFocus)
                    }
                } else {
                    MenuCell(
                        R.string.ss_delay, stringResource(R.string.ss_delay_hint),
                        AerialSettings.screensaverChoices.map {
                            it to if (it == AerialSettings.NEVER) stringResource(R.string.ss_never) else stringResource(R.string.ss_minutes, it)
                        },
                        AerialSettings.screensaverMinutes, AerialSettings::chooseScreensaverMinutes,
                    )
                }
            },
            {
                ChoiceCell(
                    R.string.ss_back, stringResource(R.string.ss_back_choice_hint),
                    listOf(true to R.string.ss_back_on, false to R.string.ss_back_off),
                    AerialSettings.backStartsScreensaver, AerialSettings::chooseBackStartsScreensaver,
                )
            },
        )
        // The clock screensaver is its clock, and the others show none.
        if (Wallpapers.screensaver == ScreensaverMode.Aerial || Wallpapers.screensaver == ScreensaverMode.Photos) {
            Row2({
                ChoiceCell(
                    R.string.ss_show_time, stringResource(R.string.ss_show_time_hint),
                    listOf(true to R.string.st_on, false to R.string.st_off),
                    AerialSettings.screensaverClock, AerialSettings::chooseScreensaverClock,
                )
            })
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** 天气与时钟。 */
@Composable
private fun WeatherSection() {
    Group {
        Row2(
            {
                ChoiceCell(
                    R.string.st_clock_hours, stringResource(R.string.st_clock_hours_hint),
                    listOf(ClockHours.H24 to R.string.st_clock_hours_24, ClockHours.H12 to R.string.st_clock_hours_12),
                    if (AerialSettings.clockHours == ClockHours.H12) ClockHours.H12 else ClockHours.H24,
                    { AerialSettings.chooseClockHours(it) },
                )
            },
            {
                // 天气是否显示在时钟下方
                ChoiceCell(
                    R.string.st_weather_visibility, stringResource(R.string.st_weather_visibility_hint),
                    listOf(true to R.string.st_on, false to R.string.st_off),
                    AerialSettings.showWeather, AerialSettings::chooseShowWeather,
                )
            },
        )
        Row2({ WeatherCell() })
    }
    Spacer(Modifier.height(12.dp))
}

/** 亲子守护: 密码与儿童/长辈模式的入口。 */
@Composable
private fun FamilySection(onPin: (on: Boolean) -> Unit, onKids: () -> Unit) {
    Group {
        Row2(
            {
                ChoiceCell(
                    R.string.st_pin, stringResource(R.string.st_pin_hint),
                    listOf(true to R.string.st_pin_on, false to R.string.st_pin_off),
                    AerialSettings.pinSet,
                    { if (AerialSettings.pinSet != it) onPin(it) },
                )
            },
            {
                ActionCell(
                    R.string.st_kids, stringResource(R.string.st_kids_hint),
                    listOf(
                        CellAction(
                            when {
                                !Restricted.enabled -> stringResource(R.string.st_off)
                                Restricted.kind == RestrictedKind.Kids -> stringResource(R.string.kids_kind_kids)
                                else -> stringResource(R.string.kids_kind_elders)
                            },
                            chevron = true,
                        ) { onKids() },
                    ),
                )
            },
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun WallpaperRows(takeFocus: Boolean) {
    val items = Wallpapers.items
    val selected = Wallpapers.selectedId
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val dailyTitle = stringResource(R.string.wp_daily_latest)
    val rotateTitle = stringResource(R.string.wp_photos_rotate)
    val videosTitle = stringResource(R.string.wp_videos_rotate)
    val list = remember(items, selected, dailyTitle, rotateTitle, videosTitle) {
        buildList {
            addAll(items.filter { it.kind == WallpaperKind.Live })
            // Each video on its own, and with two or more one card that plays them in turn (#762).
            val videos = items.filter { it.kind == WallpaperKind.Video && it.id != Wallpapers.VIDEOS }
            if (videos.size > 1 || selected == Wallpapers.VIDEOS) add(Wallpaper(Wallpapers.VIDEOS, WallpaperKind.Video, videosTitle))
            addAll(videos)
            if (items.any { it.kind == WallpaperKind.Daily }) add(Wallpaper(Wallpapers.LATEST_DAILY, WallpaperKind.Daily, dailyTitle))
            addAll(items.filter { it.kind == WallpaperKind.Daily && it.id == selected })
            val photos = items.filter { it.kind == WallpaperKind.Photo }
            // With two or more, one card takes turns through all of them, one a day (#27).
            if (photos.size > 1) add(Wallpaper(Wallpapers.ROTATE_PHOTOS, WallpaperKind.Photo, rotateTitle))
            addAll(photos)
            addAll(items.filter { it.kind == WallpaperKind.Builtin && (it.id in Wallpapers.FEATURED_BUILTINS || it.id == selected) })
            addAll(items.filter { it.kind == WallpaperKind.Auto })
            // The inputs' pictures last: a choice for a home with a set-top box, not the usual one.
            addAll(items.filter { it.kind == WallpaperKind.Source })
        }
    }
    var note by remember { mutableStateOf<String?>(null) }
    // A row clips to its bounds, and the focused card grows by 8%: room on the left for that,
    // with the row moved back by as much so the cards still line up with the titles.
    LazyRow(
        modifier = Modifier
            .offset(x = (-10).dp)
            // 画到自己的离屏层里, 才能用 DstIn 做左右两端的淡入淡出(不做层的话会连外面的壁纸一起擦掉)。
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                // Keep the cards out of the phone column on old GPUs (#557), independently of the
                // scroll layer's clip. Leave vertical room for the focused card's 8% zoom.
                val focusRoom = 16.dp.toPx()
                clipRect(top = -focusRoom, bottom = size.height + focusRoom) {
                    this@drawWithContent.drawContent()
                }
                // 左右各留一段渐隐: 卡片滚到边上时慢慢化掉, 而不是被硬切。
                val fade = 26.dp.toPx()
                val w = size.width.coerceAtLeast(1f)
                if (w > fade * 2) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            (fade / w) to Color.Black,
                            (1f - fade / w) to Color.Black,
                            1f to Color.Transparent,
                        ),
                        topLeft = Offset(0f, -focusRoom),
                        size = Size(w, size.height + focusRoom * 2),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 24.dp, top = 6.dp, bottom = 4.dp),
    ) {
        items(list, key = { it.id }) { wp ->
            val hint = wallpaperNote(wp)
            WallpaperCard(wp, if (wp === list.first()) first else null, onFocused = { note = hint })
        }
    }
    Box(Modifier.height(42.dp).padding(start = 4.dp, top = 8.dp)) {
        note?.let { BasicText(it, style = Type.Caption.copy(color = Palette.TertiaryLabel), maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** What a wallpaper card is, for the note under the row; null where its picture says it all. */
@Composable
private fun wallpaperNote(wp: Wallpaper): String? = when {
    wp.kind == WallpaperKind.Live -> stringResource(R.string.wp_live)
    wp.id == Wallpapers.VIDEOS -> stringResource(R.string.wp_videos_note)
    wp.kind == WallpaperKind.Video -> stringResource(R.string.wp_video_note)
    wp.kind == WallpaperKind.Source -> stringResource(R.string.wp_source_note)
    wp.id == Wallpapers.LATEST_DAILY -> stringResource(R.string.wp_daily_note)
    wp.id == Wallpapers.ROTATE_PHOTOS -> stringResource(R.string.wp_photos_rotate_note)
    wp.kind == WallpaperKind.Photo -> stringResource(R.string.wp_photo_note)
    wp.kind == WallpaperKind.Auto -> stringResource(R.string.wp_auto_note)
    wp.kind == WallpaperKind.Daily -> wp.caption
    else -> null
}

private val CardCorner = 12.dp

@Composable
private fun WallpaperCard(wp: Wallpaper, focusRequester: FocusRequester?, onFocused: () -> Unit) {
    val selected = Wallpapers.selectedId == wp.id
    // A moving wallpaper's card is made again when the frame it stopped on changes, also while the
    // sheet is open: the film stopped as the sheet opened is decoded seconds later (Xiaomi MiTV-MFTR0).
    val picture = if (wp.kind == WallpaperKind.Live || wp.kind == WallpaperKind.Video) Wallpapers.pictureVersion else 0
    // The picture is cut to the card's continuous outline once, at the size it is drawn, so the
    // card is one image draw and nothing is clipped (see SmoothCorners.kt).
    val density = LocalDensity.current
    val cardW = with(density) { 168.dp.roundToPx() }
    val cardH = with(density) { 94.dp.roundToPx() }
    val corner = with(density) { CardCorner.toPx() }
    val thumb by produceState<ImageBitmap?>(null, wp.id, Wallpapers.items, picture, cardW, cardH) {
        value = withContext(Dispatchers.Default) {
            Wallpapers.thumbnail(wp.id)?.let { smoothCut(it, cardW, cardH, corner).asImageBitmap() }
        }
    }
    Pressable(onClick = { Wallpapers.select(wp.id) }, focusRequester = focusRequester, focusedScale = 1.08f) { state ->
        LaunchedEffect(state.focused) { if (state.focused) onFocused() }
        Column(Modifier.width(168.dp)) {
            Box(
                Modifier
                    .size(168.dp, 94.dp)
                    .smoothBackground(if (thumb == null) Palette.Fill else Color.Transparent, CardCorner)
                    .smoothBorder(
                        if (state.focused || selected) 3.dp else 1.dp,
                        when {
                            state.focused -> Palette.Focus
                            selected -> Palette.Focus.copy(alpha = 0.55f)
                            else -> Palette.Hairline
                        },
                        CardCorner,
                    ),
            ) {
                thumb?.let {
                    Canvas(Modifier.fillMaxSize()) {
                        drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
                    }
                }
                if (selected) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp).clip(CircleShape).background(Palette.Focus),
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.Check, Palette.OnFocus, 13.dp) }
                }
                // The aerial wallpaper moves; its card shows the last frame it stopped on.
                if (wp.kind == WallpaperKind.Live || wp.kind == WallpaperKind.Video) {
                    GlyphIcon(Glyph.Play, Color.White, 22.dp, Modifier.align(Alignment.BottomStart).padding(7.dp))
                }
                // An input's picture cannot be copied for a card: its mark over the dark picture.
                if (wp.kind == WallpaperKind.Source) {
                    GlyphIcon(Glyph.Hdmi, Color.White, 34.dp, Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.height(6.dp))
            BasicText(
                wp.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    BasicText(stringResource(title), style = Type.Headline, modifier = Modifier.padding(start = 4.dp))
    Spacer(Modifier.height(10.dp))
}

/**
 * A card of setting rows, as in tvOS Settings, with one note under it: the focused row's, or
 * [note] when the row has none or focus is elsewhere. The note keeps two lines of room so the
 * sheet does not shift as focus moves.
 */
@Composable
private fun Group(rows: @Composable ColumnScope.() -> Unit) {
    var height by remember { mutableStateOf(0f) }
    // A focused row asks the sheet to show it from its top down to the group's note: the
    // screensaver's group sits at the sheet's bottom, and showing the row alone (on TV, Compose
    // keeps a focused row about a third down) left the quality's note below the screen. One
    // request, so the sheet scrolls once; a second scroll after the focus's own made the sheet
    // jump back and forth on every press (TCL MT5879).
    val withNote = remember {
        @OptIn(ExperimentalFoundationApi::class)
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect) =
                Rect(localRect.left, localRect.top, localRect.right, maxOf(localRect.bottom, height))
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }
    @OptIn(ExperimentalFoundationApi::class)
    Column(Modifier.fillMaxWidth().onSizeChanged { height = it.height.toFloat() }.bringIntoViewResponder(withNote)) {
        Column(Modifier.fillMaxWidth()) {
            // 组的说明不再是常驻一行: 它并进了相关那一格的小字提示, 焦点落上去才出现。
            rows()
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 两列网格: 一行放两个选项; 只有一个时它自己占满整行, 右边照样留出位置, 所以两列时
 * 左右两格的宽度和它们小字提示的左边总在同一条线上。
 */
@Composable
private fun Row2(left: @Composable () -> Unit, right: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(CellGap),
    ) {
        Box(Modifier.weight(1f)) { left() }
        if (right != null) Box(Modifier.weight(1f)) { right() }
    }
}

/** 一颗按钮: 一段文字, 需要时带一个 ">"。 */
private class CellAction(
    val text: String,
    val chevron: Boolean,
    val icon: Glyph? = null,
    val run: () -> Unit,
)

/**
 * 一个选项自己就是一颗气泡: 上面一行是名称和它的值, 下面一直显示这一项的小字提示
 * (两行位置固定, 有没有内容都这么高)。气泡平时只有淡底和一圈细边, 焦点落在气泡里的
 * 任何一颗按钮上时, 气泡整体描一圈白边 —— 宽度和壁纸卡片的选中框一致。
 */
@Composable
private fun Cell(
    label: Int,
    hint: String?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(onFocus: (Any, Boolean) -> Unit) -> Unit,
) = Cell(stringResource(label), hint, modifier, content)

@Composable
private fun Cell(
    labelText: String,
    hint: String?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(onFocus: (Any, Boolean) -> Unit) -> Unit,
) {
    // 记"哪颗按钮在聚焦"。布尔值会在同一格里换按钮时被后到的 false 抹掉, 描边就消失一下。
    var focusOwner by remember { mutableStateOf<Any?>(null) }
    val focused = focusOwner != null
    val borderWidth by animateFloatAsState(if (focused) 3f else 1f, tween(140, easing = Motion.EaseOut), label = "cell-border-width")
    val borderColor by animateColorAsState(
        if (focused) Palette.Focus else Palette.Hairline,
        tween(140, easing = Motion.EaseOut), label = "cell-border-color",
    )
    Column(
        modifier
            .fillMaxWidth()
            .smoothBackground(Color.White.copy(alpha = 0.05f), 16.dp)
            .smoothBorder(borderWidth.dp, borderColor, 16.dp)
            .padding(start = 14.dp, end = 10.dp, top = 2.dp, bottom = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                labelText,
                style = Type.Body,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            content { owner, on ->
                if (on) focusOwner = owner else if (focusOwner === owner) focusOwner = null
            }
        }
        Box(Modifier.fillMaxWidth().height(HintRoom)) {
            if (!hint.isNullOrBlank()) {
                BasicText(
                    hint,
                    style = Type.Caption.copy(color = Palette.TertiaryLabel, lineHeight = 15.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 气泡里的一颗胶囊按钮: 聚焦时白底(和以前一样), 选中的打勾。 */
@Composable
private fun ChipButton(
    label: String,
    on: Boolean,
    chevron: Boolean = false,
    check: Boolean = true,
    icon: Glyph? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onFocus: (Any, Boolean) -> Unit,
    onKey: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null,
) {
    val owner = remember { Any() }
    val memory = LocalSheetFocus.current
    val ownRequester = remember { FocusRequester() }
    val holding = memory?.holding == true
    Pressable(
        onClick = onClick, focusRequester = focusRequester ?: ownRequester, focusedScale = 1f, onKey = onKey,
        enabled = !holding,
        // 同步回调: 气泡描边和按钮自己的白底必须同一帧开始变, 走 LaunchedEffect 会晚一帧
        onFocusState = { got ->
            onFocus(owner, got)
            if (got) memory?.last = focusRequester ?: ownRequester
        },
    ) { state ->
        val bg by animateColorAsState(
            when {
                state.focused -> Palette.Focus
                on -> Palette.FillStrong
                else -> Palette.Fill
            },
            tween(140, easing = Motion.EaseOut), label = "chip-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .drawBehind { drawRect(bg) }
                .height(30.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            icon?.let { GlyphIcon(it, fg, 13.dp) }
            if (check && on) GlyphIcon(Glyph.Check, fg, 12.dp)
            BasicText(label, style = Type.Body.copy(color = fg), maxLines = 1)
            if (chevron) GlyphIcon(Glyph.ChevronRight, fg, 13.dp)
        }
    }
}

/** 二选一或几选一: 每颗按钮都能用左右键走到, OK 选中。 */
@Composable
private fun <T> ChoiceCell(
    label: Int,
    hint: String?,
    options: List<Pair<T, Any>>,
    current: T,
    choose: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Cell(label, hint, modifier) { onFocus ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            options.forEach { (value, text) ->
                ChipButton(
                    if (text is Int) stringResource(text) else text.toString(),
                    on = current == value,
                    onClick = { choose(value) },
                    onFocus = onFocus,
                )
            }
        }
    }
}

/**
 * 「隐藏的卡片」的入口格子: 一颗格子两颗"数量 + >"的按钮(应用和直播), 按下去各自在自己上面
 * 浮出一个小窗口列出收起来的卡片。窗口开着的时候按键全在那颗按钮里处理(焦点从没离开过它),
 * 上下键换一张, OK 放回桌面, 左右或返回收起 —— 和横向的选项窗口刚好对齐(那边是左右换、下键收)。
 */
@Composable
private fun HiddenCell(
    label: Int,
    hint: String?,
    entries: List<HiddenEntry>,
    modifier: Modifier = Modifier,
) {
    Cell(label, hint, modifier) { onFocus ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            entries.forEach { entry -> HiddenChip(entry, onFocus) }
        }
    }
}

/** 一颗入口按钮: 它开自己那个窗口, 按键也归它管, 三角对准的就是它自己。 */
@Composable
private fun HiddenChip(entry: HiddenEntry, onFocus: (Any, Boolean) -> Unit) {
    val window = entry.window
    var chipTop by remember { mutableStateOf(0f) }
    var chipCenterX by remember { mutableStateOf(0f) }

    fun bump(e: androidx.compose.ui.input.key.KeyEvent) {
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) Sounds.limit()
    }

    Box(
        Modifier.onGloballyPositioned { c ->
            val b = c.boundsInRoot()
            chipTop = b.top
            chipCenterX = b.center.x
        },
    ) {
        ChipButton(
            entry.chipText,
            on = false, chevron = true, check = false,
            onClick = { window?.show(chipCenterX, chipTop, entry.onPick) },
            onFocus = onFocus,
            onKey = { e ->
                if (window == null || !window.open) {
                    false
                } else {
                    val down = e.type == KeyEventType.KeyDown
                    val last = entry.rows.lastIndex
                    when (e.nativeKeyEvent.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_UP -> {
                            if (down) {
                                if (window.index > 0) { window.index--; Sounds.focusMoved() } else bump(e)
                            }
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (down) {
                                if (window.index < last) { window.index++; Sounds.focusMoved() } else bump(e)
                            }
                            true
                        }
                        // 左右键也不再顺手收起窗口(以前按一下就没了), 只响撞边音;
                        // 收窗口统一用返回键。
                        NativeKeyEvent.KEYCODE_DPAD_LEFT,
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { bump(e); true }
                        NativeKeyEvent.KEYCODE_BACK -> {
                            if (down) window.hide()
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_CENTER,
                        NativeKeyEvent.KEYCODE_ENTER,
                        NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            // 打开它的那一下, 抬手也可能落在这里, 别顺手就把卡片放回去了。
                            if (down && SystemClock.uptimeMillis() - window.openedAt > 350) {
                                entry.rows.getOrNull(window.index)?.let {
                                    Sounds.select()
                                    window.pick(it.key)
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
            },
        )
    }
}

/** 只做事不选值的格子: 一颗或几颗按钮, 左右键换着走。 */
@Composable
private fun ActionCell(
    label: Int,
    hint: String?,
    actions: List<CellAction>,
    modifier: Modifier = Modifier,
) = ActionCell(stringResource(label), hint, actions, modifier)

@Composable
private fun ActionCell(
    labelText: String,
    hint: String?,
    actions: List<CellAction>,
    modifier: Modifier = Modifier,
) {
    Cell(labelText, hint, modifier) { onFocus ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            actions.forEach { a ->
                ChipButton(
                    a.text,
                    on = false,
                    chevron = a.chevron,
                    check = false,
                    icon = a.icon,
                    onClick = a.run,
                    onFocus = onFocus,
                )
            }
        }
    }
}

/**
 * 面板里的一个"迷你窗口"。
 *
 * 之前用 Popup 做: 那是独立窗口, 在当贝这类电视上要抢到焦点很不稳, 经常按了 OK
 * 什么都不出来(3.32~3.36 一直没解决)。现在不用独立窗口了, 窗口直接画在面板最上层,
 * 按键仍然由入口按钮那颗按钮自己处理, 焦点一直没离开过它, 打开/移动/确认/关上
 * 都不会再丢事件。
 */
private class MenuWindow {
    var open by mutableStateOf(false)
    var labels by mutableStateOf<List<String>>(emptyList())
    var selected = 0
    var index by mutableStateOf(0)
    var anchorX = 0f
    var anchorY = 0f
    var openedAt = 0L
    var pick: (Int) -> Unit = {}

    fun show(labels: List<String>, selected: Int, anchorX: Float, anchorY: Float, pick: (Int) -> Unit) {
        this.labels = labels
        this.selected = selected
        this.index = selected
        this.anchorX = anchorX
        this.anchorY = anchorY
        this.pick = pick
        this.openedAt = SystemClock.uptimeMillis()
        open = true
    }

    fun hide() { open = false }
}

/** 面板里最后一颗拿到焦点的按钮: 密码框这类临时层关掉后按它回位。 */
private class SheetFocusMemory {
    var last: FocusRequester? = null
    /** 密码框盖着面板这段时间为 true: 面板里的按钮一律不可聚焦, 不跟密码框抢。 */
    var holding by mutableStateOf(false)
}

private val LocalSheetFocus = compositionLocalOf<SheetFocusMemory?> { null }

private val LocalMenuWindow = compositionLocalOf<MenuWindow?> { null }

/**
 * 「隐藏的卡片」那颗入口按钮按下后浮出来的小窗口。
 *
 * 一个窗口只管"开着没有、亮第几行、贴着哪颗按钮", 内容(卡片列表)由外面每次合成时传进来,
 * 所以放回一张卡片后窗口自己就短一截, 不用去同步一份副本。
 *
 * 和选项窗口同一个气泡: 同一块磨砂、同一圈光边、底下一个对准按钮的三角。这两个入口以前
 * 打开的是整屏弹层(盖住整个面板、居中一张大卡片), 跟面板里的小窗口完全不是一套。
 */
private class ListWindow {
    var open by mutableStateOf(false)
    var index by mutableStateOf(0)
    var anchorX = 0f
    var anchorY = 0f
    var openedAt = 0L
    var pick: (String) -> Unit = {}

    fun show(anchorX: Float, anchorY: Float, pick: (String) -> Unit) {
        this.anchorX = anchorX
        this.anchorY = anchorY
        this.index = 0
        this.pick = pick
        this.openedAt = SystemClock.uptimeMillis()
        open = true
    }

    fun hide() { open = false }
}

/** 小窗口里的一行: 应用带首页那块图标, 直播源只有名字(前面一颗播放图标)。 */
private class HiddenRow(
    val key: String,
    val label: String,
    val app: AppEntry? = null,
    val live: LiveSource? = null,
)

/** 一颗格子里的一个入口按钮: 它自己那颗窗口、里面列的行、放回去要调用谁。 */
private class HiddenEntry(
    val window: ListWindow?,
    val chipText: String,
    val rows: List<HiddenRow>,
    val onPick: (String) -> Unit,
)

/** 面板里两颗入口各自一个小窗口, 一块提供下去。 */
private class PopupHost {
    val apps = ListWindow()
    val live = ListWindow()
}

private val LocalPopupHost = compositionLocalOf<PopupHost?> { null }

/**
 * 选项多的格子: 入口是"当前值 + >"那颗按钮(和「隐藏的卡片」同一套)。按下 OK,
 * 入口按钮正上方浮出一个迷你窗口, 横向列出全部选项, 打勾那颗先亮起来; 左右键在
 * 窗口里换, 到头响一声撞边音, 上键也响一声; 下键或返回键收起来。入口按钮本身不打勾。
 */
@Composable
private fun <T> MenuCell(
    label: Int,
    hint: String?,
    options: List<Pair<T, Any>>,
    current: T,
    choose: (T) -> Unit,
    modifier: Modifier = Modifier,
    leading: CellAction? = null,
) {
    val window = LocalMenuWindow.current
    val index = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
    val labels = options.map { (_, text) -> if (text is Int) stringResource(text) else text.toString() }
    // 窗口要对准"入口按钮"的顶边中点, 所以量按钮的位置, 不是量整格。
    var chipTop by remember { mutableStateOf(0f) }
    var chipCenterX by remember { mutableStateOf(0f) }

    fun bump(e: androidx.compose.ui.input.key.KeyEvent) {
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) Sounds.limit()
    }

    Box {
        Cell(label, hint, modifier) { onFocus ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                leading?.let {
                    ChipButton(it.text, on = false, chevron = it.chevron, check = false, icon = it.icon, onClick = it.run, onFocus = onFocus)
                }
                Box(
                    Modifier.onGloballyPositioned { c ->
                        val b = c.boundsInRoot()
                        chipTop = b.top
                        chipCenterX = b.center.x
                    },
                ) {
                    ChipButton(
                        labels.getOrElse(index) { "" },
                        on = false, chevron = true, check = false,
                        onClick = {
                            window?.show(labels, index, chipCenterX, chipTop) { choose(options[it].first) }
                        },
                        onFocus = onFocus,
                        // 窗口开着的时候, 按键全在这颗按钮里处理: 焦点从没离开过它。
                        onKey = { e ->
                            if (window == null || !window.open) {
                                false
                            } else {
                                val down = e.type == KeyEventType.KeyDown
                                when (e.nativeKeyEvent.keyCode) {
                                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                                        if (down) {
                                            if (window.index > 0) {
                                                window.index--
                                                Sounds.focusMoved()
                                            } else bump(e)
                                        }
                                        true
                                    }
                                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        if (down) {
                                            if (window.index < window.labels.lastIndex) {
                                                window.index++
                                                Sounds.focusMoved()
                                            } else bump(e)
                                        }
                                        true
                                    }
                                    // 方向键只用来在窗口里换选项, 不再顺手关窗口:
                                    // 上下都一样是撞边提示音(以前下键会直接收起, 往上按一格
                                    // 就没了, 想看完所有选项只能重开)。
                                    NativeKeyEvent.KEYCODE_DPAD_UP,
                                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> { bump(e); true }
                                    NativeKeyEvent.KEYCODE_BACK -> {
                                        if (down) window.hide()
                                        true
                                    }
                                    NativeKeyEvent.KEYCODE_DPAD_CENTER,
                                    NativeKeyEvent.KEYCODE_ENTER,
                                    NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                        // 打开它的那一下, 抬手也可能落在这里, 别顺手就把值选了。
                                        if (down && SystemClock.uptimeMillis() - window.openedAt > 350) {
                                            Sounds.select()
                                            window.pick(window.index)
                                            window.hide()
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 迷你窗口那一层: 画在面板最上层, 位置对着入口按钮的顶边, 下沿带一个指向它的小三角。 */
@Composable
private fun MenuWindowLayer(window: MenuWindow) {
    val tailH = MiniTailHeight
    val gap = 10.dp
    val edge = 8.dp
    var originX by remember { mutableStateOf(0f) }
    var originY by remember { mutableStateOf(0f) }
    var winSize by remember { mutableStateOf(IntSize.Zero) }
    // 这里只用普通状态: MutableTransitionState 没有 transition 在跑, currentState 永远是 false,
    // 窗口就一直停在 alpha=0(看不见), 表现正是"按了看似打开、其实屏幕上什么都没有"。
    var shown by remember { mutableStateOf(false) }
    // 第一次合成时还没量到窗口大小, offset 只能先用(按钮位置, 0 高)顶上:
    // 那一帧窗口会出现在按钮的正上方, 随即跳到该在的位置, 看上去就是"从别处飞过来"。
    // 所以量到宽高之前一律不显示, 量到了才开始淡入放大。
    var placed by remember { mutableStateOf(false) }
    // 退出也要有动画: 直接 if (!window.open) return 的话窗口是一帧消失的, 只有进场有动画。
    // 关掉后先把它留在合成树里播完淡出+缩小, 再撤走。
    var alive by remember { mutableStateOf(window.open) }
    // 换一个入口按钮重开(openedAt 变了)时, 窗口内容、宽度都不一样了: 旧状态要清掉重新量,
    // 否则会先按上一个窗口的宽度摆一次位置再跳过来 —— 就是"打开 B 时抖一下"。
    LaunchedEffect(window.openedAt) {
        winSize = IntSize.Zero
        placed = false
        shown = false
    }
    // 进场: 量到尺寸并且窗口是开着的才淡入放大(关掉期间不要偷偷把它亮回来)。
    LaunchedEffect(placed, window.open) { if (placed && window.open) shown = true }
    // 退场: 关掉后留住 150ms 播淡出+缩小, 再撤走; 中途又打开就取消。
    LaunchedEffect(window.open) {
        if (window.open) {
            // 3.44 就是漏了这一句: 图层一直挂在合成树里, alive 一开始是 false,
            // 打开时没人把它置回 true, 于是永远走 if (!alive) return —— 窗口彻底打不开。
            alive = true
        } else if (alive) {
            shown = false
            delay(MiniExitMs)
            alive = false
        }
    }
    if (!alive) return
    val scale by animateFloatAsState(
        if (shown) 1f else 0.92f,
        // 不要回弹: 回弹本身看着就像抖一下。
        spring(dampingRatio = 1f, stiffness = 800f), label = "mini-scale",
    )
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(120, easing = Motion.EaseOut), label = "mini-alpha")

    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned {
            val at = it.positionInRoot()
            originX = at.x
            originY = at.y
        },
    ) {
        val density = LocalDensity.current
        val fullW = with(density) { maxWidth.toPx() }
        val ax = window.anchorX - originX
        val ay = window.anchorY - originY
        val gapPx = with(density) { gap.toPx() }
        val edgePx = with(density) { edge.toPx() }
        val x = if (winSize.width == 0) ax else
            (ax - winSize.width / 2f).coerceIn(edgePx, (fullW - winSize.width - edgePx).coerceAtLeast(edgePx))
        // 窗口本体和下面的三角是同一块玻璃、同一圈光边: 整块交给 bubbleGlass 画。
        // 三角的中点落在入口按钮的中心 (ax - x 就是按钮中心离窗口左边多远)。
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), (ay - winSize.height - gapPx).roundToInt()) }
                .onGloballyPositioned {
                    winSize = it.size
                    if (it.size.width > 0 && it.size.height > 0) placed = true
                }
                // 顺序要紧: graphicsLayer 必须在 bubbleGlass 外面。bubbleGlass 是 drawWithCache
                // 画的, 排在后面前它就落在图层之外, 图层 alpha/scale 管不到它 —— 上一版正是这样,
                // 磨砂和光边在"还没量到尺寸"的那一帧按错位的 offset 先画了出来, 看着还是从按钮处飞来。
                .drawWithContent {
                    // 尺寸未知的那一帧整块不画(offset 这时还退化成贴着按钮的位置)。
                    if (placed) drawContent()
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .bubbleGlass(
                    corner = 20.dp,
                    tailHalf = 9.dp,
                    tailHeight = tailH,
                    tailX = { with(density) { (ax - x).toDp() } },
                ),
        ) {
            Row(
                Modifier
                    .height(MiniMenuHeight)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                window.labels.forEachIndexed { i, text ->
                    val lit = i == window.index
                    val bg by animateColorAsState(if (lit) Palette.Focus else Palette.Fill, tween(120, easing = Motion.EaseOut), label = "mini-bg")
                    val fg = if (lit) Palette.OnFocus else Palette.Label
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .drawBehind { drawRect(bg) }
                            .height(30.dp)
                            .padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        BasicText(text, style = Type.Body.copy(color = fg), maxLines = 1)
                        if (i == window.selected) GlyphIcon(Glyph.Check, fg, 12.dp)
                    }
                }
            }
            // 三角那一截的高度: 玻璃自己画, 这里只负责把窗口量到"行 + 三角"。
            Spacer(Modifier.height(tailH))
        }
    }
}

/**
 * 直播源的图标: 手机上传的自定义图先, 没有就用预设源那张(央视/央视频/东方明珠…), 都没有
 * 才退回纯色 + 播放图标。和首页直播卡、直播菜单里那张是同一张图。
 */
@Composable
private fun LiveRowIcon(source: LiveSource, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, source.id, source.iconTs) {
        value = withContext(Dispatchers.IO) {
            val upload = java.io.File(context.filesDir, "live_icon_${source.id}.jpg")
            val bmp = if (upload.exists()) {
                runCatching { android.graphics.BitmapFactory.decodeFile(upload.absolutePath) }.getOrNull()
            } else {
                liveSourceIconResId(source)?.let { id ->
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                    runCatching { android.graphics.BitmapFactory.decodeResource(context.resources, id, opts) }.getOrNull()
                }
            }
            bmp?.asImageBitmap()
        }
    }
    val face = icon
    if (face != null) {
        androidx.compose.foundation.Image(
            bitmap = face, contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(6.dp)),
        )
    } else {
        val (top, bottom) = liveColors[source.colorIndex % liveColors.size]
        Box(
            modifier.clip(RoundedCornerShape(6.dp))
                .background(Brush.verticalGradient(listOf(Color(top), Color(bottom)))),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.Play, Palette.Label, 13.dp) }
    }
}

/**
 * 收起卡片的小窗口: 和选项窗口同一个气泡(磨砂 + 一圈光边 + 对准按钮的三角), 内容是一列卡片。
 * 上下键换一张、OK 放回桌面、左右或返回收起。
 */
@Composable
private fun ListWindowLayer(
    window: ListWindow,
    empty: String,
    items: List<HiddenRow>,
) {
    val tailH = MiniTailHeight
    val gap = 10.dp
    val edge = 8.dp
    var originX by remember { mutableStateOf(0f) }
    var originY by remember { mutableStateOf(0f) }
    var winSize by remember { mutableStateOf(IntSize.Zero) }
    var shown by remember { mutableStateOf(false) }
    var placed by remember { mutableStateOf(false) }
    var alive by remember { mutableStateOf(window.open) }
    LaunchedEffect(window.openedAt) {
        winSize = IntSize.Zero
        placed = false
        shown = false
    }
    LaunchedEffect(placed, window.open) { if (placed && window.open) shown = true }
    LaunchedEffect(window.open) {
        if (window.open) {
            alive = true
        } else if (alive) {
            shown = false
            delay(MiniExitMs)
            alive = false
        }
    }
    // 放回一张卡片后列表短一截: 停在末尾的那一行要跟着回到最后一张, 不能指向不存在的位置。
    LaunchedEffect(items.size) {
        if (window.index > items.lastIndex) window.index = items.lastIndex.coerceAtLeast(0)
    }
    if (!alive) return
    val scale by animateFloatAsState(
        if (shown) 1f else 0.92f,
        spring(dampingRatio = 1f, stiffness = 800f), label = "list-scale",
    )
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(120, easing = Motion.EaseOut), label = "list-alpha")

    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned {
            val at = it.positionInRoot()
            originX = at.x
            originY = at.y
        },
    ) {
        val density = LocalDensity.current
        val fullW = with(density) { maxWidth.toPx() }
        val ax = window.anchorX - originX
        val ay = window.anchorY - originY
        val gapPx = with(density) { gap.toPx() }
        val edgePx = with(density) { edge.toPx() }
        val x = if (winSize.width == 0) ax else
            (ax - winSize.width / 2f).coerceIn(edgePx, (fullW - winSize.width - edgePx).coerceAtLeast(edgePx))
        val rowH = 36.dp
        val scroll = rememberScrollState()
        val rowPx = with(density) { rowH.toPx() }
        LaunchedEffect(window.index) {
            runCatching { scroll.animateScrollTo((window.index * (rowPx + with(density) { 4.dp.toPx() })).roundToInt()) }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), (ay - winSize.height - gapPx).roundToInt()) }
                .onGloballyPositioned {
                    winSize = it.size
                    if (it.size.width > 0 && it.size.height > 0) placed = true
                }
                .drawWithContent { if (placed) drawContent() }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .bubbleGlass(
                    corner = 20.dp,
                    tailHalf = 9.dp,
                    tailHeight = tailH,
                    tailX = { with(density) { (ax - x).toDp() } },
                ),
        ) {
            Column(Modifier.width(300.dp).padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
                // 标题行去掉了, 顶部留一点内边距就够。
                Spacer(Modifier.height(2.dp))
                if (items.isEmpty()) {
                    BasicText(
                        empty, style = Type.Body.copy(color = Palette.TertiaryLabel),
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                    )
                } else {
                    Column(
                        Modifier.heightIn(max = 220.dp).verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items.forEachIndexed { i, item ->
                            val lit = i == window.index
                            // 一个进度同时驱动"行高亮""图标描边""文字颜色": 以前行底色是 120ms
                            // 渐变的, 而图标那圈描边是布尔值直接跳, 看起来就是描边先亮、底色后到。
                            val t by animateFloatAsState(
                                if (lit) 1f else 0f,
                                tween(120, easing = Motion.EaseOut), label = "list-row",
                            )
                            val bg = androidx.compose.ui.graphics.lerp(Palette.Fill, Palette.Focus, t)
                            val fg = androidx.compose.ui.graphics.lerp(Palette.Label, Palette.OnFocus, t)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(50))
                                    .drawBehind { drawRect(bg) }
                                    .height(rowH)
                                    .padding(horizontal = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                // 图标要在: 应用用首页那块切好的图标, 直播源给一颗播放图标。
                                val iconBox = Modifier.size(44.dp, 25.dp)
                                when {
                                    item.app != null -> MiniTile(item.app, lit, iconBox, focusT = t)
                                    item.live != null -> LiveRowIcon(item.live, iconBox)
                                    else -> Box(
                                        iconBox.clip(RoundedCornerShape(6.dp)).drawBehind { drawRect(Palette.FillStrong) },
                                        contentAlignment = Alignment.Center,
                                    ) { GlyphIcon(Glyph.Play, Palette.Label, 13.dp) }
                                }
                                BasicText(
                                    item.label,
                                    style = Type.Body.copy(color = fg),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(tailH))
        }
    }
}

/** 「按键音量」: 0..100, 只靠左右键增减(遥控器友好)。改一个数就立即生效, 不用退出面板。 */
@Composable
private fun KeyVolumeCell() {
    var bumpedEdge by remember { mutableStateOf(0) }
    fun step(delta: Int) {
        val now = AerialSettings.keyVolume
        val next = (now + delta).coerceIn(0, 100)
        if (next != now) {
            bumpedEdge = 0
            Sounds.step()
            AerialSettings.chooseKeyVolume(next)
        } else if (bumpedEdge != delta) {
            // 到头的"撞边"声只响一下: 停在端点继续按不再重复, 离开再回来才算新的一次。
            bumpedEdge = delta
            Sounds.limit()
        }
    }
    // 按 OK 静音 / 恢复, 和上面控制中心那条系统音量同一套手感;
    // 恢复时回到静音之前那个数, 而不是固定跳某个值。
    var restoreTo by remember { mutableStateOf(if (AerialSettings.keyVolume > 0) AerialSettings.keyVolume else 60) }
    fun toggleMute() {
        val now = AerialSettings.keyVolume
        if (now > 0) {
            restoreTo = now
            Sounds.select()
            AerialSettings.chooseKeyVolume(0)
        } else {
            Sounds.select()
            AerialSettings.chooseKeyVolume(restoreTo.coerceIn(1, 100))
        }
    }
    Cell(R.string.st_key_volume, stringResource(R.string.st_key_volume_hint)) { onFocus ->
        val owner = remember { Any() }
        Pressable(
            onClick = { toggleMute() },
            focusedScale = 1f,
            onKey = { e ->
                val code = e.nativeKeyEvent.keyCode
                if (code != NativeKeyEvent.KEYCODE_DPAD_LEFT && code != NativeKeyEvent.KEYCODE_DPAD_RIGHT) return@Pressable false
                if (e.type == KeyEventType.KeyDown) step(if (code == NativeKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                true
            },
        ) { state ->
            LaunchedEffect(state.focused) { onFocus(owner, state.focused) }
            // 和按钮一样: 聚焦时白底, 没聚焦时淡底, 内容颜色随之反相。
            val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "keyvol-bg")
            val fg = if (state.focused) Palette.OnFocus else Palette.Label
            val track = if (state.focused) Palette.OnFocus.copy(alpha = 0.22f) else Palette.Label.copy(alpha = 0.2f)
            val volume = AerialSettings.keyVolume
            // 条形跟着数值走一段动画, 不再瞬时跳变。
            val fraction by animateFloatAsState(
                targetValue = volume / 100f,
                animationSpec = tween(160, easing = LinearEasing),
                label = "keyvol-level",
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .drawBehind { drawRect(bg) }
                    .height(30.dp)
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                // 喇叭图标放在滑块条的左边, 音量格数用 level 表现, 不额外写字。
                GlyphIcon(
                    if (volume == 0) Glyph.SpeakerMuted else Glyph.Speaker,
                    fg,
                    16.dp,
                    level = fraction,
                )
                // 滑块条自己撑满数字左边的空位: 数字靠右, 条随宽度伸缩, 不再是一个固定长度的条。
                Canvas(Modifier.weight(1f).height(5.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(track, cornerRadius = r)
                    drawRoundRect(fg, size = GeometrySize(size.width * fraction, size.height), cornerRadius = r)
                }
                // 数字位置宽度固定: 按「100」和「已静音」里更宽的那个留够, 切换时不拉长也不缩。
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                val mutedLabel = stringResource(R.string.cc_muted)
                val density = androidx.compose.ui.platform.LocalDensity.current
                val labelW = remember(mutedLabel, density) {
                    val a = measurer.measure("100", Type.Body).size.width
                    val b = measurer.measure(mutedLabel, Type.Body).size.width
                    with(density) { maxOf(a, b).toDp() }
                }
                BasicText(
                    if (volume == 0) mutedLabel else volume.toString(),
                    style = Type.Body.copy(color = fg, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                    modifier = Modifier.width(labelW), maxLines = 1,
                )
            }
        }
    }
}

/** 天气位置: 能选中的一格, 把"这是哪、现在怎样"显示出来; 城市在手机上设置。 */
@Composable
private fun WeatherCell() {
    val place = Weather.place
    val w = Weather.now
    val kind = w?.let {
        stringResource(
            when (it.kind) {
                WeatherKind.Clear -> R.string.wx_clear
                WeatherKind.PartlyCloudy -> R.string.wx_partly_cloudy
                WeatherKind.Cloudy, WeatherKind.Wind -> R.string.wx_cloudy
                WeatherKind.Fog, WeatherKind.Haze, WeatherKind.Dust, WeatherKind.Sand -> R.string.wx_fog
                WeatherKind.Drizzle, WeatherKind.LightRain, WeatherKind.ModerateRain,
                WeatherKind.HeavyRain, WeatherKind.ViolentRain, WeatherKind.FreezingRain,
                WeatherKind.Typhoon -> R.string.wx_rain
                WeatherKind.LightSnow, WeatherKind.ModerateSnow, WeatherKind.HeavySnow,
                WeatherKind.SnowStorm, WeatherKind.Sleet -> R.string.wx_snow
                WeatherKind.Thunder, WeatherKind.ThunderHail, WeatherKind.Hail,
                WeatherKind.SandStorm, WeatherKind.Tornado -> R.string.wx_thunder
            },
        )
    }
    val shown = if (w != null) listOfNotNull(place, "${w.celsius}° $kind").joinToString(" · ") else stringResource(R.string.wp_weather_none)
    // 只用一条提示: 以前把"城市在手机上设置…"接在它后面, 两句话说的是同一件事。
    val hint = stringResource(if (Weather.chosen) R.string.wp_weather_chosen else R.string.wp_weather_guessed)
    Cell(R.string.wp_weather_city, hint) { onFocus ->
        val owner = remember { Any() }
        Pressable(onClick = {}, focusedScale = 1f) { state ->
            LaunchedEffect(state.focused) { onFocus(owner, state.focused) }
            val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "weather-bg")
            val fg = if (state.focused) Palette.OnFocus else Palette.SecondaryLabel
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .drawBehind { drawRect(bg) }
                    .height(30.dp)
                    .padding(horizontal = 10.dp)
                    .widthIn(max = 210.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(shown, style = Type.Body.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** B 站扫码要显示在右侧那张卡上, 所以状态提到这一层, 面板和卡片共用一份。 */
private class BiliMirror {
    var focused by mutableStateOf(false)
    var loggedIn by mutableStateOf(BiliAccount.loggedIn)
    var uid by mutableStateOf(BiliAccount.uid)
    var state by mutableStateOf<BiliLoginState?>(null)
    var qr by mutableStateOf<ImageBitmap?>(null)
}

/**
 * B 站账号: 没登录时按钮是「扫码登录」, 按下去右侧那张卡就换成 B 站登录码;
 * 登录后标题写成「B站账号（已登录 · UID）」, 按钮是「退出登录」。
 */
@Composable
private fun BiliCell(mirror: BiliMirror) {
    val scope = rememberCoroutineScope()

    fun startLogin() {
        mirror.qr = null
        mirror.state = null
        scope.launch {
            BiliLogin.login { s ->
                mirror.state = s
                when (s) {
                    is BiliLoginState.QrReady -> {
                        // 回调本身就在 BiliLogin 的 IO 线程里, 直接同步编码二维码。
                        mirror.qr = PhoneServer.qr(s.qrUrl).asImageBitmap()
                    }
                    BiliLoginState.Success -> {
                        mirror.loggedIn = BiliAccount.loggedIn
                        mirror.uid = BiliAccount.uid
                    }
                    BiliLoginState.Expired, is BiliLoginState.Failed -> mirror.qr = null
                    else -> {}
                }
            }
        }
    }

    val action = if (mirror.loggedIn) {
        CellAction(stringResource(R.string.st_bili_logout), chevron = false) {
            BiliAccount.clear()
            mirror.loggedIn = false
            mirror.uid = null
            mirror.state = null
            mirror.qr = null
        }
    } else {
        CellAction(stringResource(R.string.st_bili_login), chevron = true) { startLogin() }
    }
    // 标题只写到"已登录"; UID 放进下面那行小字, 免得标题太长被截成省略号。
    val currentUid = mirror.uid
    val loggedUid = currentUid?.takeIf { mirror.loggedIn && it.isNotBlank() }
    val title = if (loggedUid != null) {
        stringResource(R.string.st_bili_account_on)
    } else {
        stringResource(R.string.st_bili_account)
    }
    val hint = if (loggedUid != null) {
        stringResource(R.string.st_bili_hint_uid, loggedUid)
    } else {
        stringResource(R.string.st_bili_account_hint)
    }
    // 焦点在不在这一格, 决定右侧那张卡显示什么。
    val focusOwner = remember { mutableStateOf<Any?>(null) }
    Cell(title, hint) { onFocus ->
        ChipButton(
            action.text,
            on = false, chevron = action.chevron, check = false,
            onClick = action.run,
            onFocus = { owner, focused ->
                onFocus(owner, focused)
                if (focused) {
                    focusOwner.value = owner
                } else if (focusOwner.value === owner) {
                    focusOwner.value = null
                }
                mirror.focused = focusOwner.value != null
            },
        )
    }
}

/** 登录状态的一句话说明, 给右侧那张卡用。 */
@Composable
private fun biliStatusText(state: BiliLoginState?): String? = when (state) {
    is BiliLoginState.QrReady -> stringResource(R.string.st_bili_qr_ready)
    BiliLoginState.WaitingScan -> stringResource(R.string.st_bili_waiting)
    BiliLoginState.ScannedConfirm -> stringResource(R.string.st_bili_scanned)
    BiliLoginState.Expired -> stringResource(R.string.st_bili_expired)
    is BiliLoginState.Failed -> stringResource(R.string.st_bili_failed)
    else -> null
}

/**
 * 右侧那张卡: 平时是"用手机或电脑设置"的二维码; 正在扫码登录 B 站时整张卡换成
 * B 站登录码和对应文字, 扫完或焦点离开就自动回到原来那张。
 */
@Composable
private fun PhoneCard(url: String?, qr: ImageBitmap?, bili: BiliMirror? = null) {
    val biliScan = bili != null && !bili.loggedIn && bili.focused && bili.qr != null
    val picture = if (biliScan) bili.qr else qr
    Column(Modifier.width(220.dp).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(176.dp).smoothBackground(Color.White, 18.dp).padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null) {
                Image(picture, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            } else {
                GlyphIcon(Glyph.Offline, Color.Black.copy(alpha = 0.35f), 40.dp)
            }
        }
        Spacer(Modifier.height(14.dp))
        BasicText(
            if (biliScan) stringResource(R.string.st_bili_scan_title) else stringResource(R.string.wp_phone_title),
            style = Type.Headline,
        )
        Spacer(Modifier.height(4.dp))
        val body = when {
            biliScan -> biliStatusText(bili?.state) ?: stringResource(R.string.st_bili_scan_hint)
            else -> stringResource(if (url != null) R.string.wp_phone_body else R.string.wp_phone_offline)
        }
        BasicText(
            body,
            style = Type.Caption.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
        if (!biliScan && url != null) {
            Spacer(Modifier.height(6.dp))
            // The whole address, token and all, to type into a computer's browser.
            BasicText(url.removePrefix("http://"), style = Type.Caption.copy(color = Palette.SecondaryLabel))
        }
    }
}


/** 两列网格的格间距。 */
private val CellGap = 14.dp

/** 每格下面给小字提示留的高度: 有没有提示都一样高, 焦点移动不会让整页跳。 */
private val HintRoom = 32.dp

/** 迷你窗口的高度: 上下留白 + 一颗胶囊按钮。 */
private val MiniMenuHeight = 46.dp

/** 迷你窗口下方指向按钮的三角形高度。 */
private val MiniTailHeight = 9.dp

/** 迷你窗口关掉后留在屏上播完淡出+缩小的时间。 */
private const val MiniExitMs = 150L

private val SheetHeight = 360.dp
private val SheetCorner = 30.dp

/**
 * How much of the screen's bottom the settled sheet hides whole: its height less its rounded top
 * corners, under which Home shows. Home drew all of itself under the opaque sheet every frame,
 * three full-screen layers and the tiles that nobody sees (Xiaomi MiTV-MFTR0).
 */
val SheetCovers = SheetHeight - SheetCorner
