package app.orchard.tv

import android.app.Application
import app.orchard.tv.system.DiagLog

/**
 * 进程起来的第一件事: 初始化日志。
 *
 * 之前最早的日志是 MainActivity.startReal 里的 "onCreate", 也就是窗口已经在建的时候。开机冷启动
 * 那一两秒(壁纸还没画、遮罩还没上)完全落在窗口之外, 正是"闪一下壁纸"发生的位置, 于是诊断里
 * 永远缺这一段。放到 Application 里就能把它接上, 顺带记下进程名 —— 主进程和守卫进程各记一笔。
 */
class AerialApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) {
            runCatching { getProcessName() }.getOrNull()
        } else null
        DiagLog.i("进程启动 ${name ?: "?"}(pid ${android.os.Process.myPid()})")
    }
}
