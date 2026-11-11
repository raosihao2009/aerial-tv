package app.orchard.tv.ui

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
import android.os.SystemClock
import android.view.View
import app.orchard.tv.ui.components.smoothCut
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 开屏的那张图: 纯黑, 中间横排着 Aerial 的图标和白色字。窗口背景([SplashBackground])和遮罩
 * ([SplashVeil])画的是同一份, 所以窗口一可见就看得见它, 内容视图后到也没有接缝。
 *
 * 图标的圆角用卡片圆角的半径: 卡片画在设计稿 960 宽的画布上、圆角 12dp, 也就是屏宽的 1/80,
 * 这里按同一个比例切。
 */
private class SplashArt(context: Context) {
    val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.04f
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

    private var rounded: Bitmap? = null
    private var roundedKey = ""

    /** 切成卡片圆角的图标, 按尺寸缓存(换屏幕尺寸只做一次)。 */
    private fun roundedIcon(side: Int, radius: Float): Bitmap? {
        val src = icon ?: return null
        val key = "$side/$radius"
        if (rounded != null && roundedKey == key) return rounded
        return runCatching { smoothCut(src, side, side, radius) }
            .onSuccess { rounded = it; roundedKey = key }
            .getOrNull()
    }

    fun drawBrand(canvas: Canvas, w: Float, h: Float, fade: Float) {
        if (fade <= 0f) return
        word.textSize = min(w * 0.082f, h * 0.175f).coerceAtLeast(28f)
        val textW = word.measureText(BRAND)
        val iconSide = word.textSize
        val gap = word.textSize * 0.42f
        val iconRoom = if (icon != null) iconSide + gap else 0f
        val left = w / 2f - (iconRoom + textW) / 2f
        val radius = w * CARD_CORNER_RATIO
        val bmp = roundedIcon(iconSide.roundToInt(), radius)
        if (bmp != null) {
            iconPaint.alpha = (fade * 255).toInt().coerceIn(0, 255)
            val top = h / 2f - iconSide / 2f
            canvas.drawBitmap(bmp, null, RectF(left, top, left + iconSide, top + iconSide), iconPaint)
        }
        word.alpha = (fade * 255).toInt().coerceIn(0, 255)
        // 纵向居中对齐图标: 字的中线压在屏幕中线上
        canvas.drawText(BRAND, left + iconRoom, h / 2f - (word.descent() + word.ascent()) / 2f, word)
    }

    private companion object {
        const val BRAND = "Aerial"
        /** 卡片圆角 12dp / 设计画布 960 = 1/80。 */
        const val CARD_CORNER_RATIO = 1f / 80f
    }
}

/**
 * 窗口背景画的开屏图。窗口一出现(内容视图还没建好、Compose 还没组合)系统就把它铺上, 所以
 * 不会再出现"先看两秒壁纸, 然后才黑下来"——遮罩是跟着内容视图一起加进去的, 内容视图建得慢,
 * 那两秒露出来的就是上次的烘焙壁纸。
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
 * 冷启动遮罩: 一块纯黑, 中间横排着 Aerial 的图标和白色字(和窗口背景同一张图, 所以无缝接上)。
 * 桌面的首帧(壁纸、底板、卡片)真的画出来之后, 白字先淡到透明, 再从字的位置扩出一个圆把黑幕推开。
 *
 * 动画自己按 [SystemClock] 逐帧算, 不走 ValueAnimator: 电视盒子常把开发者选项里的"动画时长
 * 缩放"关成 0, 那时 ValueAnimator 会直接跳到终点, 看起来就是"没有过渡"。
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
    private var startAt = 0L

    /** 揭开之后叫一声: 窗口背景换回纯黑, 不用每帧再画一遍字。 */
    var onRevealed: (() -> Unit)? = null

    init {
        // 黑幕期间不接受焦点、不响应触摸, 让下面的桌面照常准备自己的焦点。
        isFocusable = false
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measureCenter()
        invalidate()
        if (pending) start()
    }

    private fun measureCenter() {
        cx = width / 2f
        cy = height / 2f
        maxR = hypot(cx, cy) + 2f
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
        // 圆推开时字还没淡完(两个动画有重叠), 一起收掉更自然
        if (fade > 0f) art.drawBrand(canvas, width.toFloat(), height.toFloat(), fade)
    }

    /** 桌面首帧到了: 白字淡出, 随即从字的位置把黑幕推开。只做一次。 */
    fun revealNow() {
        if (started || pending) return
        if (width == 0 || height == 0) {
            // 内容视图还没量过: 现在开始的话圆心和最大半径都是 0, 等于没有过渡。
            pending = true
            return
        }
        start()
    }

    private fun start() {
        if (started) return
        pending = false
        started = true
        measureCenter()
        startAt = SystemClock.uptimeMillis()
        postOnAnimation(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val e = SystemClock.uptimeMillis() - startAt
            fade = (1f - e.toFloat() / FADE_MS).coerceIn(0f, 1f)
            val p = ((e - OPEN_DELAY_MS).toFloat() / OPEN_MS).coerceIn(0f, 1f)
            reveal = easeOut(p)
            invalidate()
            if (p < 1f || fade > 0f) postOnAnimation(this) else finish()
        }
    }

    /** 尾部收得快一点, 推开时先快后慢, 像 tvOS 那样"一下铺开"。 */
    private fun easeOut(p: Float): Float = 1f - (1f - p) * (1f - p) * (1f - p)

    private fun finish() {
        onRevealed?.invoke()
        (parent as? android.view.ViewGroup)?.removeView(this)
    }

    private companion object {
        const val FADE_MS = 300f
        const val OPEN_DELAY_MS = 140f
        const val OPEN_MS = 680f
    }
}

/**
 * 遮罩与桌面之间的信号: HomeScreen 在首帧真的画完之后 [reveal], 遮罩收到就把自己揭开。
 * 遮罩还挂在窗口上时提前来了信号也没关系, 每次挂新遮罩重新等一次。
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
