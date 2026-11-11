package app.orchard.tv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.hypot
import kotlin.math.min

/**
 * 冷启动遮罩: 一块纯黑, 中间是 Aerial 的图标和白色字。桌面的首帧(壁纸、底板、卡片)画好之后,
 * 白字先淡到透明, 再从字的位置扩出一个圆把黑幕推开, 露出已经画好的桌面 —— 所以用户不会看到
 * "先出壁纸、再过几帧才出卡片"的那种拼装过程, 看到的是一次干净的揭开。
 *
 * 它是一块普通的 [View], 不是 Compose: 上一版在 setContent 前先挂一个 ComposeView 预热,
 * 抢走了窗口唯一的 Recomposer, 摘掉之后主界面再也拿不到帧, 于是永远黑屏。这里只画 canvas,
 * 不碰 Compose, 也不参与焦点。
 */
class SplashVeil(context: Context) : View(context) {

    private val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.12f
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    /** 图标, 取不到就不画(有的电视把 leanback banner 当图标, 尺寸很小)。 */
    private val icon: Bitmap? = runCatching {
        val d: Drawable = context.packageManager.getApplicationIcon(context.packageName)
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 96
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 96
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(bmp))
        }
    }.getOrNull()

    /** 1 → 白字与图标完全可见; 0 → 全透明。 */
    private var fade = 1f
    /** 0 → 全黑; 1 → 圆已经盖满整屏, 黑幕完全推开。 */
    private var reveal = 0f
    private var cx = 0f
    private var cy = 0f
    private var maxR = 1f
    private var started = false

    init {
        // 黑幕期间不接受焦点、不响应触摸, 让下面的桌面照常准备自己的焦点。
        isFocusable = false
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        maxR = hypot(cx, cy) + 2f
        word.textSize = min(w * 0.055f, h * 0.115f).coerceAtLeast(24f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (reveal <= 0f) {
            canvas.drawColor(Color.BLACK)
            drawBrand(canvas)
            return
        }
        // 黑幕只在圆外: 用离屏层把黑填满, 再在那个位置把圆抠掉。
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawColor(Color.BLACK)
        canvas.drawCircle(cx, cy, maxR * reveal, clear)
        canvas.restoreToCount(layer)
        // 圆推开时字还没淡完(两个动画有 90ms 重叠), 一起收掉更自然
        if (fade > 0f) drawBrand(canvas)
    }

    private fun drawBrand(canvas: Canvas) {
        val textH = word.descent() - word.ascent()
        val gap = word.textSize * 0.55f
        val iconSide = word.textSize * 1.35f
        val blockH = iconSide + gap + textH
        var top = cy - blockH / 2f
        val bmp = icon
        if (bmp != null) {
            iconPaint.alpha = (fade * 255).toInt().coerceIn(0, 255)
            val l = cx - iconSide / 2f
            canvas.drawBitmap(bmp, null, android.graphics.RectF(l, top, l + iconSide, top + iconSide), iconPaint)
            top += iconSide + gap
        }
        word.alpha = (fade * 255).toInt().coerceIn(0, 255)
        val textW = word.measureText(BRAND)
        canvas.drawText(BRAND, cx - textW / 2f, top - word.ascent(), word)
    }

    /** 桌面首帧到了: 白字淡出, 随即从字的位置把黑幕推开。只做一次。 */
    fun revealNow() {
        if (started) return
        started = true
        val fadeIn = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 260
            interpolator = LinearInterpolator()
            addUpdateListener { fade = it.animatedValue as Float; invalidate() }
        }
        val open = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            startDelay = 170
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
        }
        open.doOnEndCompat {
            (parent as? android.view.ViewGroup)?.removeView(this)
        }
        fadeIn.start()
        open.start()
    }

    private inline fun ValueAnimator.doOnEndCompat(crossinline block: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = block()
        })
    }

    private companion object {
        const val BRAND = "Aerial"
    }
}

/**
 * 遮罩与桌面的之间的信号: HomeScreen 在第一帧画好后 [reveal], 遮罩收到就把自己揭开。
 * 遮罩还挂在窗口上时提前来了信号也没关系, [attach] 时会立刻补上。
 */
object BootVeil {
    private var veil: SplashVeil? = null
    private var signaled = false

    fun attach(v: SplashVeil) {
        veil = v
        if (signaled) v.revealNow()
    }

    fun reveal() {
        signaled = true
        veil?.revealNow()
    }
}
