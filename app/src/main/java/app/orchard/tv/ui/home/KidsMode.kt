package app.orchard.tv.ui.home

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.LIVE_KEY_PREFIX
import app.orchard.tv.guard.HomeGuardService
import app.orchard.tv.guard.KidsWatch
import app.orchard.tv.settings.KidsLock
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.KidsTime
import app.orchard.tv.settings.ModeRules
import app.orchard.tv.settings.Restricted
import app.orchard.tv.settings.RestrictedKind
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.components.smoothBackground
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import app.orchard.tv.ui.wallpaper.Cell
import app.orchard.tv.ui.wallpaper.ChipButton
import app.orchard.tv.ui.wallpaper.ChoiceCell
import app.orchard.tv.ui.wallpaper.Group
import app.orchard.tv.ui.wallpaper.LocalMenuWindow
import app.orchard.tv.ui.wallpaper.MenuWindow
import app.orchard.tv.ui.wallpaper.MenuWindowLayer
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/** Minutes a day the TV offers; 0 is no limit. The phone page sets any. */
private val DAY_MINUTES = listOf(0, 30, 60, 90, 120)
/** Where the window ends, in minutes of the day; 0 is all day. */
private val END_MINUTES = listOf(0, 20 * 60, 21 * 60, 22 * 60)
private val BREAKS = listOf(0, 30, 45, 60)

/** The TV's few values, and the one set on the phone page after them when it is none of them. */
private fun withCustom(presets: List<Int>, current: Int) = if (current in presets) presets else presets + current

/** "1 h 30 min", "45 min", in the interface's language. */
@Composable
fun durationText(ms: Long): String {
    val minutes = ((ms + 30_000L) / 60_000L).toInt().coerceAtLeast(0)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> stringResource(R.string.kids_m, m)
        m == 0 -> stringResource(R.string.kids_h, h)
        else -> stringResource(R.string.kids_hm, h, m)
    }
}

private fun clockText(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().let { "%d:%02d".format(it.hour, it.minute) }

/** 必选的直播卡片与自动补的预览应用卡片: 规矩写在 [ModeRules] 里, 手机页用的是同一份。 */
private fun modeLocks(candidates: List<AppEntry>): Set<String> = ModeRules.locks(candidates)

/** 把上面的规矩落进选中的卡片里: 该补的补, 该撤的撤。 */
private fun enforceModeRules(candidates: List<AppEntry>) = ModeRules.apply(Restricted.allowed, candidates)

/**
 * Setting up Kids and Elders mode, reached from Personalize behind the PIN: who it is for, the
 * tiles it shows (on the right, in the order picked) and, for children, the time limits. Each
 * choice is kept at once; "Start" goes into the mode.
 *
 * 左边选项和个性化面板同一套: 一格一个选项、下面一行小字提示, 选项多的时候在按钮上面浮一个
 * 迷你窗口(ChoiceCell), 面板里怎么点这里就怎么点。
 */
@Composable
fun KidsSetupSheet(visible: Boolean, candidates: List<AppEntry>, onStart: () -> Unit, onDismiss: () -> Unit) {
    val leftFirst = remember { FocusRequester() }
    val menuWindow = remember { MenuWindow() }
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, easing = Motion.EaseOut)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(150, easing = Motion.EaseOut)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            // 迷你窗口和面板里那颗是同一块玻璃, 位置对准入口按钮。
            androidx.compose.runtime.CompositionLocalProvider(LocalMenuWindow provides menuWindow) {
                Row(
                    Modifier
                        .width(912.dp)
                        .height(500.dp)
                        .consumeTaps()
                        .liquidGlass(28.dp)
                        .padding(start = 28.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
                ) {
                    SetupSettings(candidates.isNotEmpty(), onStart, leftFirst, Modifier.width(480.dp).fillMaxHeight())
                    Spacer(Modifier.width(24.dp))
                    AllowedApps(candidates, Modifier.weight(1f).fillMaxHeight(), leftFirst)
                }
            }
            MenuWindowLayer(menuWindow)
        }
    }
}

@Composable
private fun SetupSettings(hasApps: Boolean, onStart: () -> Unit, first: FocusRequester, modifier: Modifier) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val kids = Restricted.kind == RestrictedKind.Kids
    val rules = Restricted.rules
    val guard = HomeGuardService.isRunning
    Column(modifier) {
        // 大标题和说明钉在左上角(用户口径): 左边选项一多就要往下滚, 它们不能被一起滚出去。
        BasicText(stringResource(R.string.kids_title), style = Type.LargeTitle)
        BasicText(
            stringResource(R.string.kids_hint),
            style = Type.Callout,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Group {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Cell(
                    R.string.kids_kind,
                    stringResource(if (kids) R.string.kids_kind_kids_hint else R.string.kids_kind_elders_hint),
                ) { onFocus ->
                    // Each starts from what suits it: children have other apps kept away, older people
                    // are let through (a firmware app opened by its own key is not a mistake there).
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChipButton(stringResource(R.string.kids_kind_kids), kids, focusRequester = first, onClick = {
                            if (!kids) { Restricted.chooseKind(RestrictedKind.Kids); Restricted.chooseBlockOthers(true) }
                        }, onFocus = onFocus, blockLeft = true, autoEdges = false)
                        ChipButton(stringResource(R.string.kids_kind_elders), !kids, onClick = {
                            if (kids) { Restricted.chooseKind(RestrictedKind.Elders); Restricted.chooseBlockOthers(false) }
                        }, onFocus = onFocus, autoEdges = false)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Cell(
                    R.string.kids_block,
                    stringResource(if (guard) R.string.kids_block_hint else R.string.kids_block_no_guard),
                ) { onFocus ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChipButton(stringResource(R.string.kids_block_on), Restricted.blockOthers, onClick = { Restricted.chooseBlockOthers(true) }, onFocus = onFocus, blockLeft = true, autoEdges = false)
                        ChipButton(stringResource(R.string.kids_block_off), !Restricted.blockOthers, onClick = { Restricted.chooseBlockOthers(false) }, onFocus = onFocus, autoEdges = false)
                    }
                }
            }
            val minutesHint = stringResource(R.string.kids_minutes_hint)
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                ChoiceCell(
                    R.string.kids_weekday, minutesHint,
                    withCustom(DAY_MINUTES, rules.weekdayMinutes).map { m -> m to (if (m == 0) R.string.kids_no_limit else m.toString()) },
                    rules.weekdayMinutes,
                    choose = { m -> Restricted.chooseRules(rules.copy(weekdayMinutes = m)); KidsWatch.poke() },
                    blockRightAtEnd = false,
                )
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                ChoiceCell(
                    R.string.kids_weekend, minutesHint,
                    withCustom(DAY_MINUTES, rules.weekendMinutes).map { m -> m to (if (m == 0) R.string.kids_no_limit else m.toString()) },
                    rules.weekendMinutes,
                    choose = { m -> Restricted.chooseRules(rules.copy(weekendMinutes = m)); KidsWatch.poke() },
                    blockRightAtEnd = false,
                )
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                ChoiceCell(
                    R.string.kids_latest, stringResource(R.string.kids_latest_hint, KidsTime.clock(rules.startMinute)),
                    // An end before the start the phone page set leaves the window all day.
                    withCustom(END_MINUTES, rules.endMinute).map { m -> m to (if (m == 0) R.string.kids_no_limit else KidsTime.clock(m)) },
                    rules.endMinute,
                    choose = { m ->
                        val next = rules.copy(endMinute = m)
                        Restricted.chooseRules(if (next.valid) next else next.copy(startMinute = KidsTime.DEFAULT_START)); KidsWatch.poke()
                    },
                    blockRightAtEnd = false,
                )
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                ChoiceCell(
                    R.string.kids_break, stringResource(R.string.kids_break_hint, rules.breakMinutes),
                    withCustom(BREAKS, rules.breakEvery).map { m -> m to (if (m == 0) R.string.st_off else m.toString()) },
                    rules.breakEvery,
                    choose = { m -> Restricted.chooseRules(rules.copy(breakEvery = m)); KidsWatch.poke() },
                    blockRightAtEnd = false,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val start = stringResource(if (kids) R.string.kids_start_kids else R.string.kids_start_elders)
        val startFocus = remember { FocusRequester() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            WideButton(start, Glyph.Kids, focusRequester = startFocus, onClick = { if (Restricted.allowed.isNotEmpty()) onStart() })
            Spacer(Modifier.width(14.dp))
            if (Restricted.allowed.isEmpty()) {
                BasicText(
                    stringResource(if (hasApps) R.string.kids_pick_first else R.string.kids_loading),
                    style = Type.Caption.copy(color = Palette.Orange), maxLines = 2,
                )
            } else if (Restricted.enabled) {
                WideButton(stringResource(R.string.kids_turn_off), null, onClick = {
                    Restricted.chooseEnabled(false); KidsWatch.poke()
                    // 这颗按钮一按就消失(模式已关), 焦点要落回「进入」按钮, 不能凭空丢掉。
                    startFocus.requestFocus()
                })
            }
        }
        Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun WideButton(label: String, glyph: Glyph?, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.05f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "kids-button")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.smoothBackground({ bg }, 14.dp).height(48.dp).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (glyph != null) GlyphIcon(glyph, fg, 18.dp)
            BasicText(label, style = Type.Headline.copy(color = fg), maxLines = 1)
        }
    }
}

/** Every tile the mode can show; picking one puts it at the end of the mode's Home. */
@Composable
private fun AllowedApps(candidates: List<AppEntry>, modifier: Modifier, leftTarget: FocusRequester? = null) {
    // 两张必选卡片每次进来先校准一遍(央视网快看; 选了别的直播卡片时那张直播预览应用)。
    LaunchedEffect(candidates, Restricted.allowed) { enforceModeRules(candidates) }
    val allowed = Restricted.allowed
    val live = candidates.filter { it.key.startsWith(LIVE_KEY_PREFIX) }
    val appsOnly = candidates.filterNot { it.key.startsWith(LIVE_KEY_PREFIX) }
    val locks = modeLocks(candidates)
    // 一个"切换式"分段键: 左右键切页, 高亮始终绑在当前页, 不是两颗各自独立的按钮。
    var tab by remember { mutableStateOf(if (live.isEmpty()) 1 else 0) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pressable(
                onClick = { tab = 1 - tab },
                focusedScale = 1.03f,
                onKey = { e ->
                    if (e.type != KeyEventType.KeyDown) false else when (e.nativeKeyEvent.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (tab > 0) { tab = 0; Sounds.focusMoved() }
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (tab < 1) { tab = 1; Sounds.focusMoved() }
                            true
                        }
                        else -> false
                    }
                },
            ) { st ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        // 聚焦只让"当前这一页"那颗变成白底, 不再整条胶囊一起变白
                        .background(Palette.Fill)
                        .height(32.dp)
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(R.string.kw_tab_live, R.string.kw_tab_apps).forEachIndexed { index, label ->
                        val active = tab == index
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    when {
                                        active && st.focused -> Palette.Focus
                                        active -> Palette.FillStrong
                                        else -> Color.Transparent
                                    },
                                )
                                .height(24.dp)
                                .padding(horizontal = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                stringResource(label),
                                style = Type.Callout.copy(
                                    color = when {
                                        active && st.focused -> Palette.OnFocus
                                        active -> Palette.Label
                                        st.focused -> Palette.Label
                                        else -> Palette.SecondaryLabel
                                    },
                                ),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            BasicText(
                if (tab == 0) stringResource(R.string.kids_live, live.count { it.key in allowed })
                else stringResource(R.string.kids_apps, appsOnly.count { it.key in allowed }),
                style = Type.Caption.copy(color = Palette.SecondaryLabel),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // 两页用同一句说明(用户口径): 桌面按选择的顺序显示, 以后新增的卡片不会自动加进来。
        BasicText(
            stringResource(R.string.kids_apps_hint),
            style = Type.Caption.copy(color = Palette.SecondaryLabel),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        val items = if (tab == 0) live else appsOnly
        // 编号只看本组里的勾选顺序: 直播卡片和应用卡片各自从 1 开始, 不串号。
        val allowedInGroup = allowed.filter { k -> items.any { it.key == k } }
        // 两页都是一排三张(用户口径)。用固定三列的网格, 宽度由网格自己分, 不会叠在一起,
        // 卡片多了就往下排、整块自己滚。
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    BasicText(
                        stringResource(if (tab == 0) R.string.kw_live_none else R.string.kids_loading),
                        style = Type.Caption.copy(color = Palette.SecondaryLabel),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            itemsIndexed(items, key = { _, app -> app.key }) { idx, app ->
                val index = allowedInGroup.indexOf(app.key)
                // 只有每排第一张按左才回到左侧选项, 别的都留在卡片之间。
                val locked = app.key in locks
                Pressable(
                    onClick = {
                        // 必选/被锁的卡片按下去不给取消, 只响一声撞边音。
                        if (locked) Sounds.limit() else {
                            Restricted.toggleAllowed(app.key)
                            enforceModeRules(candidates)
                        }
                    },
                    modifier = if (idx % 3 == 0) Modifier.focusProperties { leftTarget?.let { left = it } } else Modifier,
                    focusedScale = 1.08f,
                ) { state ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                            MiniTile(app, state.focused, Modifier.fillMaxSize())
                            if (index >= 0) {
                                // The place it takes on the mode's Home.
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(Palette.Green),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    BasicText("${index + 1}", style = Type.Caption.copy(color = Color.White, textAlign = TextAlign.Center))
                                }
                            } else {
                                Box(Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f)))
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        BasicText(
                            if (locked) app.label + " · " + stringResource(R.string.kids_required) else app.label,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            // 卡片名左右居中(用户口径): 名字在整格宽度里居中, 不是贴着左边界。
                            style = Type.Caption.copy(
                                color = if (state.focused || index >= 0) Palette.Label else Palette.SecondaryLabel,
                                textAlign = TextAlign.Center,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Over Home while Kids mode is locked: the day's time used, a break, or the hours the TV may be
 * watched. Nothing behind it can be reached; a parent's PIN gives more time or leaves the mode.
 * Home Guard (or KidsWatch) keeps every app away meanwhile.
 */
@Composable
fun KidsLockScreen(lock: KidsLock?, pinOpen: Boolean, onParent: (onRight: () -> Unit) -> Unit, onLeave: () -> Unit) {
    var shown by remember { mutableStateOf(lock) }
    if (lock != null) shown = lock
    var granting by remember(lock != null) { mutableStateOf(false) }
    BackHandler(enabled = lock != null && !pinOpen) {}
    AnimatedVisibility(
        visible = lock != null,
        enter = fadeIn(tween(300, easing = Motion.EaseOut)),
        exit = fadeOut(tween(220, easing = Motion.EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        val current = shown ?: return@AnimatedVisibility
        val first = remember { FocusRequester() }
        LaunchedEffect(granting, pinOpen) {
            if (pinOpen) return@LaunchedEffect
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
        // A break counts down by the second.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        if (current is KidsLock.Break) LaunchedEffect(current) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
        // Dark enough that the tiles under it do not read through the buttons.
        Box(Modifier.fillMaxSize().consumeTaps().background(Color.Black.copy(alpha = 0.86f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 620.dp)) {
                GlyphIcon(if (current is KidsLock.Break) Glyph.Sun else Glyph.Moon, Palette.Label, 44.dp)
                Spacer(Modifier.height(18.dp))
                val (title, sub) = when (current) {
                    KidsLock.DayUsed -> stringResource(R.string.kids_lock_day) to
                        stringResource(R.string.kids_lock_day_sub, durationText(Restricted.usage.usedMs))
                    KidsLock.Late -> stringResource(R.string.kids_lock_late) to
                        stringResource(R.string.kids_lock_late_sub, KidsTime.clock(Restricted.rules.startMinute), KidsTime.clock(Restricted.rules.endMinute))
                    is KidsLock.Break -> {
                        val left = ((current.untilMs - now) / 1000).coerceAtLeast(0)
                        stringResource(R.string.kids_lock_break) to stringResource(R.string.kids_lock_break_sub, "%d:%02d".format(left / 60, left % 60))
                    }
                }
                BasicText(title, style = Type.LargeTitle.copy(textAlign = TextAlign.Center))
                Spacer(Modifier.height(8.dp))
                BasicText(sub, style = Type.Body.copy(color = Palette.SecondaryLabel, textAlign = TextAlign.Center))
                Spacer(Modifier.height(30.dp))
                if (!granting && !AerialSettings.pinSet) {
                    // 没设家长密码: 别先把人关在"验证密码"里, 直接给一条退出的路。
                    WideButton(stringResource(if (Restricted.kind == RestrictedKind.Kids) R.string.cc_kids_leave else R.string.cc_elders_leave), null, focusRequester = first) {
                        onLeave()
                    }
                } else if (!granting) {
                    WideButton(stringResource(R.string.kids_parent), Glyph.Shield, focusRequester = first) {
                        onParent { granting = true }
                    }
                } else {
                    BasicText(stringResource(R.string.kids_grant_title), style = Type.Headline)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WideButton(stringResource(R.string.kids_grant, 15), null, focusRequester = first) { Restricted.allow(15); KidsWatch.poke() }
                        WideButton(stringResource(R.string.kids_grant, 30), null) { Restricted.allow(30); KidsWatch.poke() }
                        WideButton(stringResource(R.string.kids_grant, 60), null) { Restricted.allow(60); KidsWatch.poke() }
                        WideButton(stringResource(R.string.kids_grant_day), null) { Restricted.allow(null); KidsWatch.poke() }
                    }
                    Spacer(Modifier.height(10.dp))
                    WideButton(stringResource(if (Restricted.kind == RestrictedKind.Kids) R.string.cc_kids_leave else R.string.cc_elders_leave), null) { onLeave() }
                }
            }
        }
    }
}

/** Control Center's line about the day: time left, a parent's allowance, or no limit. */
@Composable
fun kidsTimeLine(): String? {
    if (Restricted.kind == RestrictedKind.Elders && !Restricted.rules.any) return null
    val now = System.currentTimeMillis()
    val usage = Restricted.usage
    if (usage.allowUntil > now) return stringResource(R.string.cc_kids_allowed_until, clockText(usage.allowUntil))
    val limit = Restricted.limitToday(now)
    if (limit == 0) return stringResource(R.string.cc_kids_no_limit, durationText(usage.usedMs))
    return stringResource(R.string.cc_kids_left, durationText((limit * 60_000L - usage.usedMs).coerceAtLeast(0)))
}
