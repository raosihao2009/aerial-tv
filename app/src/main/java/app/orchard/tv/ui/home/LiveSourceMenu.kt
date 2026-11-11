package app.orchard.tv.ui.home

import android.content.ComponentName
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.drawscope.Stroke
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
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.TileArtFactory
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.Type
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntSize

/** 焦点缩放的演出时长: 菜单在这之后才开始淡入 */
private const val FOCUS_SETTLE_MS = 260L

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
    val resId = liveSourceIconResId(source)
    val art = if (resId != null) {
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = android.graphics.BitmapFactory.decodeResource(ctx.resources, resId, opts)
        runCatching { TileArtFactory.bitmapTileFromBitmap(ctx, bmp, top, bottom, null, 0) }.getOrNull()
            ?: TileArtFactory.glyphTile(top, bottom, Glyph.Play)
    } else {
        TileArtFactory.glyphTile(top, bottom, Glyph.Play)
    }
    return AppEntry(
        packageName = "live_${source.id}",
        label = source.name,
        component = ComponentName("app.orchard.tv", "app.orchard.tv.MainActivity"),
        leanback = true,
        removable = !source.isPreset,
        art = art,
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
    controlCenterOpen: Boolean = false,
    network: app.orchard.tv.system.NetworkStatus = app.orchard.tv.system.NetworkStatus(),
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
    // 上下文菜单关闭后焦点行为对齐一级菜单
    var lastMenuId by remember { mutableStateOf<String?>(null) }
    var hiddenIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(menuForId) {
        if (menuForId != null) {
            lastMenuId = menuForId
        } else if (lastMenuId != null) {
            val gone = !sources.any { it.id == lastMenuId }
            if (gone) {
                // 被隐藏了: 等一帧让列表更新, 然后聚焦相邻可见源
                androidx.compose.runtime.withFrameNanos { }
                // 用隐藏前记录的索引找相邻项: 被隐藏项移除后, 它的位置由后面的项补位
                val fallbackId = sources.getOrNull(hiddenIndex)?.id
                    ?: sources.getOrNull(hiddenIndex - 1)?.id
                    ?: sources.firstOrNull()?.id
                if (fallbackId != null) {
                    focusedId = fallbackId
                    runCatching { requester(fallbackId).requestFocus() }
                }
            } else {
                // 菜单关闭但没隐藏: 立即把焦点拉回卡片, 抢在Compose自动落焦点到pill之前
                val id = lastMenuId
                if (id != null) {
                    focusedId = id
                    runCatching { requester(id).requestFocus() }
                }
            }
            lastMenuId = null
            hiddenIndex = -1
        }
    }
    val density = LocalDensity.current

    // CC关闭返回时聚焦pill(和一级菜单一致)
    var wasCcOpen by remember { mutableStateOf(false) }
    val pillFocus = remember { FocusRequester() }
    // 刚打开时pill临时不可聚焦, 让焦点自然落到直播卡片上, 避免从右上角跳转的动画
    // 初始值true: 组件首次组合时justOpened就为true, 只有当前直播卡片可聚焦
    var justOpened by remember { mutableStateOf(true) }

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

    // 打开量: 0=完全收起, 1=完全展开。用它驱动屏保那一套动作
    // (卡片逐列错峰下沉淡出 + 时钟缩小 + 底板退场)。
    // 用 Animatable 而不是 animateFloatAsState: 打开时要先等焦点(和卡片缩放)演完再淡入,
    // 否则会看到卡片在菜单里放大。
    val alpha = androidx.compose.animation.core.Animatable(0f)

    // 焦点时机:
    // - 打开: 先把焦点放到当前直播卡片上, 等它的缩放演完(此间菜单还没开始淡入, 全不可见),
    //   再淡入 —— 整个过程看不到卡片放大。justOpened 让除当前卡片外的节点(含右上角控制中心)不可聚焦。
    // - 关闭: 淡出全程保持焦点和选中尺寸不变, 菜单完全看不见之后才把焦点挪走 —— 看不到缩小。
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(visible) {
        if (visible) {
            if (wasCcOpen) {
                wasCcOpen = false
                justOpened = true
                repeat(2) { androidx.compose.runtime.withFrameNanos { } }
                runCatching { pillFocus.requestFocus() }
                alpha.animateTo(1f, androidx.compose.animation.core.tween(500, easing = Motion.EaseInOut))
                justOpened = false
            } else {
                focusedId = currentId
                justOpened = true
                repeat(2) { androidx.compose.runtime.withFrameNanos { } }
                runCatching { requester(currentId).requestFocus() }
                // 焦点缩放演完之后才淡入: 缩放全程在看不见的时候发生
                kotlinx.coroutines.delay(FOCUS_SETTLE_MS)
                runCatching { requester(currentId).requestFocus() }
                alpha.animateTo(1f, androidx.compose.animation.core.tween(500, easing = Motion.EaseInOut))
                justOpened = false
            }
        } else {
            alpha.animateTo(0f, androidx.compose.animation.core.tween(500, easing = Motion.EaseInOut))
            justOpened = true
            runCatching { focusManager.clearFocus(force = true) }
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // 不再整层做透明度: 卡片逐列错峰下沉淡出、时钟缩小、底板退场, 和屏保/一级同一套
            .onPreviewKeyEvent { e ->
                if (!visible || controlCenterOpen) return@onPreviewKeyEvent false
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
        LaunchedEffect(controlCenterOpen) {
            if (controlCenterOpen) wasCcOpen = true
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { e ->
                    if (!visible) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode != NativeKeyEvent.KEYCODE_DPAD_UP) {
                    }
                    // 任何方向键/确认键后才允许菜单键呼出上下文菜单
                    if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_UP ||
                        e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_DOWN ||
                        e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_LEFT ||
                        e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_RIGHT ||
                        e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_CENTER) {
                        ignoreMenuKeyUp = false
                    }
                    // UP键在第一行时聚焦pill
                    if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_UP && e.type == KeyEventType.KeyDown) {
                        val fi = sources.indexOfFirst { it.id == focusedId }
                        val isTopRow = fi >= 0 && fi < columns
                        if (isTopRow && menuForId == null && movingId == null) {
                            runCatching { pillFocus.requestFocus() }
                            return@onPreviewKeyEvent true
                        }
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
                        .padding(top = 80.dp),
                    // 和屏保过渡一样: 收起时缩回角落小钟
                    toSaver = { 1f - alpha.value },
                )
            } else if (!clockRight) {
                CornerClock(
                    now = java.time.LocalDateTime.now(),
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 28.dp),
                    toSaver = { 1f - alpha.value },
                )
            }
            // 控制中心（一级菜单同款 StatusPill）
            StatusPill(
                now = java.time.LocalDateTime.now(),
                network = network,
                showTime = false,
                enabled = menuForId == null && movingId == null && !controlCenterOpen && !justOpened,
                focusRequester = pillFocus,
                onFocused = {},
                onClick = { onOpenControlCenter() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 42.dp)
                    .graphicsLayer { this.alpha = alpha.value }
                    .focusProperties {
                    up = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                    left = requester(focusedId)
                    down = requester(focusedId)
                }
            )
            // 绝对定位每个卡片，带位置动画（完全照搬一级菜单 GridMetrics）
            // 行Y坐标逻辑（对齐 HomeScreen.topRowY）:
            //   双排: 底行贴 baseline, 上行 = 底行 - pitchY
            //   单排+露出: 主行上抬12dp, 次行露12dp边; 焦点到次排时上抬两行全可见
            //   单排+不露出: 主行贴baseline, 次行在屏幕外; 焦点到次排时上抬两行全可见
            val numRows = (sources.size + columns - 1) / columns
            val focusedIndex = remember(sources, focusedId) { sources.indexOfFirst { it.id == focusedId }.coerceAtLeast(0) }
            val focusedRow = focusedIndex / columns
            // 双排: 焦点行贴底, 上一行在上方, 无peek
            var bottomRow = focusedRow
            if (bottomRow >= numRows) bottomRow = numRows - 1
            // 和一级菜单 topRowY 同一个算法, 逐支对上:
            //   有底板+露一排: screenH - pitchY - 12 - 28
            //   有底板:        screenH - tileH - 50 - 28
            //   没底板+露一排: screenH - pitchY - 12
            //   没底板:        screenH - tileH - 70
            val peeking = AerialSettings.peekNextRow && bottomRow + 1 < numRows
            val baseY = remember(screenH, tileH, pitchY, peeking, AerialSettings.homePlate) {
                when {
                    AerialSettings.homePlate && peeking -> screenH - pitchY - 40.dp
                    AerialSettings.homePlate -> screenH - tileH - 78.dp
                    peeking -> screenH - pitchY - 12.dp
                    else -> screenH - tileH - 70.dp
                }
            }
            val pitchYVal = remember(tileH) { pitchY }
            // 下面那一排: 露出时让它的上沿停在屏幕底边上方 12dp, 不露出时正好压在屏幕外
            val nextTop = if (peeking) screenH - 12.dp else screenH
            val nextOffset = nextTop - (baseY + pitchYVal)
            fun rowY(rowFromTop: Int): Dp = baseY + pitchYVal * (rowFromTop - bottomRow) +
                if (rowFromTop > bottomRow) nextOffset else 0.dp
            // 玻璃底板：照搬一级菜单书架底板，铺在当前可见的直播卡片行下面(小屏是两行)
            if (AerialSettings.homePlate && sources.isNotEmpty()) {
                val plateFirstRow = (bottomRow - 1).coerceAtLeast(0)
                val plateTop = rowY(plateFirstRow) - 20.dp
                // 名字画在底板上: 高度 = 卡片 + 名字那一段, 和一级菜单同一套 (PlatePadV + PlateNameRoom)
                val plateHeight = rowY(bottomRow) + tileH + 20.dp + 28.dp - plateTop
                val plateY by animateDpAsState(
                    plateTop,
                    spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessHigh),
                    label = "livePlateY",
                )
                val plateW = tileW * columns + gridGap * (columns - 1) + 52.dp
                val plateX = gridMargin - 26.dp
                // 和一级菜单的 bakeLivePlate 完全同一套参数, 只把"壁纸那层的颜色"换成中性灰;
                // 但这里用矢量方式画(不烘焙成图), 首排/双排高度变化时 32dp 圆角永远不变形。
                //   磨砂: 灰 55% (FILM_FROST)
                //   烟灰: argb(72,0,0,0) (FILM_SMOKE)
                //   圆角: 32dp (PlateCorner), 描边 1.2dp (glassEdge 的 rim)
                //   顶部光泽: 白 10% -> 中段 0 (glassEdge 的 sheen)
                //   亮边: 白 55% -> 10% -> 6% -> 30% 的横向渐变 (glassEdge 的 rim)
                val greyLevel = remember(Wallpapers.backdrop) { frostGreyLevel() }
                Box(
                    Modifier
                        .offset(x = plateX, y = plateY)
                        .width(plateW)
                        .height(plateHeight)
                        .graphicsLayer { this.alpha = alpha.value }
                        .drawBehind {
                            val corner = 32.dp.toPx()
                            val rim = 1.2.dp.toPx()
                            // 磨砂灰(和一级 plateTinted 之后的平均亮度一致) 55%
                            drawRoundRect(
                                Color(greyLevel, greyLevel, greyLevel).copy(alpha = 0.55f),
                                cornerRadius = CornerRadius(corner),
                            )
                            // 烟灰压暗
                            drawRoundRect(Color(0x48000000), cornerRadius = CornerRadius(corner))
                            // 顶部那道光泽, 到中间淡没
                            drawRoundRect(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to Color(0x1AFFFFFF),
                                    0.5f to Color(0x00FFFFFF),
                                    startY = 0f,
                                    endY = size.height,
                                ),
                                cornerRadius = CornerRadius(corner),
                            )
                            // 一圈亮边: 左上最亮, 往下和往右变淡, 末角再抬起来
                            drawRoundRect(
                                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    0f to Color(0x8CFFFFFF),
                                    0.35f to Color(0x1AFFFFFF),
                                    0.7f to Color(0x0FFFFFFF),
                                    1f to Color(0x4DFFFFFF),
                                ),
                                topLeft = androidx.compose.ui.geometry.Offset(rim / 2, rim / 2),
                                size = androidx.compose.ui.geometry.Size(
                                    size.width - rim,
                                    size.height - rim,
                                ),
                                cornerRadius = CornerRadius(corner - rim / 2),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(rim),
                            )
                        }
                )
            }
            sources.forEachIndexed { index, source ->
                val rowFromTop = index / columns
                val shifted = rowFromTop - bottomRow
                androidx.compose.runtime.key(source.id) {
                val fallback = remember(source.id, source.name) { liveSourceAppEntry(source, context) }
                // 异步解码bitmap, 状态标签变化时只重画标签不重新解码
                val baseBmpState = androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
                    initialValue = null, source.id, source.iconTs
                ) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val uploadFile = java.io.File(context.filesDir, "live_icon_${source.id}.jpg")
                        if (uploadFile.exists()) {
                            runCatching { android.graphics.BitmapFactory.decodeFile(uploadFile.absolutePath) }.getOrNull()
                        } else {
                            val resId = liveSourceIconResId(source)
                            if (resId != null) {
                                // 预设源图标较大, 压缩到合适尺寸避免OOM但保持清晰
                                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                                runCatching { android.graphics.BitmapFactory.decodeResource(context.resources, resId, opts) }.getOrNull()
                            } else null
                        }
                    }
                }
                val artState = androidx.compose.runtime.produceState<app.orchard.tv.apps.TileArt?>(
                    initialValue = fallback.art, baseBmpState.value, source.liveStatus, source.id == currentId
                ) {
                    val statusText = when {
                        source.liveStatus == 2 -> "未开播"
                        source.id == currentId -> "直播中"
                        source.liveStatus == 1 -> "已开播"
                        else -> null
                    }
                    val statusColor = when {
                        source.liveStatus == 2 -> 0xFFFF3B30.toInt()
                        source.id == currentId -> 0xFF007AFF.toInt()
                        source.liveStatus == 1 -> 0xFF34C759.toInt()
                        else -> 0
                    }
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val (top, bottom) = liveColors[source.colorIndex % liveColors.size]
                        runCatching { TileArtFactory.bitmapTileFromBitmap(context, baseBmpState.value, top, bottom, statusText, statusColor) }.getOrNull() ?: fallback.art
                    }
                }
                val app = remember(fallback, artState.value) { fallback.copy(art = artState.value ?: fallback.art) }
                // 标准行优先换行：第一排在最上，后续行向屏幕底部叠。
                val colIndex = index % columns
                val rowFromTop = index / columns
                val xPx = with(density) { (gridMargin + (tileW + gridGap) * colIndex).roundToPx() }
                val yPx = with(density) { rowY(rowFromTop).roundToPx() }
                // 用graphicsLayer做位移动画，避免每帧触发布局(relayout)，当贝H5低GPU友好
                val animX by androidx.compose.animation.core.animateIntAsState(
                    xPx, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessHigh), label = "slotX")
                val animY by androidx.compose.animation.core.animateIntAsState(
                    yPx, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessHigh), label = "slotY")
                // 屏保那一套: 从左到右逐列错峰, 每列下沉 18dp 并淡出
                val cardFade = ((1f - alpha.value - 0.05f * colIndex) / 0.6f).coerceIn(0f, 1f)
                val isFirstCol = colIndex == 0
                val isLastCol = colIndex == columns - 1 || index == sources.size - 1
                val isLastRow = rowFromTop == (sources.size - 1) / columns && index >= sources.size - 1 - columns
                Box(modifier = Modifier
                    .graphicsLayer {
                        translationX = animX.toFloat()
                        translationY = animY.toFloat() + cardFade * 18.dp.toPx()
                    }
                    .alpha((if (shifted in -1..1) 1f else 0f) * (1f - cardFade))
                    .focusProperties {
                        if (isFirstCol) left = FocusRequester.Cancel
                        if (isLastCol) right = FocusRequester.Cancel
                        if (isLastRow) down = FocusRequester.Cancel
                    }
                ) {
                    AppTile(
                        app = app,
                        width = tileW,
                        height = tileH,
                        focusRequester = requester(source.id),
                        // justOpened期间只有当前直播卡片可聚焦, 其他卡片disabled, 让Compose自动聚焦到它
                        enabled = menuForId == null && visible && !controlCenterOpen && (!justOpened || source.id == currentId),
                        moving = movingId == source.id,
                        onFocused = { focusedId = source.id; Sounds.focusMoved() },
                        onClick = { if (movingId == null) {
                            if (source.url.isBlank()) {
                                bannerMsg = "「${source.name}」暂未设置直播地址"
                            } else if (source.liveStatus == 2) {
                                val oldName = sources.find { it.id == currentId }?.name ?: "央视网快看"
                                bannerMsg = "「${source.name}」未开播，请继续观看「$oldName」"
                            } else {
                                onSelect(source.id); onDismiss()
                            }
                        } },
                        onLongPress = { if (movingId == null) menuForId = source.id },
                        rowEnd = index % columns == columns - 1 || index == sources.size - 1,
                        // 和一级菜单一样: 底板开着时名字画在底板上, 不占卡片内部高度,
                        // 否则卡片里的图会矮一截, 和一级菜单对不上
                        showName = if (AerialSettings.homePlate) showAllNames else showAllNames || focusedId == source.id,
                        nameOnPlate = AerialSettings.homePlate,
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
                        MenuRow(Glyph.EyeSlash, "从直播菜单隐藏", {
                            // 记录被隐藏项在旧列表中的索引, 用于隐藏后转移焦点到相邻项
                            hiddenIndex = sources.indexOf(source)
                            onHide(source.id); menuForId = null
                        })
                        MenuRow(Glyph.Trash, "删除", { onDelete(source.id); menuForId = null }, destructive = true)
                    }
                }
            }
        }
    }
}
