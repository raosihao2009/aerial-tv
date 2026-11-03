package app.orchard.tv.ui.home

import android.content.ComponentName
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.Dp
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
    "preset_4" -> app.orchard.tv.R.drawable.live_dongfangmingzhu
    "preset_5" -> app.orchard.tv.R.drawable.live_dengzi
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
    onOpenControlCenter: () -> Unit = {},
) {
    var focusedId by remember { mutableStateOf(currentId) }
    var menuForId by remember { mutableStateOf<String?>(null) }
    var movingId by remember { mutableStateOf<String?>(null) }
    var bannerMsg by remember { mutableStateOf<String?>(null) }
    var ignoreMenuKeyUp by remember { mutableStateOf(true) }
    LaunchedEffect(bannerMsg) {
        if (bannerMsg != null) { kotlinx.coroutines.delay(2500); bannerMsg = null }
    }
    LaunchedEffect(visible) {
        if (visible) ignoreMenuKeyUp = true
    }
    LaunchedEffect(menuForId) { onMenuOpenChange(menuForId != null) }
    LaunchedEffect(movingId) { onMovingChange(movingId != null) }
    // 二级菜单关闭时, 清掉上下文菜单和移动状态(无论什么原因关闭)
    LaunchedEffect(visible) {
        if (!visible) {
            menuForId = null
            movingId = null
        }
    }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    // 上下文菜单关闭后焦点直接回到原卡片(不延迟, 不闪烁)
    var lastMenuId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(menuForId) {
        if (menuForId != null) {
            lastMenuId = menuForId
        } else if (lastMenuId != null) {
            focusedId = lastMenuId!!
            runCatching { requester(lastMenuId!!).requestFocus() }
            lastMenuId = null
        }
    }
    val density = LocalDensity.current

    // 菜单显示时立即聚焦初始卡片(不等动画)
    LaunchedEffect(visible) {
        if (visible) {
            focusedId = currentId
            runCatching { requester(currentId).requestFocus() }
        }
    }
    // 移动时焦点跟随
    LaunchedEffect(movingId, sources) {
        movingId?.let { runCatching { requester(it).requestFocus() } }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    // 完全对齐一级书架 HomeScreen.GridMetrics 的几何参数
    val columns = AerialSettings.homeColumns
    val plate = AerialSettings.homePlate
    val oneRow = AerialSettings.oneRowHome
    val peek = AerialSettings.peekNextRow
    // gridGap: 4→34dp, 5→28dp, 6→24dp (和一级菜单一致)
    val gridGap = when (columns) { 4 -> 34.dp; 6 -> 24.dp; else -> 28.dp }
    val gridMargin = 56.dp
    val pitchYExtra = 50.dp
    val basePad = if (plate) 50.dp else 70.dp
    val showAllNames = AerialSettings.showAllNames

    val alpha = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "liveMenuAlpha",
    )

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha.value)
            .onPreviewKeyEvent { e ->
                if (!visible) return@onPreviewKeyEvent false
                if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_HOME && e.type == KeyEventType.KeyDown) {
                    if (menuForId != null) menuForId = null
                    if (movingId != null) movingId = null
                    onDismiss()
                    return@onPreviewKeyEvent true
                }
                false
            }
    ) {
        val screenW = maxWidth
        val screenH = maxHeight
        val tileW = (screenW - gridMargin * 2 - gridGap * (columns - 1)) / columns
        val tileH = tileW * 9f / 16f
        val pitchY = tileH + pitchYExtra

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { e ->
                    if (!visible) return@onPreviewKeyEvent false
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
                            e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_UP && e.type == KeyEventType.KeyDown && from >= columns -> {
                                onMove(from, from - columns); true
                            }
                            e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_DOWN && e.type == KeyEventType.KeyDown && from < sources.size - 1 -> {
                                val target = minOf(from + columns, sources.size - 1)
                                if (target != from) onMove(from, target); true
                            }
                            e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_CENTER && e.type == KeyEventType.KeyUp -> {
                                movingId = null; true
                            }
                            else -> true
                        }
                    } else false
                },
        ) {
            // 时钟（照搬一级菜单三种形态）
            val clockRight = AerialSettings.clockRight
            if (AerialSettings.largeClock) {
                ShelfClock(
                    now = java.time.LocalDateTime.now(),
                    alignEnd = clockRight,
                    modifier = Modifier
                        .align(if (clockRight) Alignment.TopEnd else Alignment.TopStart)
                        .padding(horizontal = 56.dp)
                        .padding(top = 80.dp)
                )
            } else if (!clockRight) {
                CornerClock(
                    now = java.time.LocalDateTime.now(),
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 28.dp)
                )
            }
            // 控制中心（一级菜单同款 StatusPill）
            val pillFocus = remember { FocusRequester() }
            StatusPill(
                now = java.time.LocalDateTime.now(),
                network = app.orchard.tv.system.NetworkStatus(app.orchard.tv.system.NetKind.Wifi, "WiFi", 1f, false),
                showTime = false,
                enabled = true,
                focusRequester = pillFocus,
                onFocused = {},
                onClick = { onOpenControlCenter() },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 42.dp).focusProperties { right = FocusRequester.Cancel }
            )
            // 绝对定位每个卡片，带位置动画（完全照搬一级菜单 GridMetrics）
            // 行Y坐标逻辑（对齐 HomeScreen.topRowY）:
            //   双排: 底行贴 baseline, 上行 = 底行 - pitchY
            //   单排+露出: 主行上抬12dp, 次行露12dp边; 焦点到次排时上抬两行全可见
            //   单排+不露出: 主行贴baseline, 次行在屏幕外; 焦点到次排时上抬两行全可见
            val numRows = (sources.size + columns - 1) / columns
            val focusedIndex = sources.indexOfFirst { it.id == focusedId }.coerceAtLeast(0)
            val focusedRow = focusedIndex / columns
            // 显示规律:
            // 焦点第0排: 第0排贴底, 第1排peek
            // 焦点第1排: 第0排上方, 第1排贴底, 第2排peek
            // 焦点第N>=2排: 偶数排N贴底, 奇数排N+1贴底(显示N/N+1/N+2)
            fun rowY(rowFromTop: Int): Dp {
                val diff = rowFromTop - focusedRow
                val peekOffset = if (peek && diff == 1) 20.dp else 0.dp
                val lift = if (focusedRow >= 1) pitchY else 0.dp
                return screenH - basePad - tileH + pitchY * diff - peekOffset - lift
            }
            sources.forEachIndexed { index, source ->
                val rowFromTop = index / columns
                val shifted = rowFromTop - focusedRow
                androidx.compose.runtime.key(source.id) {
                val fallback = remember(source.id, source.name) { liveSourceAppEntry(source, context) }
                val artState = androidx.compose.runtime.produceState<app.orchard.tv.apps.TileArt?>(initialValue = fallback.art, source.id, source.name, source.liveStatus, source.id == currentId, source.iconTs) {
                    val statusText = when {
                        source.id == currentId -> "直播中"
                        source.liveStatus == 2 -> "未开播"
                        source.liveStatus == 1 -> "已开播"
                        else -> null
                    }
                    val statusColor = when {
                        source.id == currentId -> 0xFF007AFF.toInt()
                        source.liveStatus == 2 -> 0xFFFF3B30.toInt()
                        source.liveStatus == 1 -> 0xFF34C759.toInt()
                        else -> 0
                    }
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val uploadFile = java.io.File(context.filesDir, "live_icon_${source.id}.jpg")
                        if (uploadFile.exists()) {
                            runCatching {
                                val bmp = android.graphics.BitmapFactory.decodeFile(uploadFile.absolutePath)
                                val (top, bottom) = liveColors[source.colorIndex % liveColors.size]
                                TileArtFactory.bitmapTileFromBitmap(context, bmp, top, bottom, statusText, statusColor)
                            }.getOrNull() ?: fallback.art
                        } else {
                            val resId = liveSourceIconResId(source)
                            if (resId != null) {
                                val (top, bottom) = liveColors[source.colorIndex % liveColors.size]
                                val bmp = android.graphics.BitmapFactory.decodeResource(context.resources, resId)
                                runCatching { TileArtFactory.bitmapTileFromBitmap(context, bmp, top, bottom, statusText, statusColor) }.getOrNull() ?: fallback.art
                            } else fallback.art
                        }
                    }
                }
                val app = remember(fallback, artState.value) { fallback.copy(art = artState.value ?: fallback.art) }
                // 标准行优先换行：第一排在最上，后续行向屏幕底部叠。
                val colIndex = index % columns
                val rowFromTop = index / columns
                val xPx = with(density) { (gridMargin + (tileW + gridGap) * colIndex).roundToPx() }
                val yPx = with(density) { rowY(rowFromTop).roundToPx() }
                val targetOffset = IntOffset(xPx, yPx)
                val animatedOffset by animateIntOffsetAsState(
                    targetOffset,
                    spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessHigh),
                    label = "slot",
                )
                Box(modifier = Modifier.offset { animatedOffset }.alpha(if (shifted in -1..1) 1f else 0f)) {
                    AppTile(
                        app = app,
                        width = tileW,
                        height = tileH,
                        focusRequester = requester(source.id),
                        enabled = menuForId == null && visible,
                        moving = movingId == source.id,
                        appearDelayMs = null,
                        onFocused = { focusedId = source.id; Sounds.focusMoved() },
                        onClick = { if (movingId == null) { if (source.url.isBlank()) { bannerMsg = "「${source.name}」暂未设置直播地址" } else { onSelect(source.id); onDismiss() } } },
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
            if (bannerMsg != null) {
                BasicText(
                    bannerMsg!!,
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
                val menuCol = sourceIndex % columns
                val menuRowFromTop = sourceIndex / columns
                // 上下文菜单位置：在该卡片正上方弹出(菜底距卡顶20dp)
                val menuBottomPad = screenH - rowY(menuRowFromTop) + 20.dp
                val first = remember { FocusRequester() }
                LaunchedEffect(menuForId) { first.requestFocus() }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = gridMargin + (tileW + gridGap) * menuCol + tileW / 2 - 125.dp,
                            bottom = menuBottomPad,
                        )
                        .width(250.dp)
                        .liquidGlass(20.dp)
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(source.name, style = TextStyle(Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp))
                    MenuRow(Glyph.Move, "移动", { movingId = source.id; menuForId = null }, focusRequester = first)
                    if (source.id != "preset_2") {
                        MenuRow(Glyph.EyeSlash, "从直播预览隐藏", { onHide(source.id); menuForId = null })
                        MenuRow(Glyph.Trash, "删除", { onDelete(source.id); menuForId = null }, destructive = true)
                    }
                }
            }
        }
    }
}
