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
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.graphics.asAndroidPath
import app.orchard.tv.ui.components.smoothRectPath
import app.orchard.tv.R
import app.orchard.tv.ui.components.smoothCut
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 开屏的静态图: 纯黑, 中间横排着 Aerial 的图标和白色字。窗口背景([SplashBackground])和
 * 遮罩([MarkView])画的是同一份几何, 所以从窗口一可见到揭开, 中间不会有接缝。
 *
 * 字用包内的 Inter(和桌面同一套字体), 图标用应用图标, 圆角取卡片圆角的半径: 卡片画在设计稿 960
 * 宽的画布上、圆角 12dp, 也就是屏宽的 1/80, 这里按同一个比例切。
 */
/** 卡片圆角 12dp / 设计画布 960 = 1/80: 图标、卡片、开屏都按这个比例切。 */
private const val CARD_CORNER_RATIO = 1f / 80f

/** 连续圆角的 smoothing, 和 [AppleSmoothing] 一致。 */
private const val CORNER_SMOOTHING = 0.6f

private class SplashArt(context: Context) {
    val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = runCatching { ResourcesCompat.getFont(context, R.font.inter) }.getOrNull()
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.04f
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 图标: 用包里那张(品牌图标铺满整格的那一版, 见 drawable-nodpi/splash_icon.png), 不用
     * PackageManager 返回的自适应图标 —— 那个前景只占 108 画布的 37%, 放到开屏里比品牌图形小一圈。
     * 取不到就不画。
     */
    val icon: Bitmap? = runCatching {
        val d: Drawable = context.resources.getDrawable(R.drawable.splash_icon, null)!!
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

    /** 图标在屏幕上的位置: 上下排版, 图标在上、字在下, 整组在屏幕中线上。 */
    class Box(val side: Float, val left: Float, val top: Float) {
        val centerX: Float get() = left + side / 2
        val centerY: Float get() = top + side / 2
    }

    private var silhouette: android.graphics.Path? = null
    private var silhouetteKey = ""

    /**
     * 图标的外形轮廓: 放大时"镜头从图标里穿过去", 洞就是这个图标。路径每帧按当前倍数在设备
     * 分辨率下光栅, 边缘干净, 不会像放大位图那样出锯齿。
     */
    internal fun silhouette(w: Float, h: Float): android.graphics.Path {
        relayout(w, h)
        val key = "$w/$h"
        if (silhouette != null && silhouetteKey == key) return silhouette!!
        val path = smoothRectPath(
            androidx.compose.ui.geometry.Rect(left, top, left + iconSide, top + iconSide),
            w * CARD_CORNER_RATIO,
        ).asAndroidPath()
        silhouette = path
        silhouetteKey = key
        return path
    }

    private val iconRectF = RectF()
    /** 图标在屏幕上的矩形(上下排版后: 中间偏上那一块)。 */
    internal fun iconRect(w: Float, h: Float): RectF {
        relayout(w, h)
        iconRectF.set(left, top, left + iconSide, top + iconSide)
        return iconRectF
    }

    private var cachedW = -1f
    private var cachedH = -1f
    private var textW = 0f
    private var iconSide = 0f
    private var left = 0f
    private var top = 0f
    private var textLeft = 0f
    private var baseline = 0f

    /** "Aerial" 烤成一张白色位图: 每帧带不同 alpha 去 drawText 会让 Skia 每帧重新光栅化字形。 */
    private var wordBitmap: Bitmap? = null
    private var wordTop = 0f
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun relayout(w: Float, h: Float) {
        if (cachedW == w && cachedH == h) return
        cachedW = w
        cachedH = h
        word.textSize = sizeOf(w, h)
        // 每帧都 measureText 要重新 shaping 一遍, 弱 GPU 上就是一卡一卡的原因; 尺寸没变就算一次。
        textW = word.measureText(BRAND)
        // 上下排版: 图标是主角, 比字大一圈; 字在图标下面, 中间留一点气。
        iconSide = word.textSize * 1.45f
        val gap = word.textSize * 0.30f
        val fm = word.fontMetrics
        val textH = fm.descent - fm.ascent
        val total = iconSide + gap + textH
        top = h / 2f - total / 2f
        left = w / 2f - iconSide / 2f
        textLeft = w / 2f - textW / 2f
        baseline = top + iconSide + gap + fm.ascent
        bakeWord(w, h)
    }

    /**
     * 把 "Aerial" 画进一张位图(白字, 位置和直接 drawText 一模一样)。之后每帧只改这张图的
     * alpha: 一条带透明度的 drawText 每帧都会让 Skia 重做一遍字形光栅, 2.5 秒六十帧就是六十次。
     */
    private fun bakeWord(w: Float, h: Float) {
        val fm = word.fontMetrics
        val bw = (textW + 4f).toInt().coerceAtLeast(1)
        val bh = (fm.descent - fm.top + 4f).toInt().coerceAtLeast(1)
        val bmp = runCatching {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { b ->
                Canvas(b).drawText(BRAND, 2f, 2f - fm.top, word)
            }
        }.getOrNull() ?: return
        wordBitmap?.recycle()
        wordBitmap = bmp
        // 位图的 top 按基线反推(位图里画的基线是 2f - ascent)。
        wordTop = baseline + fm.top - 2f
    }

    fun box(w: Float, h: Float): Box {
        relayout(w, h)
        return Box(iconSide, left, top)
    }

    /** [fade] 管图标, [wordFade] 管文字: 开屏时文字先淡到透明, 图标留到整体放大那一段。 */
    fun drawBrand(canvas: Canvas, w: Float, h: Float, fade: Float, wordFade: Float = fade) {
        if (fade <= 0f && wordFade <= 0f) return
        relayout(w, h)
        val radius = w * CARD_CORNER_RATIO
        val bmp = roundedIcon(iconSide.roundToInt(), radius)
        if (bmp != null && fade > 0f) {
            iconPaint.alpha = (fade * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bmp, null, RectF(left, top, left + iconSide, top + iconSide), iconPaint)
        }
        wordBitmap?.let { b ->
            wordPaint.alpha = (wordFade * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(b, textLeft, wordTop, wordPaint)
        }
    }

    private fun sizeOf(w: Float, h: Float): Float = min(w * 0.082f, h * 0.175f).coerceAtLeast(28f)

    private companion object {
        const val BRAND = "Aerial"
    }
}

/**
 * 窗口背景画的开屏图。窗口一出现(内容视图还没建好、Compose 还没组合)系统就把它铺上, 所以
 * 不会再出现"先看两秒壁纸, 然后才黑下来"——遮罩是跟着内容视图一起加进去的, 内容视图建得慢,
 * 那两秒露出来的就是上次的烘焙壁纸。
 */
class SplashBackground(context: Context) : Drawable() {
    private val art = SplashArt(context)
    /** 0 时整张都不画: 遮罩盖上来之后每帧再画一遍这张字, 就是白花的钱。 */
    private var alpha = 255

    override fun draw(canvas: Canvas) {
        if (alpha <= 0) return
        // 窗口背景在整棵视图树后面, 它画的标志不可能压住遮罩层 —— 它只负责"内容视图还没建好"的
        // 那几帧(冷启动开机最先看到的那一下)。过渡动画的标志仍只由 MarkView 一份负责。
        canvas.drawColor(Color.BLACK)
        art.drawBrand(canvas, bounds.width().toFloat(), bounds.height().toFloat(), 1f)
    }

    override fun setAlpha(alpha: Int) {
        this.alpha = alpha
    }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * 冷启动遮罩: 短片在上, 桌面在黑幕后面先把壁纸、底板、卡片画好; 桌面报"好了"再等半秒, 然后:
 *   ① 文字"Aerial"由白渐变为透明;
 *   ② 图标与这一组整体放大(向观众推近), 标志本身渐渐只剩形状, 桌面从标志的形状里透出来;
 *   ③ 最后一段黑幕收薄到全透明, 桌面完全落定。
 * 全程 2.5 秒。开口的形状就是标志本身(不是圆): 圆的边缘在电视的缩放/合成上会出锯齿和色带,
 * 而标志是从一开始就在画面里的那个东西, 放大它没有"多出来的一条边"。
 *
 * 动画自己按 [SystemClock] 逐帧算, 不走 ValueAnimator: 电视盒子常把开发者选项里的"动画时长
 * 缩放"关成 0, 那时 ValueAnimator 会直接跳到终点, 看起来就是"没有过渡"。
 *
 * 它是普通的 View, 不是 Compose: 早前在 setContent 前先挂一个 ComposeView 预热, 抢走了窗口
 * 唯一的 Recomposer, 摘掉之后主界面再也拿不到帧, 于是永远黑屏。
 */
class SplashVeil(context: Context) : FrameLayout(context) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mark = MarkView(context)

    /** 揭开之后叫一声: 窗口背景换回纯黑, 不用每帧再画一遍字。 */
    var onRevealed: (() -> Unit)? = null

    private var started = false
    /** 桌面报"好了"时报得太早(还没量出尺寸), 等 [onSizeChanged] 再真正开始。 */
    private var pending = false
    private var startAt = 0L

    init {
        // 内容视图刚挂上、短片还没出第一帧之前, 这块背景就是窗口背景那张开屏静图(黑底+图标+Aerial)。
        // 以前这里铺的是纯黑: 窗口的静图先被它盖住, 短片又要等媒体服务冷启动几秒, 于是
        // "先看到 logo → 黑掉几秒 → logo 又出来"。静图和短片画的是同一份几何, 第一帧一到就无缝接上。
        // 不挂背景: 纯黑由 MarkView 自己画, logo 也只由它画一份。窗口背景是纯黑(见 MainActivity)。
        addView(mark, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pending) start()
    }

    /** 桌面报"好了"之后再等一会儿才揭开: 底板和卡片刚上屏的那一下不算好光学, 停半秒更稳。 */
    fun armReveal(delayMs: Long) {
        if (started || pending) return
        postDelayed({ revealNow() }, delayMs)
    }

    /** 桌面首帧到了: 收起短片, 文字淡到透明, 标志放大, 黑幕收起。只做一次。 */
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
        // 遮罩同一帧就把黑幕和标志画上, 背景这块黑/静图在 tick 里让开, 不会闪白。
        mark.reveal()
        startAt = SystemClock.uptimeMillis()
        postOnAnimation(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val e = (SystemClock.uptimeMillis() - startAt).toFloat()
            mark.frame(e)
            if (e < TOTAL_MS) postOnAnimation(this) else finish()
        }
    }

    private fun finish() {
        (parent as? android.view.ViewGroup)?.removeView(this)
        // 黑幕已经收起、桌面完全可见, 但"这一帧"不抢解码器: 视频壁纸和直播预览都在等 revealed,
        // 之前它在动画结束的同一刻翻 true, 1080p 解码 + 拉流正好压在淡出尾巴上。让桌面先静
        // 800ms 再放这些重活开工。
        // 注意不能用这个 View 的 postDelayed: 上面已经把它从视图树摘了, detached View 的 post 进
        // 了永不回调的队列 —— 上一版就是这样 revealed 永远不翻, 壁纸停在第一帧、直播一直转圈。
        handler.postDelayed({
            BootVeil.revealed = true
            onRevealed?.invoke()
        }, 800L)
    }

    private companion object {
        const val TOTAL_MS = 2500f
    }
}

/** 黑幕 + 标志 + 开口。揭开时唯一还在动的东西。 */
private class MarkView(context: Context) : View(context) {
    private val ctx = context
    private val art = SplashArt(context)
    private val cut = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

    /** 短片起不来时, 这里就是那块静态图。 */
    private var showsStill = false
    private var started = false
    private var startAt = 0L

    /** 黑幕现在多不透明: 遮罩自己的背景按它淡出。 */
    var blackAlpha = 1f
        private set

    /** 黑幕(带洞)已经就绪: 这层就不再需要自己那块黑底了。 */
    val curtainOk: Boolean get() = (usePathCurtain && Build.VERSION.SDK_INT >= 26) || curtainReady
    private var inkAlpha = 1f
    private var wordAlpha = 1f
    private var scale = 1f

    /** 黑幕+洞, 一次烤好: 洞是真的透明, 不是抠在黑色上。 */
    private val veilPaint = Paint().apply { color = Color.BLACK }
    /** 26 以上用矢量路径挖洞(边缘干净); 老系统仍用烤好的位图。 */
    private val usePathCurtain = true
    private var curtain: Bitmap? = null
    private var curtainReady = false
    private val curtainPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var building = false
    private var pivotX = 0f
    private var pivotY = 0f
    private var endScale = 12f

    fun showStill() {
        showsStill = true
        invalidate()
    }

    fun reveal() {
        if (started) return
        started = true
        startAt = SystemClock.uptimeMillis()
        measure()
        buildMask()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measure()
        buildMask()
        invalidate()
    }

    /**
     * 放大要绕的点和要放多大: 绕图标中心放大, 放大到洞能盖住离它最远的那个屏幕角。第一帧就
     * 算好, 不等剪影(剪影在别的线程画, 慢一点也没关系 —— 颜色层挡着, 看不出洞里少了什么)。
     */
    private fun measure() {
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val box = art.box(w, h)
        // 上下排版之后, 镜头是从图标里穿过去的: 绕图标中心放大, 洞也就是这个图标。
        pivotX = box.centerX
        pivotY = box.centerY
        val far = maxOf(hypot(pivotX, pivotY), hypot(w - pivotX, pivotY), hypot(pivotX, h - pivotY), hypot(w - pivotX, h - pivotY))
        endScale = (far / (box.side / 2f) * 1.15f).coerceIn(4f, 24f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (!started) {
            // 停留阶段: 纯黑 + logo。logo 只在这一层画, 不依赖任何别的背景。
            canvas.drawColor(Color.BLACK)
            art.drawBrand(canvas, w, h, 1f)
            return
        }
        // 黑幕 = 一整块黑, 挖掉"标志形状"那块(clipOutPath)。路径每一帧都按当前放大倍数在设备
        // 分辨率下光栅化, 所以洞边缘始终干净 —— 之前是把一张半分辨率的黑幕位图放大十几倍, 边缘
        // 的 1 像素过渡被放大成几十像素的台阶, 就是"洞边锯齿"。
        // 也不再需要 saveLayer / 离屏: 一次实心填充 + 一条裁剪路径, 比整屏贴一张大位图还便宜。
        if (Build.VERSION.SDK_INT >= 26 && usePathCurtain) {
            if (blackAlpha > 0f) drawCurtain(canvas, w, h)
        } else {
            if (curtain == null) canvas.drawColor(Color.BLACK)
            curtain?.let { c ->
                canvas.save()
                canvas.scale(scale, scale, pivotX, pivotY)
                curtainPaint.alpha = (blackAlpha * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(c, null, RectF(0f, 0f, w, h), curtainPaint)
                canvas.restore()
            }
        }
        // 标志自己的颜色先退, 只留下形状的开口 —— 放大到几倍的字会糊成一片, 不该留到最后。
        if (inkAlpha > 0f || wordAlpha > 0f) {
            canvas.save()
            canvas.scale(scale, scale, pivotX, pivotY)
            art.drawBrand(canvas, w, h, inkAlpha, wordAlpha)
            canvas.restore()
        }
    }

    /**
     * 带洞的黑幕。洞 = 缩放后的图标(连续圆角)。黑幕拆成"屏幕减去洞的矩形"的四条直边带, 再加上
     * 四个圆角小块 —— 只有这几个角需要曲线, 每帧要算覆盖率的面积从整屏缩到半径大小的方块。
     * 之前整屏贴一条巨大曲线路径, 放大到最后覆盖率要按整屏算, 就是末段卡顿的来源。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun drawCurtain(canvas: Canvas, w: Float, h: Float) {
        val a = (blackAlpha * 255).toInt().coerceIn(0, 255)
        veilPaint.alpha = a
        veilPaint.isAntiAlias = false
        val rect = art.iconRect(w, h)
        val sx = pivotX + (rect.left - pivotX) * scale
        val sy = pivotY + (rect.top - pivotY) * scale
        val sw = rect.width() * scale
        // 四条直边: 屏幕减去洞矩形
        band(canvas, 0f, 0f, w, sy)
        band(canvas, 0f, sy + sw, w, h)
        band(canvas, 0f, sy, sx, sy + sw)
        band(canvas, sx + sw, sy, w, sy + sw)
        // 四个角: 在洞矩形四角的小方块里, 黑的地方是"方块减去图标的外形"
        val reach = rect.width() * CARD_CORNER_RATIO * (1f + CORNER_SMOOTHING) * scale * 1.1f
        val sil = art.silhouette(w, h)
        val moved = android.graphics.Path(sil).also {
            val m = android.graphics.Matrix()
            m.setScale(scale, scale, pivotX, pivotY)
            it.transform(m)
        }
        for (cx in floatArrayOf(sx, sx + sw - reach)) {
            for (cy in floatArrayOf(sy, sy + sw - reach)) {
                canvas.save()
                canvas.clipRect(cx, cy, cx + reach, cy + reach)
                canvas.clipOutPath(moved)
                veilPaint.alpha = a
                canvas.drawRect(cx, cy, cx + reach, cy + reach, veilPaint)
                canvas.restore()
            }
        }
    }

    /** 一块矩形黑带(可为空); 不做抗锯齿, 边缘就是屏幕像素。 */
    private fun band(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val left = maxOf(l, 0f)
        val top = maxOf(t, 0f)
        val right = minOf(r, width.toFloat())
        val bottom = minOf(b, height.toFloat())
        if (right <= left || bottom <= top) return
        canvas.drawRect(left, top, right, bottom, veilPaint)
    }

    /** 每帧的透明度与放大倍数, 由 [SplashVeil] 的时钟推。 */
    fun frame(e: Float) {
        wordAlpha = 1f - smooth((e / WORD_MS).coerceIn(0f, 1f))
        inkAlpha = 1f - smooth(((e - INK_DELAY_MS) / INK_MS).coerceIn(0f, 1f))
        val t = ((e - ZOOM_DELAY_MS) / ZOOM_MS).coerceIn(0f, 1f)
        // 起手先轻轻回拉一点, 再越放越快: 像镜头被人推着进去, 不是匀速放大。
        val pull = 1f - 0.05f * sin(Math.PI * min(t / 0.28f, 1f).toDouble()).toFloat()
        scale = 1f + (endScale - 1f) * (t * t * (t * 0.6f + 0.4f)) * pull
        blackAlpha = 1f - smooth(((e - VEIL_DELAY_MS) / VEIL_MS).coerceIn(0f, 1f))
        invalidate()
    }

    /**
     * 黑幕位图: 整块黑 + 标志形状的透明洞(半分辨率, 够用且省内存)。在别的线程烤, 烤好前一帧
     * 靠这层自己的黑底顶着, 所以看不到半成品。
     */
    private fun buildMask() {
        if (usePathCurtain && Build.VERSION.SDK_INT >= 26) return
        if (curtain != null || building || width <= 0 || height <= 0) return
        building = true
        val w = width.toFloat()
        val h = height.toFloat()
        val halfW = (width / 2).coerceAtLeast(1)
        val halfH = (height / 2).coerceAtLeast(1)
        // 换一份 Paint/Typeface 在别的线程画, 不和正在上屏的那几处抢同一个 Paint。
        Thread {
            val a = runCatching {
                // 标志剪影(只要 alpha), 半分辨率: 够用, 也省内存。
                val full = Bitmap.createBitmap(halfW, halfH, Bitmap.Config.ARGB_8888)
                Canvas(full).apply {
                    scale(0.5f, 0.5f)
                    SplashArt(ctx).drawBrand(this, w, h, 1f)
                }
                val holeMask = full.extractAlpha().also { full.recycle() }
                // 黑幕: 整块不透明, 再把标志形状擦成真透明 —— 洞底下就是桌面的壁纸和卡片。
                val c = Bitmap.createBitmap(halfW, halfH, Bitmap.Config.ARGB_8888)
                Canvas(c).apply {
                    drawColor(Color.BLACK)
                    drawBitmap(holeMask, null, RectF(0f, 0f, halfW.toFloat(), halfH.toFloat()), cut)
                }
                holeMask.recycle()
                c
            }.getOrNull()
            post {
                curtain = a
                curtainReady = a != null
                // 这层(SplashVeil)的黑底由 tick 每帧按 curtainOk 透明, 这里不用管。
                building = false
                invalidate()
            }
        }.start()
    }

    private companion object {
        /** 文字先淡到透明(0.7 秒)。 */
        const val WORD_MS = 500f
        /** 标志的颜色要在放大起步之前退干净: 之前 ink 拖到 1 秒才没, 放大 0.5 秒就开始了,
         * 一个越放越大的实色渐变方块压在洞口上 —— 就是"LOGO 挡住过渡"的原因。现在 0→0.35 秒退完。 */
        const val INK_DELAY_MS = 0f
        const val INK_MS = 300f
        /** 放大: 0.35 秒起步, 走到 2.25 秒。 */
        const val ZOOM_DELAY_MS = 500f
        const val ZOOM_MS = 1900f
        /** 黑幕: 1.9 秒才收, 2.5 秒全开。 */
        const val VEIL_DELAY_MS = 1900f
        const val VEIL_MS = 600f
    }
}

/** 两端都放慢的 smoothstep: 起手不突兀, 落点不急刹。 */
private fun smooth(p: Float): Float = p * p * (3f - 2f * p)

/**
 * 遮罩与桌面之间的信号: HomeScreen 在首帧真的画完之后 [arm], 遮罩收到就把自己揭开。
 * 本进程是否已经完整显示过一次桌面也记在这里: 屏保回来、换分辨率重建不用再盖一次开屏。
 */
object BootVeil {
    private var veil: SplashVeil? = null
    private var armed = false
    private var signaled = false

    /**
     * 冷启动遮罩已经揭开(冷启动动画跑完)。VideoWallpaper / ChannelFocusPreview 这种要抢
     * 硬件解码器的东西, 在它变 true 之前不起播 —— 盒子上同时起两个解码器再叠过渡动画就是卡的来源。
     * 非冷启动(从应用/屏保回来)由 MainActivity 直接置 true。
     */
    var revealed by mutableStateOf(false)

    /**
     * 开机时 Activity 会先后建两次(先解锁前那次只铺一张图, 解锁后再真正启动), 所以不能靠
     * savedInstanceState 判断要不要盖开屏 —— 看这个标志: 还没显示过桌面就盖。
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
