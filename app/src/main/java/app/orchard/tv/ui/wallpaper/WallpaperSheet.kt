package app.orchard.tv.ui.wallpaper

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.Dp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Size

/**
 * Wallpaper and screensaver, chosen on the TV. A sheet rises from the bottom and leaves the top of
 * the screen showing Home, so a new wallpaper can be seen the moment it is chosen. A QR code on the
 * right opens the same settings on a phone, where sending photos is easy.
 */
@Composable
fun WallpaperSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    hiddenCount: Int,
    onHiddenApps: () -> Unit,
    hiddenLiveCount: Int = 0,
    onHiddenLiveSources: () -> Unit = {},
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
) {
    val context = LocalContext.current
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }

    BackHandler(enabled = visible, onBack = onDismiss)

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

    AnimatedVisibility(visible = visible, enter = fadeIn(tween(220, easing = Motion.EaseOut)), exit = fadeOut(tween(180, easing = Motion.EaseOut)), modifier = Modifier.fillMaxSize()) {
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

    // 分类: 焦点落在右缘导航的哪一个, 左边内容就换成哪一组。
    val tab = remember { mutableStateOf(PerCategory.Wallpaper) }
    // 每个分类一个焦点请求者: 打开面板落在第一个, 按返回键/右边缘回到当前那一类。
    val railReqs = remember { PerCategory.entries.map { FocusRequester() } }
    val railExit: () -> Unit = { runCatching { railReqs[tab.value.ordinal].requestFocus() } }
    // 焦点在右缘导航里时, 导航整体显示图标 + 名字, 面板往左扩;
    // 焦点进了左边的内容, 面板整体往右缩, 导航只剩图标(和当贝一样)。
    var railFocused by remember { mutableStateOf(true) }
    val panelWidth by animateDpAsState(if (railFocused) SheetWidth else SheetWidth - (RailWidth - RailWidthNarrow), tween(240, easing = Motion.EaseOut), label = "panel-w")
    val railWidth by animateDpAsState(if (railFocused) RailWidth else RailWidthNarrow, tween(240, easing = Motion.EaseOut), label = "rail-w")

    // The sheet as it opens, or (takeFocus false) drawn once nearly clear for the warm-up.
    val sheet: @Composable (takeFocus: Boolean) -> Unit = { takeFocus ->
        Row(
            Modifier
                .fillMaxHeight()
                .width(panelWidth)
                .consumeTaps()
                // Glass made from the wallpaper itself, opaque, as the Control Center's: what is
                // left of Home beside it stays sharp while you choose.
                .liquidGlass(SheetCorner)
                .padding(start = 28.dp, end = 8.dp, top = 28.dp, bottom = 24.dp),
        ) {
            val contentScroll = rememberScrollState()
            // 换分类时内容回到顶部。
            LaunchedEffect(tab.value) { contentScroll.scrollTo(0) }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(contentScroll)
                    .latestBringIntoView(contentScroll)
                    // 焦点在左边内容时按返回键, 不关面板, 先回到当前那一类导航上。
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Back) {
                            railExit(); true
                        } else false
                    },
            ) {
                CompositionLocalProvider(LocalRailExit provides railExit) {
                when (tab.value) {
                    PerCategory.Wallpaper -> {
                        CategoryTitle(Glyph.Photo, R.string.per_wallpaper)
                        WallpaperSection(takeFocus)
                        Spacer(Modifier.height(18.dp))
                        PhoneCard(phoneUrl, qr)
                    }
                    PerCategory.Home -> {
                        CategoryTitle(Glyph.Home, R.string.per_home)
                        HomeSection(hiddenCount, onHiddenApps, hiddenLiveCount, onHiddenLiveSources, onHomeLock)
                    }
                    PerCategory.Live -> {
                        CategoryTitle(Glyph.Play, R.string.per_live)
                        LiveSection()
                    }
                    PerCategory.Look -> {
                        CategoryTitle(Glyph.Sun, R.string.per_look)
                        LookSection()
                    }
                    PerCategory.Screensaver -> {
                        CategoryTitle(Glyph.Moon, R.string.per_screensaver)
                        ScreensaverSection(onStartScreensaver)
                    }
                    PerCategory.Weather -> {
                        CategoryTitle(Glyph.CloudSun, R.string.per_weather)
                        WeatherSection()
                    }
                    PerCategory.Family -> {
                        CategoryTitle(Glyph.Shield, R.string.per_family)
                        FamilySection(onPin, onKids)
                    }
                }
                }
                Spacer(Modifier.height(24.dp))
            }
            // 右缘导航: 平时只一条图标(贴着屏幕右缘); 焦点停上来才向左展开成图标 + 名字的高亮胶囊。
            Column(
                Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    // 焦点只要还在这条导航里(含它的子项), 就一直显示文字。
                    .onFocusChanged { railFocused = it.hasFocus },
                verticalArrangement = Arrangement.Center,
            ) {
                CompositionLocalProvider(LocalRailExit provides railExit) {
                    PerCategory.entries.forEachIndexed { i, category ->
                        RailItem(category, glyphOf(category), tab, railFocused, railReqs[i])
                    }
                }
                // 打开面板时焦点落在第一项分类上, 往左才进选项。
                LaunchedEffect(takeFocus) {
                    if (!takeFocus) return@LaunchedEffect
                    withFrameNanos { }
                    runCatching { railReqs.first().requestFocus() }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        // The first opening composed the sheet and gave the GPU its pictures, glyphs and shaders
        // for the first time: frames of 250 and 316 ms, 1.16 s for the slide-in, against about
        // 140 ms at worst on later openings (Xiaomi MiTV-MFTR0). Drawn once at 1% while Home is
        // idle, without focus (HomeScreen, as Control Center), the first opening is like the next.
        if (warm && !visible) Box(Modifier.graphicsLayer { alpha = 0.01f }) { sheet(true) }
        AnimatedVisibility(
            visibleState = shown,
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + slideInHorizontally(tween(380, easing = Motion.Drawer)) { it },
            exit = fadeOut(tween(160, easing = Motion.EaseOut)) + slideOutHorizontally(tween(200, easing = Motion.EaseOut)) { it },
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
/** 选项按钮滑到头再按右键 / 按返回键时, 焦点要回到右缘那条导航上。 */
private val LocalRailExit = staticCompositionLocalOf<(() -> Unit)?> { null }

/** 设置面板的分类, 对应右缘导航的一个图标。 */
private enum class PerCategory(val label: Int) {
    Wallpaper(R.string.per_wallpaper),
    Home(R.string.per_home),
    Live(R.string.per_live),
    Look(R.string.per_look),
    Screensaver(R.string.per_screensaver),
    Weather(R.string.per_weather),
    Family(R.string.per_family),
}

private fun glyphOf(category: PerCategory): Glyph = when (category) {
    PerCategory.Wallpaper -> Glyph.Photo
    PerCategory.Home -> Glyph.Home
    PerCategory.Live -> Glyph.Play
    PerCategory.Look -> Glyph.Sun
    PerCategory.Screensaver -> Glyph.Moon
    PerCategory.Weather -> Glyph.CloudSun
    PerCategory.Family -> Glyph.Shield
}

/**
 * 右缘导航的一项: 没被选中就只有一个图标; 焦点停上来才展开成"图标 + 分类名"的高亮胶囊,
 * 同时左边内容也跟着切过去(不用按 OK)。
 */
@Composable
private fun RailItem(
    category: PerCategory,
    glyph: Glyph,
    tab: androidx.compose.runtime.MutableState<PerCategory>,
    labels: Boolean,
    focusRequester: FocusRequester? = null,
) {
    Pressable(
        onClick = { tab.value = category },
        focusRequester = focusRequester,
        focusedScale = 1.0f,
        modifier = Modifier.fillMaxWidth(),
    ) { state ->
        LaunchedEffect(state.focused) { if (state.focused) tab.value = category }
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Color.Transparent, tween(140, easing = Motion.EaseOut), label = "rail-bg")
        // 焦点在左边内容时, 当前这一类留着白色图标, 一眼还能看出停在哪一类。
        val fg = when {
            state.focused -> Palette.OnFocus
            !labels && tab.value == category -> Palette.Label
            else -> Palette.SecondaryLabel
        }
        Row(
            Modifier
                .padding(end = 8.dp)
                .fillMaxWidth()
                .height(if (labels) 52.dp else 56.dp)
                .clip(RoundedCornerShape(50))
                .drawBehind { drawRect(bg) }
                // 宽度固定: 名字长短只会影响文字本身, 不会把图标左右挪。
                .padding(start = if (labels) 18.dp else 0.dp, end = if (labels) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center,
        ) {
            GlyphIcon(glyph, fg, if (labels) 28.dp else 36.dp)
            if (labels) {
                BasicText(
                    stringResource(category.label),
                    style = Type.Body.copy(color = fg),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

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
    if (Wallpapers.isVideo()) Group { note ->
        Choices(
            R.string.st_play, stringResource(R.string.st_play_hint), note,
            listOf(false to R.string.st_play_still, true to R.string.st_play_always),
            AerialSettings.wallpaperAlwaysMoves, AerialSettings::chooseWallpaperAlwaysMoves, first = true,
        )
    }
    // 上游 #1314: 只有"我的照片, 轮流"才需要问多久换一张, 别的壁纸没有下一张
    if (Wallpapers.selectedId == Wallpapers.ROTATE_PHOTOS) Group { note ->
        Choices(
            R.string.st_photo_every, stringResource(R.string.st_photo_every_hint), note,
            AerialSettings.PHOTO_MINUTES.map { m ->
                m to when {
                    m >= AerialSettings.DAY_MINUTES -> stringResource(R.string.st_photo_every_day)
                    m >= 60 -> stringResource(R.string.kids_h, m / 60)
                    else -> stringResource(R.string.kids_m, m)
                }
            },
            AerialSettings.photoMinutes, { AerialSettings.choosePhotoMinutes(it); Wallpapers.rotateIfDue() }, first = true,
        )
    }
    Spacer(Modifier.height(12.dp))
}

/** 主屏幕: 怎么摆、叫什么、露多少、哪些卡片收起来了。 */
@Composable
private fun HomeSection(
    hiddenCount: Int,
    onHiddenApps: () -> Unit,
    hiddenLiveCount: Int,
    onHiddenLiveSources: () -> Unit,
    onHomeLock: (on: Boolean) -> Unit,
) {
    Group { note ->
        Choices(
            R.string.st_columns, stringResource(R.string.st_columns_hint), note,
            AerialSettings.HOME_COLUMNS.map { it to it.toString() },
            AerialSettings.homeColumns, AerialSettings::chooseHomeColumns, first = true,
        )
        Choices(
            R.string.st_names, stringResource(R.string.st_names_hint), note,
            listOf(false to R.string.st_names_focused, true to R.string.st_names_all),
            AerialSettings.showAllNames, AerialSettings::chooseShowAllNames,
        )
        Choices(
            R.string.st_plate, stringResource(R.string.st_plate_hint), note,
            listOf(true to R.string.st_plate_glass, false to R.string.st_plate_none),
            AerialSettings.homePlate, AerialSettings::chooseHomePlate,
        )
        // 双排布局下也保留这个选项: 它也控制二级菜单(直播源)露出下一排的效果。
        Choices(
            R.string.st_peek, stringResource(R.string.st_peek_hint), note,
            listOf(true to R.string.st_peek_show, false to R.string.st_peek_hide),
            AerialSettings.peekNextRow, AerialSettings::choosePeekNextRow,
        )
        Choices(
            R.string.st_layout, stringResource(R.string.st_home_layout_hint), note,
            listOf(true to R.string.st_home_one_row, false to R.string.st_home_standard),
            AerialSettings.oneRowHome, AerialSettings::chooseOneRowHome,
        )
        SettingRow(R.string.st_hidden, stringResource(R.string.st_hidden_hint), note) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionChip(
                    if (hiddenCount == 0) stringResource(R.string.st_hidden_app_none)
                    else stringResource(R.string.st_hidden_app_count, hiddenCount),
                    Glyph.ChevronRight,
                    onHiddenApps,
                )
                ActionChip(
                    if (hiddenLiveCount == 0) stringResource(R.string.st_hidden_live_none)
                    else stringResource(R.string.st_hidden_live_count, hiddenLiveCount),
                    Glyph.ChevronRight,
                    onHiddenLiveSources,
                )
            }
        }
        // 上锁: 长按 OK / Menu 不再弹应用菜单, 免得误移动、误隐藏、误卸载
        Choices(
            R.string.st_home_lock, stringResource(R.string.st_home_lock_hint), note,
            listOf(true to R.string.st_on, false to R.string.st_off),
            AerialSettings.homeLocked, onHomeLock,
        )
    }
    Spacer(Modifier.height(12.dp))
}

/** 直播与卡片: 背景预览、卡顿灵敏度、B 站账号。 */
@Composable
private fun LiveSection() {
    Group { note ->
        // 二创：焦点选中 IPTV 应用时背景播放相关直播
        Choices(
            R.string.st_live_preview,
            "焦点选中IPTV应用时背景播放相关直播",
            note,
            listOf(true to R.string.st_on, false to R.string.st_off),
            AerialSettings.livePreviewEnabled, AerialSettings::chooseLivePreview, first = true,
        )
        // 画质不再有全局开关: 每张直播卡的画质在直播菜单里按台选。
        Choices(
            R.string.st_live_jank,
            "高灵敏及早切备用源, 低灵敏减少误切, 关则不自动切换",
            note,
            listOf(
                AerialSettings.JankSensitivity.Off to "关闭",
                AerialSettings.JankSensitivity.Low to "低",
                AerialSettings.JankSensitivity.Medium to "中",
                AerialSettings.JankSensitivity.High to "高",
            ),
            AerialSettings.jankSensitivity, AerialSettings::chooseJankSensitivity,
        )
        BiliLoginRow(note)
    }
    Spacer(Modifier.height(12.dp))
}

/** 外观与声音: 焦点框、特效、开场遮罩、按键音与它的音量。 */
@Composable
private fun LookSection() {
    Group { note ->
        Choices(
            R.string.st_focus_style,
            stringResource(if (AerialSettings.focusStyle == FocusStyle.HighContrast) R.string.st_focus_high_hint else R.string.st_focus_standard_hint),
            note,
            listOf(FocusStyle.HighContrast to R.string.st_focus_high, FocusStyle.Standard to R.string.st_focus_standard),
            AerialSettings.focusStyle, AerialSettings::chooseFocusStyle, first = true,
        )
        Choices(
            R.string.st_sounds,
            stringResource(if (AerialSettings.uiSounds == UiSounds.Tv) R.string.st_sounds_tv_hint else R.string.st_sounds_hint),
            note,
            listOf(UiSounds.Tv to R.string.st_sounds_tv, UiSounds.Aerial to R.string.st_sounds_aerial, UiSounds.Off to R.string.st_off),
            AerialSettings.uiSounds, { AerialSettings.chooseUiSounds(it); Sounds.prepare() },
        )
        // Aerial 自带按键音的独立音量: 只在选「Aerial」时出现, 和电视音量滑块无关。
        if (AerialSettings.uiSounds == UiSounds.Aerial) {
            KeyVolumeRow(note)
        }
        Choices(
            R.string.st_splash_veil,
            stringResource(R.string.st_splash_veil_hint),
            note,
            listOf(true to R.string.st_on, false to R.string.st_off),
            AerialSettings.splashVeilEnabled, AerialSettings::chooseSplashVeil,
        )
        Choices(
            R.string.st_effects,
            stringResource(
                if (AerialSettings.effects == Effects.Auto && AerialSettings.lowEnd) R.string.st_effects_auto_lite_hint
                else R.string.st_effects_hint,
            ),
            note,
            listOf(Effects.Auto to R.string.st_effects_auto, Effects.Full to R.string.st_effects_full, Effects.Lite to R.string.st_effects_lite),
            AerialSettings.effects, AerialSettings::chooseEffects,
        )
    }
    Spacer(Modifier.height(12.dp))
}

/** 屏幕保护程序: 类型、画质、时钟、延时、返回键。 */
@Composable
private fun ScreensaverSection(onStartScreensaver: () -> Unit) {
    val context = LocalContext.current
    Group { note ->
        SettingRow(
            R.string.ss_kind,
            when {
                Wallpapers.screensaver == ScreensaverMode.Videos -> stringResource(R.string.ss_videos_hint)
                Wallpapers.screensaver == ScreensaverMode.System -> stringResource(R.string.ss_system_hint)
                Wallpapers.screensaverFollows -> stringResource(R.string.ss_follow_hint)
                AerialSettings.backStartsScreensaver -> stringResource(R.string.ss_back_hint)
                else -> null
            },
            note, first = true,
        ) { ScreensaverChoice(onStartScreensaver) }
        // Only the qualities this TV can play; a 1080p-only TV has nothing to choose.
        val qualities = remember { AerialFormats.supported(context) }
        if (qualities.size > 1) {
            val keeps = stringResource(
                R.string.ss_aerial_keeps,
                remember { android.text.format.Formatter.formatShortFileSize(context, AerialStream.keepLimit(context)) },
                AerialSet.MAX,
            )
            Choices(
                R.string.ss_quality, keeps, note,
                listOf(AerialQuality.Auto to R.string.ss_quality_auto, AerialQuality.Hd to R.string.ss_quality_hd) +
                    listOf(AerialQuality.Uhd to R.string.ss_quality_uhd, AerialQuality.DolbyVision to R.string.ss_quality_dv)
                        .filter { it.first in qualities },
                AerialSettings.aerialQuality, AerialSettings::chooseAerialQuality,
            )
        }
        // The clock screensaver is its clock, and the others show none.
        if (Wallpapers.screensaver == ScreensaverMode.Aerial || Wallpapers.screensaver == ScreensaverMode.Photos) Choices(
            R.string.ss_show_time, stringResource(R.string.ss_show_time_hint), note,
            listOf(true to R.string.st_on, false to R.string.st_off),
            AerialSettings.screensaverClock, AerialSettings::chooseScreensaverClock,
        )
        // The firmware's screensaver starts on the firmware's timer.
        if (Wallpapers.screensaver == ScreensaverMode.System) SettingRow(R.string.ss_delay, null, note) {
            val delay = remember { SystemScreensaver.firmwareDelayMs(context) }
            BasicText(
                when {
                    delay == null -> stringResource(R.string.ss_delay_system)
                    delay == 0L -> stringResource(R.string.ss_delay_system_off)
                    else -> stringResource(R.string.ss_delay_system_minutes, ((delay + 30_000) / 60_000).coerceAtLeast(1).toInt())
                },
                style = Type.Body.copy(color = Palette.SecondaryLabel),
                modifier = Modifier.padding(end = 8.dp),
            )
        } else Choices(
            R.string.ss_delay, null, note,
            AerialSettings.screensaverChoices.map {
                it to if (it == AerialSettings.NEVER) stringResource(R.string.ss_never) else stringResource(R.string.ss_minutes, it)
            },
            AerialSettings.screensaverMinutes, AerialSettings::chooseScreensaverMinutes,
        )
        Choices(
            R.string.ss_back, stringResource(R.string.ss_back_choice_hint), note,
            listOf(true to R.string.ss_back_on, false to R.string.ss_back_off),
            AerialSettings.backStartsScreensaver, AerialSettings::chooseBackStartsScreensaver,
        )
    }
    Spacer(Modifier.height(12.dp))
}

/** 天气与时钟。 */
@Composable
private fun WeatherSection() {
    Group(stringResource(if (Weather.chosen) R.string.wp_weather_chosen else R.string.wp_weather_guessed)) { note ->
        Choices(
            R.string.st_clock_hours, stringResource(R.string.st_clock_hours_hint), note,
            listOf(ClockHours.H24 to R.string.st_clock_hours_24, ClockHours.H12 to R.string.st_clock_hours_12),
            if (AerialSettings.clockHours == ClockHours.H12) ClockHours.H12 else ClockHours.H24,
            { AerialSettings.chooseClockHours(it) }, first = true,
        )
        // 天气是否显示在时钟下方
        Choices(
            R.string.st_weather_visibility, stringResource(R.string.st_weather_visibility_hint), note,
            listOf(true to R.string.st_on, false to R.string.st_off),
            AerialSettings.showWeather, AerialSettings::chooseShowWeather,
        )
        SettingRow(R.string.wp_weather_city, null, note) { WeatherValue() }
    }
    Spacer(Modifier.height(12.dp))
}

/** 家长控制: 密码与儿童/长辈模式的入口。 */
@Composable
private fun FamilySection(onPin: (on: Boolean) -> Unit, onKids: () -> Unit) {
    // One row says what it is; a title over it would only repeat it.
    Group(stringResource(R.string.st_pin_hint)) { note ->
        SettingRow(R.string.st_pin, null, note, first = true) {
            PinChoice(onPin)
        }
        SettingRow(R.string.st_kids, stringResource(R.string.st_kids_hint), note) {
            ActionChip(
                when {
                    !Restricted.enabled -> stringResource(R.string.st_off)
                    Restricted.kind == RestrictedKind.Kids -> stringResource(R.string.kids_kind_kids)
                    else -> stringResource(R.string.kids_kind_elders)
                },
                Glyph.ChevronRight,
                onKids,
            )
        }
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
        modifier = Modifier.offset(x = (-10).dp).drawWithContent {
            // Keep the cards out of the phone column on old GPUs (#557), independently of the
            // scroll layer's clip. Leave vertical room for the focused card's 8% zoom.
            val focusRoom = 16.dp.toPx()
            clipRect(top = -focusRoom, bottom = size.height + focusRoom) {
                this@drawWithContent.drawContent()
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
private fun Group(note: String? = null, rows: @Composable ColumnScope.(note: (String?) -> Unit) -> Unit) {
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
        // 组自己的说明(比如"按网络自动定位")放在组前面, 一进这一组就看得到。
        note?.let {
            BasicText(
                it,
                style = Type.Caption.copy(color = Palette.TertiaryLabel),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }
        rows { }
    }
}

/** One row: the setting's name on the left, its control on the right, a hairline above all but the first. */
private val RowGap = 40.dp
private val RowCorner = 18.dp
private val ControlWidth = 210.dp

@Composable
private fun SettingRow(label: Int, hint: String?, note: (String?) -> Unit, first: Boolean = false, control: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // 每个选项单独一个气泡, 气泡之间留固定空档; 小字提示就画在这个空档里,
    // 出现和收起都不会改变行距(空档高度固定)。
    Column(Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus; if (it.hasFocus) note(hint) }) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 60.dp)
                .clip(RoundedCornerShape(RowCorner))
                .drawBehind {
                    val r = CornerRadius(RowCorner.toPx())
                    drawRoundRect(
                        color = Color.White.copy(alpha = if (focused) 0.10f else 0.06f),
                        cornerRadius = r,
                    )
                    if (focused) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.85f),
                            cornerRadius = r,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                }
                .padding(start = 18.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(stringResource(label), style = Type.Body, modifier = Modifier.weight(1f), maxLines = 1)
            // 选项按钮固定宽度(和音量条一样), 放不下的靠左右滑。
            Box(Modifier.width(ControlWidth), contentAlignment = Alignment.CenterEnd) { control() }
        }
        Box(Modifier.fillMaxWidth().height(RowGap)) {
            if (focused && !hint.isNullOrBlank()) {
                BasicText(
                    hint,
                    style = Type.Caption.copy(color = Palette.TertiaryLabel),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
                )
            }
        }
    }
}

/**
 * 选项按钮: 固定 210dp 宽(和音量条同一个宽度), 放不下就左右滑。
 * 没聚焦时把当前打勾的那颗摆在正中间; 左右两端淡出, 看得出还能滑。
 */
@Composable
private fun ChipTrack(
    selectedIndex: Int,
    itemCount: Int,
    item: @Composable (index: Int) -> Unit,
) {
    val railExit = LocalRailExit.current
    val state = rememberLazyListState()
    var focused by remember { mutableStateOf(false) }
    var focusedIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(selectedIndex, focused) {
        if (!focused && selectedIndex in 0 until itemCount) {
            state.scrollToItem(selectedIndex)
            // scrollToItem 把选中项放在左内边距上(40dp), 再挪一下让它落到正中。
            val w = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == selectedIndex }?.size ?: 0
            state.scrollBy(w / 2f - 65f)
        }
    }
    LazyRow(
        state = state,
        modifier = Modifier
            .width(ControlWidth)
            .fadeEdges()
            .onFocusChanged { focused = it.hasFocus }
            .onPreviewKeyEvent { e ->
                // 滑到最后一颗再按右键, 焦点回导航条, 不会卡在边上。
                val last = focusedIndex == itemCount - 1
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && last && railExit != null) {
                    railExit(); true
                } else false
            },
        contentPadding = PaddingValues(horizontal = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(itemCount) { index ->
            Box(Modifier.onFocusChanged { if (it.isFocused) focusedIndex = index }) { item(index) }
        }
    }
}

/** 左右两端淡出: 内容在图层里按水平渐变做 DstIn, 边缘透明, 中间不透明。 */
private fun Modifier.fadeEdges(edge: Dp = 16.dp) = drawWithContent {
    val w = edge.toPx().coerceAtMost(size.width / 2f)
    if (w <= 0f || size.width <= 0f) {
        drawContent()
        return@drawWithContent
    }
    drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
    drawContent()
    drawRect(
        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
            0f to Color.Transparent,
            w / size.width to Color.Black,
            1f - w / size.width to Color.Black,
            1f to Color.Transparent,
        ),
        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
    )
    drawContext.canvas.restore()
}

/** A row of chips for one of a few values. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(
    label: Int,
    hint: String?,
    note: (String?) -> Unit,
    options: List<Pair<T, Any>>,
    current: T,
    choose: (T) -> Unit,
    first: Boolean = false,
) {
    SettingRow(label, hint, note, first) {
        val index = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
        ChipTrack(selectedIndex = index, itemCount = options.size) { i ->
            val (value, text) = options[i]
            Chip(if (text is Int) stringResource(text) else text.toString(), on = current == value) { choose(value) }
        }
    }
}

/**
 * 「按键音量」: 0..100, 只靠左右键增减(遥控器友好)。改一个数就立即生效, 不用退出面板。
 */
@Composable
private fun KeyVolumeRow(note: (String?) -> Unit) {
    SettingRow(R.string.st_key_volume, stringResource(R.string.st_key_volume_hint), note) {
        var bumpedEdge by remember { mutableStateOf(0) }
        Pressable(
            onClick = {},
            focusedScale = 1.0f,
            modifier = Modifier.width(210.dp),
            onKey = { e ->
                val code = e.nativeKeyEvent.keyCode
                if (code != NativeKeyEvent.KEYCODE_DPAD_LEFT && code != NativeKeyEvent.KEYCODE_DPAD_RIGHT) return@Pressable false
                if (e.type == KeyEventType.KeyDown) {
                    val delta = if (code == NativeKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
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
                true
            },
        ) { state ->
            val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "keyvol-bg")
            val fg = if (state.focused) Palette.OnFocus else Palette.Label
            val volume = AerialSettings.keyVolume
            // 条形跟着数值走一段动画, 不再瞬时跳变。
            val fraction by animateFloatAsState(
                targetValue = volume / 100f,
                animationSpec = tween(160, easing = LinearEasing),
                label = "keyvol-level",
            )
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).drawBehind { drawRect(bg) }.height(40.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 喇叭图标放在滑块条的左边(和控制中心音量滑块同一套 Atom: 同一 Glyph、同一尺寸),
                // 音量格数用 level 表现, 不额外写字。
                GlyphIcon(
                    if (volume == 0) Glyph.SpeakerMuted else Glyph.Speaker,
                    fg,
                    18.dp,
                    level = fraction,
                )
                Canvas(Modifier.weight(1f).height(6.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(fg.copy(alpha = 0.2f), cornerRadius = r)
                    drawRoundRect(fg, size = GeometrySize(size.width * fraction, size.height), cornerRadius = r)
                }
                BasicText(
                    volume.toString(),
                    style = Type.Body.copy(color = fg),
                    modifier = Modifier.widthIn(min = 26.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Parental PIN on or off; either way goes through the PIN pad (issue #10). */
@Composable
private fun PinChoice(onPin: (on: Boolean) -> Unit, onFocused: (() -> Unit)? = null) {
    val options = listOf(true to R.string.st_pin_on, false to R.string.st_pin_off)
    ChipTrack(selectedIndex = options.indexOfFirst { it.first == AerialSettings.pinSet }.coerceAtLeast(0), itemCount = options.size) { i ->
        val (on, label) = options[i]
        Chip(stringResource(label), on = AerialSettings.pinSet == on, onFocused = onFocused) {
            if (AerialSettings.pinSet != on) onPin(on)
        }
    }
}

/** Where the weather is for and what it is now. The city is set on the phone page. */
@Composable
private fun WeatherValue() {
    val w = Weather.now
    val place = Weather.place
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
    BasicText(
        if (w != null) listOfNotNull(place, "${w.celsius}° $kind").joinToString(" · ") else stringResource(R.string.wp_weather_none),
        style = Type.Body.copy(color = Palette.SecondaryLabel),
        modifier = Modifier.padding(end = 8.dp),
    )
}

/** A pill to pick one of a few options: a check on the chosen one, white when focused. */
@Composable
private fun Chip(label: String, on: Boolean, onFocused: (() -> Unit)? = null, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusedScale = 1.06f) { state ->
        if (onFocused != null) LaunchedEffect(state.focused) { if (state.focused) onFocused() }
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
            Modifier.clip(RoundedCornerShape(50)).drawBehind { drawRect(bg) }.height(40.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (on) GlyphIcon(Glyph.Check, fg, 14.dp)
            BasicText(label, style = Type.Body.copy(color = fg))
        }
    }
}

/** A pill that does something rather than choosing a value: white when focused, a glyph after its label. */
@Composable
private fun ActionChip(label: String, glyph: Glyph, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusedScale = 1.06f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "action-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).drawBehind { drawRect(bg) }.height(40.dp).padding(start = 20.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(label, style = Type.Body.copy(color = fg))
            GlyphIcon(glyph, fg, 18.dp)
        }
    }
}

/** B 站扫码登录: 未登录出扫码入口并展开二维码与状态; 已登录显示账号与退出。 */
@Composable
private fun BiliLoginRow(note: (String?) -> Unit) {
    var loggedIn by remember { mutableStateOf(BiliAccount.loggedIn) }
    var uid by remember { mutableStateOf(BiliAccount.uid) }
    var loginState by remember { mutableStateOf<BiliLoginState?>(null) }
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()

    fun startLogin() {
        qrBitmap = null
        loginState = null
        scope.launch {
            BiliLogin.login { s ->
                loginState = s
                when (s) {
                    is BiliLoginState.QrReady -> {
                        // 回调本身就在 BiliLogin 的 IO 线程里, 直接同步编码二维码。
                        qrBitmap = PhoneServer.qr(s.qrUrl).asImageBitmap()
                    }
                    BiliLoginState.Success -> {
                        loggedIn = BiliAccount.loggedIn
                        uid = BiliAccount.uid
                    }
                    BiliLoginState.Expired, is BiliLoginState.Failed -> qrBitmap = null
                    else -> {}
                }
            }
        }
    }

    SettingRow(R.string.st_bili_account, stringResource(R.string.st_bili_account_hint), note) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loggedIn) {
                BasicText(
                    listOfNotNull(stringResource(R.string.st_bili_logged_in), uid).joinToString(" · "),
                    style = Type.Body.copy(color = Palette.SecondaryLabel),
                )
                ActionChip(stringResource(R.string.st_bili_logout), Glyph.Trash) {
                    BiliAccount.clear()
                    loggedIn = false
                    uid = null
                    loginState = null
                    qrBitmap = null
                }
            } else {
                ActionChip(stringResource(R.string.st_bili_login), Glyph.ChevronRight) { startLogin() }
            }
        }
    }

    if (!loggedIn && loginState != null) {
        Column(
            Modifier.padding(start = 18.dp, end = 10.dp, top = 4.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            qrBitmap?.let {
                Image(it, contentDescription = null, modifier = Modifier.size(160.dp))
            }
            BasicText(
                when (loginState) {
                    is BiliLoginState.QrReady -> stringResource(R.string.st_bili_qr_ready)
                    BiliLoginState.WaitingScan -> stringResource(R.string.st_bili_waiting)
                    BiliLoginState.ScannedConfirm -> stringResource(R.string.st_bili_scanned)
                    BiliLoginState.Success -> stringResource(R.string.st_bili_success)
                    BiliLoginState.Expired -> stringResource(R.string.st_bili_expired)
                    is BiliLoginState.Failed -> stringResource(R.string.st_bili_failed)
                    else -> ""
                },
                style = Type.Caption.copy(color = Palette.TertiaryLabel),
            )
        }
    }
}

@Composable
private fun ScreensaverChoice(onStart: () -> Unit) {
    // 五个屏保类型 + 末尾一个"开始屏保", 一起横着滑; 没聚焦时把当前类型摆在中间。
    val modes = listOf(
        ScreensaverMode.Aerial to R.string.ss_aerial,
        ScreensaverMode.Photos to R.string.ss_photos,
        ScreensaverMode.Clock to R.string.ss_clock,
        ScreensaverMode.Videos to R.string.ss_videos,
        ScreensaverMode.System to R.string.ss_system,
    )
    val current = modes.indexOfFirst { it.first == Wallpapers.screensaver }.coerceAtLeast(0)
    ChipTrack(selectedIndex = current, itemCount = modes.size + 1) { i ->
        if (i < modes.size) {
            val (mode, label) = modes[i]
            Chip(stringResource(label), on = Wallpapers.screensaver == mode) { Wallpapers.chooseScreensaver(mode) }
        } else {
            Pressable(onClick = onStart, focusedScale = 1.06f) { state ->
            val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "ss-start")
                val fg = if (state.focused) Palette.OnFocus else Palette.Label
                Row(
                    Modifier.clip(RoundedCornerShape(50)).drawBehind { drawRect(bg) }.height(40.dp).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GlyphIcon(Glyph.Play, fg, 14.dp)
                    BasicText(stringResource(R.string.ss_start), style = Type.Body.copy(color = fg))
                }
            }
        }
    }
}

@Composable
private fun PhoneCard(url: String?, qr: ImageBitmap?) {
    Column(Modifier.width(220.dp).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(176.dp).smoothBackground(Color.White, 18.dp).padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(qr, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            } else {
                GlyphIcon(Glyph.Offline, Color.Black.copy(alpha = 0.35f), 40.dp)
            }
        }
        Spacer(Modifier.height(14.dp))
        BasicText(stringResource(R.string.wp_phone_title), style = Type.Headline)
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(if (url != null) R.string.wp_phone_body else R.string.wp_phone_offline),
            style = Type.Caption.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
        if (url != null) {
            Spacer(Modifier.height(6.dp))
            // The whole address, token and all, to type into a computer's browser.
            BasicText(url.removePrefix("http://"), style = Type.Caption.copy(color = Palette.SecondaryLabel))
        }
    }
}

/** 设置面板占屏幕右侧这一段的宽度(照当贝的设置页: 左边内容, 右缘一条图标导航)。 */
val SheetWidth = 540.dp
private val SheetCorner = 30.dp
/** 右缘导航条留出的宽度: 只放图标时很窄, 选中那项要展开成"图标+名字", 靠右对齐向左长出去。 */
private val RailWidth = 150.dp
/** 焦点不在导航上时导航条的宽度: 只留图标, 面板整体往右缩这么多。 */
private val RailWidthNarrow = 92.dp

/**
 * 面板稳定后盖住的那一段宽度。Home 不必在它下面整屏作画(好几层全屏和没人看见的卡片,
 * 低端机很吃这个), 只画左边露出来的部分。
 */
val SheetCovers = SheetWidth
