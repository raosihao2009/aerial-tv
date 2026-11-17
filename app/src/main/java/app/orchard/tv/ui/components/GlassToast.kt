package app.orchard.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 磨砂玻璃轻提示(全站统一外观): 出现时从屏幕底部滑上来, 消失时滑下去。
 *
 * 为什么不能直接画 BasicText: 提示内容置空的那一刻整棵子树就被移除了, 没有机会做退场动画。
 * 这里用 AnimatedVisibility 承接进出场, 并留一份最后一条文案, 让滑下去的过程中还有字可画。
 */
@Composable
fun GlassToast(
    message: String?,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 28.dp,
) {
    var lastMessage by remember { mutableStateOf("") }
    if (message != null) lastMessage = message
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = slideInVertically(
            animationSpec = tween(TOAST_SLIDE_MS, easing = FastOutSlowInEasing),
            initialOffsetY = { fullHeight -> fullHeight },
        ) + fadeIn(tween(TOAST_SLIDE_MS)),
        exit = slideOutVertically(
            animationSpec = tween(TOAST_SLIDE_MS, easing = FastOutSlowInEasing),
            targetOffsetY = { fullHeight -> fullHeight },
        ) + fadeOut(tween(TOAST_SLIDE_MS)),
    ) {
        BasicText(
            lastMessage,
            style = TextStyle(color = Color.White, fontSize = 14.sp),
            modifier = Modifier
                .padding(bottom = bottomPadding)
                .liquidGlass(20.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

/**
 * 滑动时长: 太长像卡, 太短看不见动。
 * 淡入淡出用同一个时长(以前淡出只有 160ms, 比滑动先结束, 收尾那 100ms 什么都看不见,
 * 看着像"没有淡出动画", 直接消失)。
 */
private const val TOAST_SLIDE_MS = 260
