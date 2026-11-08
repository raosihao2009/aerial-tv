package app.orchard.tv.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * ChannelFocusPreview 的状态聚合。
 *
 * 两类状态分开管理：
 * - [disposed] / [paused]：纯守卫旗标，只被协程、Player.Listener、看门狗用来判断
 *   "是否还该继续动作"，不驱动 UI 重组。改成普通 @Volatile var，避免每次写都触发重组，
 *   也避免被错误地当成 UI 状态读进 composition 导致不必要的重绘。
 * - [loading] / [toastMessage]：真正的 UI 状态，驱动加载遮罩和底部提示条的重组，
 *   保留 Compose mutableStateOf。
 */
class PreviewState {
    /** 组合已离开(onDispose)：释放 player 时用，区分"真错误"和"dispose 释放"。 */
    @Volatile
    var disposed: Boolean = false

    /** onPause 后台：停掉看门狗，避免后台继续判断卡顿/放音。 */
    @Volatile
    var paused: Boolean = false

    /** 切源加载遮罩可见性。 */
    var loading: Boolean by mutableStateOf(false)

    /** 底部提示条文案，null 表示不显示。 */
    var toastMessage: String? by mutableStateOf(null)
}
