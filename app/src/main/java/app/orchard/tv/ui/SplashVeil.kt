package app.orchard.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import app.orchard.tv.ui.components.smoothCut
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

    /** [fade] 管图标, [wordFade] 管文字: 开屏时文字先淡到透明, 图标留到整体放大那一段。 */
    fun drawBrand(canvas: Canvas, w: Float, h: Float, fade: Float, wordFade: Float = fade) {
        if (fade <= 0f && wordFade <= 0f) return
        word.textSize = min(w * 0.082f, h * 0.175f).coerceAtLeast(28f)
        val textW = word.measureText(BRAND)
        val iconSide = word.textSize
        val gap = word.textSize * 0.42f
        val iconRoom = if (icon != null) iconSide + gap else 0f
        val left = w / 2f - (iconRoom + textW) / 2f
        val radius = w * CARD_CORNER_RATIO
        val bmp = roundedIcon(iconSide.roundToInt(), radius)
        if (bmp != null && fade > 0f) {
            iconPaint.alpha = (fade * 255).toInt().coerceIn(0, 255)
            val top = h / 2f - iconSide / 2f
            canvas.drawBitmap(bmp, null, RectF(left, top, left + iconSide, top + iconSide), iconPaint)
        }
        word.alpha = (wordFade * 255).toInt().coerceIn(0, 255)
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
 * 冷启动遮罩: 一块纯黑, 中间横排着 Aerial 的图标和白色字(和窗口背景同一张图, 无缝接上)。
 * 桌面的首帧(壁纸、底板、卡片)真的画出来、再等半秒之后开始过渡:
 *   ① 文字" Aerial "由白渐变为透明;
 *   ② 图标与文字这一组整体从小放大(向观众推近), 同时黑幕淡出, 桌面在它后面露出来。
 * 全程 2.5 秒, 用缓动曲线逐帧自算。早前那版是"圆形抠图", 圆的边缘在电视的缩放/合成上放大后
 * 有锯齿和色带, 所以换成这种整体缩放 + 淡出, 每一帧都只是位图和文字的缩放/透明度, 更顺。
 *
 * 动画自己按 [SystemClock] 逐帧算, 不走 ValueAnimator: 电视盒子常把开发者选项里的"动画时长
 * 缩放"关成 0, 那时 ValueAnimator 会直接跳到终点, 看起来就是"没有过渡"。
 *
 * 它是一块普通的 [View], 不是 Compose: 早前在 setContent 前先挂一个 ComposeView 预热, 抢走了
 * 窗口唯一的 Recomposer, 摘掉之后主界面再也拿不到帧, 于是永远黑屏。这里只画 canvas, 不碰 Compose。
 */
class SplashVeil(context: Context) : View(context) {

    private val art = SplashArt(context)
    private val black = Paint()

    /** 图标的透明度: 1 → 完全可见, 0 → 全透明。 */
    private var markAlpha = 1f
    /** 文字的透明度: 先于图标淡到 0。 */
    private var wordAlpha = 1f
    /** 黑幕的透明度: 1 → 全黑, 0 → 桌面完全露出来。 */
    private var veilAlpha = 1f
    /** 图标与文字这一组的放大倍数。 */
    private var zoom = 1f
    private var cx = 0f
    private var cy = 0f
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
    }

    override fun onDraw(canvas: Canvas) {
        // 黑幕只是一个会变淡的纯色矩形: 不用离屏层、不抠图, 低端 GPU 上也是一次填充。
        if (veilAlpha > 0f) {
            black.color = Color.argb((veilAlpha * 255).toInt().coerceIn(0, 255), 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), black)
        }
        if (markAlpha > 0f || wordAlpha > 0f) {
            // 以屏幕中心为锚点放大, 字和图标都还压在同一条中线上。
            canvas.save()
            canvas.scale(zoom, zoom, cx, cy)
            art.drawBrand(canvas, width.toFloat(), height.toFloat(), markAlpha, wordAlpha)
            canvas.restore()
        }
    }

    /** 桌面报"好了"之后再等一会儿才揭开: 底板和卡片刚上屏的那一下不算好光学, 停半秒更稳。 */
    fun armReveal(delayMs: Long) {
        if (started || pending) return
        postDelayed({ revealNow() }, delayMs)
    }

    /** 桌面首帧到了: 文字淡到透明, 图标与字放大, 黑幕淡出。只做一次。 */
    fun revealNow() {
        if (started || pending) return
        if (width == 0 || height == 0) {
            // 内容视图还没量过: 现在开始的话连中心点都还是 0, 等于没有过渡。
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
            // ① 文字先淡到透明(0 → 700ms)
            wordAlpha = 1f - smooth((e / WORD_MS).coerceIn(0f, 1f))
            // ② 一组整体放大(500 → 2400ms), 同时跟着放大淡出
            val pz = ((e - ZOOM_DELAY_MS) / ZOOM_MS).coerceIn(0f, 1f)
            zoom = 1f + (ZOOM_TO - 1f) * smooth(pz)
            markAlpha = 1f - smooth(pz)
            // ③ 黑幕在放大到一半以后开始淡, 到 2500ms 全开(桌面完全露出)
            veilAlpha = 1f - smooth(((e - VEIL_DELAY_MS) / VEIL_MS).coerceIn(0f, 1f))
            invalidate()
            if (e < TOTAL_MS) postOnAnimation(this) else finish()
        }
    }

    /** 两端都放慢的 smoothstep: 起手不突兀, 落点不急刹。 */
    private fun smooth(p: Float): Float = p * p * (3f - 2f * p)

    private fun finish() {
        onRevealed?.invoke()
        (parent as? android.view.ViewGroup)?.removeView(this)
    }

    private companion object {
        /** 过渡全程 2.5 秒。 */
        const val TOTAL_MS = 2500f
        /** 文字淡到透明。 */
        const val WORD_MS = 700f
        /** 整组放大: 500ms 起步, 到 2400ms 放大到 4 倍(边放大边淡出)。 */
        const val ZOOM_DELAY_MS = 500f
        const val ZOOM_MS = 1900f
        const val ZOOM_TO = 4f
        /** 黑幕淡出: 900ms 起, 到 2500ms 全开。 */
        const val VEIL_DELAY_MS = 900f
        const val VEIL_MS = 1600f
    }
}

/**
 * 遮罩与桌面之间的信号: HomeScreen 在首帧真的画完之后 [reveal], 遮罩收到就把自己揭开。
 * 遮罩还挂在窗口上时提前来了信号也没关系, 每次挂新遮罩重新等一次。
 */
object BootVeil {
    private var veil: SplashVeil? = null
    private var armed = false
    private var signaled = false

    /**
     * 本进程是否已经完整显示过一次桌面。开机时 Activity 会先后建两次(先解锁前那次只铺一张图,
     * 解锁后再真正启动), 所以不能靠 savedInstanceState 判断要不要盖开屏 —— 看这个标志:
     * 还没显示过桌面就盖, 从屏保回来、换分辨率重建时就不盖了。
     */
    var desktopShown = false
        private set

    fun attach(v: SplashVeil) {
        veil = v
        armed = false
        signaled = false
    }

    /** 桌面画完第一帧: 记下"显示过了", 并约好 [delayMs] 之后揭开。 */
    fun arm(delayMs: Long) {
        desktopShown = true
        if (armed || signaled) return
        armed = true
        veil?.armReveal(delayMs)
    }

    /** 兜底: 桌面一直没报"好了"也把黑幕揭开。 */
    fun reveal() {
        if (signaled) return
        signaled = true
        veil?.revealNow()
    }
}
