package app.orchard.tv.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.hypot
import kotlin.math.min

/**
 * 开屏的那张图: 纯黑, 中间是 Aerial 的图标和白色字。窗口背景([SplashBackground])和遮罩
 * ([SplashVeil])画的是同一份, 所以窗口一可见就看得见它, 内容视图后到也没有接缝。
 */
private class SplashArt(context: Context) {
    val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.12f
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 图标; 取不到就不画(有的电视把 leanback banner 当图标, 尺寸很小)。 */
    val icon: Bitmap? = runCatching {
        val d: Drawable = context.packageManager.getApplicationIcon(context.packageName)
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 96
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 96
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(bmp))
        }
    }.getOrNull()

    fun drawBrand(canvas: Canvas, w: Float, h: Float, fade: Float) {
        if (fade <= 0f) return
        word.textSize = min(w * 0.055f, h * 0.115f).coerceAtLeast(24f)
        val textH = word.descent() - word.ascent()
        val gap = word.textSize * 0.55f
        val iconSide = word.textSize * 1.35f
        val blockH = (if (icon != null) iconSide + gap else 0f) + textH
        var top = h / 2f - blockH / 2f
        val bmp = icon
        if (bmp != null) {
            iconPaint.alpha = (fade * 255).toInt().coerceIn(0, 255)
            val l = w / 2f - iconSide / 2f
            canvas.drawBitmap(bmp, null, RectF(l, top, l + iconSide, top + iconSide), iconPaint)
            top += iconSide + gap
        }
        word.alpha = (fade * 255).toInt().coerceIn(0, 255)
        val textW = word.measureText(BRAND)
        canvas.drawText(BRAND, w / 2f - textW / 2f, top - word.ascent(), word)
    }

    private companion object {
        const val BRAND = "Aerial"
    }
}

/**
 * 窗口背景画的开屏图。窗口一出现(内容视图还没建好、Compose 还没组合)系统就把它铺上, 所以
 * 不会再出现"先看两秒壁纸, 然后才黑下来"——上一版遮罩是跟着内容视图一起加进去的, 内容视图
 * 建得慢, 那两秒露出来的就是上次的烘焙壁纸。
 */
class SplashBackground(context: Context) : Drawable() {
    private val art = SplashArt(context)

    override fun draw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        art.drawBrand(canvas, bounds.width().toFloat(), bounds.height().toFloat(), 1f)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * 冷启动遮罩: 一块纯黑, 中间是 Aerial 的图标和白色字(和窗口背景同一张图, 所以无缝接上)。
 * 桌面的首帧(壁纸、底板、卡片)画好之后, 白字先淡到透明, 再从字的位置扩出一个圆把黑幕推开,
 * 露出已经画好的桌面 —— 用户不会看到"先出壁纸、再过几帧才出卡片"的拼装过程。
 *
 * 它是一块普通的 [View], 不是 Compose: 早前在 setContent 前先挂一个 ComposeView 预热, 抢走了
 * 窗口唯一的 Recomposer, 摘掉之后主界面再也拿不到帧, 于是永远黑屏。这里只画 canvas, 不碰 Compose。
 */
class SplashVeil(context: Context) : View(context) {

    private val art = SplashArt(context)
    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    /** 1 → 白字与图标完全可见; 0 → 全透明。 */
    private var fade = 1f
    /** 0 → 全黑; 1 → 圆已经盖满整屏, 黑幕完全推开。 */
    private var reveal = 0f
    private var cx = 0f
    private var cy = 0f
    private var maxR = 1f
    private var started = false
    /** 桌面报"好了"时报得太早(还没量出尺寸), 等 [onSizeChanged] 再真正开始。 */
    private var pending = false

    /** 揭开之后叫一声: 窗口背景换回纯黑, 不用每帧再画一遍字。 */
    var onRevealed: (() -> Unit)? = null

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
        invalidate()
        if (pending && w > 0 && h > 0) start()
    }

    override fun onDraw(canvas: Canvas) {
        if (reveal <= 0f) {
            canvas.drawColor(Color.BLACK)
            art.drawBrand(canvas, width.toFloat(), height.toFloat(), fade)
            return
        }
        // 黑幕只在圆外: 用离屏层把黑填满, 再在那个位置把圆抠掉。
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawColor(Color.BLACK)
        canvas.drawCircle(cx, cy, maxR * reveal, clear)
        canvas.restoreToCount(layer)
        // 圆推开时字还没淡完 (两个动画有 90ms 重叠), 一起收掉更自然
        if (fade > 0f) art.drawBrand(canvas, width.toFloat(), height.toFloat(), fade)
    }

    /** 桌面首帧到了: 白字淡出, 随即从字的位置把黑幕推开。只做一次。 */
    fun revealNow() {
        if (started || pending) return
        if (width == 0 || height == 0) {
            // 内容视图还没量过: 这时候开始的话圆心和最大半径都是 0, 等于没有过渡。
            pending = true
            return
        }
        start()
    }

    private fun start() {
        if (started) return
        pending = false
        started = true
        cx = width / 2f
        cy = height / 2f
        maxR = hypot(cx, cy) + 2f
        val fadeOut = ValueAnimator.ofFloat(1f, 0f).apply {
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
        open.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onRevealed?.invoke()
                (parent as? android.view.ViewGroup)?.removeView(this@SplashVeil)
            }
        })
        fadeOut.start()
        open.start()
    }
}

/**
 * 遮罩与桌面之间的信号: HomeScreen 在第一帧画好后 [reveal], 遮罩收到就把自己揭开。
 * 遮罩还挂在窗口上时提前来了信号也没关系, [attach] 时会补上; 换一次遮罩重新等一次。
 */
object BootVeil {
    private var veil: SplashVeil? = null
    private var signaled = false

    fun attach(v: SplashVeil) {
        veil = v
        signaled = false
    }

    fun reveal() {
        signaled = true
        veil?.revealNow()
    }
}
