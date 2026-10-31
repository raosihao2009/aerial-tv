package app.orchard.tv.ui.home

import android.content.ComponentName
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.LiveSource
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.TileArtFactory
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Glyph

private val liveColors = listOf(
    Pair(0xFFD32F2F.toInt(), 0xFF8E0000.toInt()),
    Pair(0xFF1976D2.toInt(), 0xFF0D47A1.toInt()),
    Pair(0xFF388E3C.toInt(), 0xFF1B5E20.toInt()),
    Pair(0xFFF57C00.toInt(), 0xFFE65100.toInt()),
    Pair(0xFF7B1FA2.toInt(), 0xFF4A148C.toInt()),
    Pair(0xFF00796B.toInt(), 0xFF004D40.toInt()),
)

private fun liveSourceIconResId(source: LiveSource): Int? = when (source.id) {
    "preset_0" -> app.orchard.tv.R.drawable.live_cctv
    "preset_1" -> app.orchard.tv.R.drawable.live_yangshipin
    "preset_2" -> app.orchard.tv.R.drawable.live_cctvkuikan
    "preset_3" -> app.orchard.tv.R.drawable.live_kuikan
    else -> null
}

private fun liveSourceAppEntry(source: LiveSource, ctx: android.content.Context): AppEntry {
    val (top, bottom) = liveColors[source.colorIndex % liveColors.size]
    return AppEntry(
        packageName = "live_${source.id}",
        label = source.name,
        component = ComponentName("app.orchard.tv", "app.orchard.tv.MainActivity"),
        leanback = true,
        removable = !source.isPreset,
        art = TileArtFactory.glyphTile(top, bottom, Glyph.Play),
    )
}

@Composable
fun LiveSourceMenu(
    visible: Boolean,
    sources: List<LiveSource>,
    currentId: String,
    onSelect: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onHide: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit = {},
    onMovingChange: (Boolean) -> Unit = {},
) {
    var focusedId by remember { mutableStateOf(currentId) }
    var menuForId by remember { mutableStateOf<String?>(null) }
    var movingId by remember { mutableStateOf<String?>(null) }
    var ignoreMenuKeyUp by remember { mutableStateOf(true) }
    LaunchedEffect(visible) {
        if (visible) ignoreMenuKeyUp = true
    }
    LaunchedEffect(menuForId) { onMenuOpenChange(menuForId != null) }
    LaunchedEffect(movingId) { onMovingChange(movingId != null) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    // 上下文菜单关闭后焦点回到原卡片
    var lastMenuId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(menuForId) {
        if (menuForId != null) lastMenuId = menuForId
        else if (lastMenuId != null) {
            kotlinx.coroutines.delay(100)
            lastMenuId?.let { runCatching { requester(it).requestFocus() } }
            lastMenuId = null
        }
    }
    val density = LocalDensity.current

    LaunchedEffect(visible) {
        kotlinx.coroutines.delay(200)
        runCatching { requester(currentId).requestFocus() }
    }
    // 移动时焦点跟随
    LaunchedEffect(movingId, sources) {
        movingId?.let { runCatching { requester(it).requestFocus() } }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val gridMargin = 56.dp
    val gridGap = 28.dp
    // 复用上游设置：每排 4/5/6 个，默认 5
    val columns = AerialSettings.homeColumns
    // 玻璃底板开启时，首排会向上抬（和一级菜单同一套 Plate 常量：底部边距30dp + 内边距20dp）；
    // 关闭时给 App 名称留 70dp。让二级菜单和一级菜单底部基线一致。
    val plate = AerialSettings.homePlate
    val bottomPad = if (plate) {
        30.dp + 20.dp + 10.dp
    } else {
        70.dp
    }
    val rowGap = 24.dp
    val showAllNames = AerialSettings.showAllNames

    val alpha = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "liveMenuAlpha",
    )

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize().alpha(alpha.value)) {
        val screenW = maxWidth
        val screenH = maxHeight
        val tileW = (screenW - gridMargin * 2 - gridGap * (columns - 1)) / columns
        val tileH = tileW * 9f / 16f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { e ->
                    if (!visible) return@onPreviewKeyEvent false
                    if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_HOME && e.type == KeyEventType.KeyDown) {
                        onDismiss()
                        return@onPreviewKeyEvent true
                    }
                    // 任何方向键/确认键后才允许菜单键呼出上下文菜单
                    if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_UP ||
                        e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_DOWN ||
                        e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_LEFT ||
                        e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_RIGHT ||
                        e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_CENTER) {
                        ignoreMenuKeyUp = false
                    }
                    if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_MENU && e.type == KeyEventType.KeyUp && menuForId == null && movingId == null && !ignoreMenuKeyUp) {
                        requester(focusedId).requestFocus()
                        sources.find { it.id == focusedId }?.let { menuForId = it.id }
                        true
                    } else if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK && e.type == KeyEventType.KeyUp) {
                        when {
                            menuForId != null -> {
                                menuForId = null
                                true
                            }
                            movingId != null -> { movingId = null; true }
                            else -> false
                        }
                    } else if (movingId != null) {
                        val from = sources.indexOfFirst { it.id == movingId }
                        when {
                            e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_LEFT && e.type == KeyEventType.KeyDown && from > 0 -> {
                                onMove(from, from - 1); true
                            }
                            e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_RIGHT && e.type == KeyEventType.KeyDown && from < sources.size - 1 -> {
                                onMove(from, from + 1); true
                            }
                            e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_CENTER && e.type == KeyEventType.KeyUp -> {
                                movingId = null; true
                            }
                            else -> true
                        }
                    } else false
                },
        ) {
            // 绝对定位每个卡片，带位置动画（完全照搬一级菜单）
            sources.forEachIndexed { index, source ->
                androidx.compose.runtime.key(source.id) {
                val fallback = remember(source.id, source.name) { liveSourceAppEntry(source, context) }
                val artState = androidx.compose.runtime.produceState<app.orchard.tv.apps.TileArt?>(initialValue = fallback.art, source.id, source.name) {
                    val resId = liveSourceIconResId(source)
                    if (resId != null) {
                        val (top, bottom) = liveColors[source.colorIndex % liveColors.size]
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching { TileArtFactory.bitmapTileFromResId(context, resId, top, bottom) }.getOrNull() ?: fallback.art
                        }
                    }
                }
                val app = remember(fallback, artState.value) { fallback.copy(art = artState.value ?: fallback.art) }
                val colIndex = index % columns
                val rowIndex = index / columns
                val xPx = with(density) { (gridMargin + (tileW + gridGap) * colIndex).roundToPx() }
                val yPx = with(density) { (screenH - bottomPad - tileH - (tileH + rowGap) * rowIndex).roundToPx() }
                val targetOffset = IntOffset(xPx, yPx)
                val animatedOffset by animateIntOffsetAsState(
                    targetOffset,
                    spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessHigh),
                    label = "slot",
                )
                Box(modifier = Modifier.offset { animatedOffset }) {
                    AppTile(
                        app = app,
                        width = tileW,
                        height = tileH,
                        focusRequester = requester(source.id),
                        enabled = menuForId == null && visible,
                        moving = movingId == source.id,
                        appearDelayMs = null,
                        onFocused = { focusedId = source.id; Sounds.focusMoved() },
                        onClick = { if (movingId == null) { onSelect(source.id); onDismiss() } },
                        onLongPress = { if (movingId == null) menuForId = source.id },
                        rowEnd = index == sources.size - 1,
                        showName = showAllNames || focusedId == source.id,
                    )
                }
                }
            }
            if (movingId != null) {
                BasicText(
                    "用方向键移动 · 按确定键完成",
                    style = TextStyle(Color.White, fontSize = 14.sp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp)
                        .liquidGlass(20.dp)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            if (menuForId != null) {
                val source = sources.find { it.id == menuForId } ?: return@BoxWithConstraints
                val sourceIndex = sources.indexOf(source)
                val colIndex = sourceIndex % columns
                val rowIndex = sourceIndex / columns
                val first = remember { FocusRequester() }
                LaunchedEffect(menuForId) { first.requestFocus() }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = gridMargin + (tileW + gridGap) * colIndex + tileW / 2 - 125.dp,
                            bottom = bottomPad + tileH + (tileH + rowGap) * rowIndex + 20.dp,
                        )
                        .width(250.dp)
                        .liquidGlass(20.dp)
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(source.name, style = TextStyle(Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp))
                    MenuRow(Glyph.Move, "移动", { movingId = source.id; menuForId = null }, focusRequester = first)
                    MenuRow(Glyph.EyeSlash, "从直播菜单隐藏", { onHide(source.id); menuForId = null })
                    MenuRow(Glyph.Trash, "删除", { onDelete(source.id); menuForId = null }, destructive = true)
                }
            }
        }
    }
}
