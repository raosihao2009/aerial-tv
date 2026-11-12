package app.orchard.tv.ui

import android.content.Context
import android.media.MediaPlayer
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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 开屏的静态图: 纯黑, 中间横排着 Aerial 的图标和白色字。窗口背景([SplashBackground])和
 * 遮罩([MarkView])画的是同一份几何, 所以从窗口一可见到揭开, 中间不会有接缝。
 *
 * 字用包内的 Inter(和桌面同一套字体), 图标用应用图标, 圆角取卡片圆角的半径: 卡片画在设计稿 960
 * 宽的画布上、圆角 12dp, 也就是屏宽的 1/80, 这里按同一个比例切。
 */
/** 卡片圆角 12dp / 设计画布 960 = 1/80: 卡片按这个比例切。 */
private const val CARD_CORNER_RATIO = 1f / 80f

/** 开屏图标自己的圆角, 按图标边长取: 比卡片圆角圆一档, 独看时不显得"方"。 */
private const val ICON_CORNER_RATIO = 0.22f

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
            iconSide * ICON_CORNER_RATIO,
        ).asAndroidPath()
        silhouette = path
        silhouetteKey = key
        return path
    }

    private val textRectF = RectF()
    /** "Aerial" 在屏幕上的矩形(图标下面那一块)。 */
    internal fun textRect(w: Float, h: Float): RectF {
        relayout(w, h)
        val fm = word.fontMetrics
        textRectF.set(textLeft, baseline + fm.ascent, textLeft + textW, baseline + fm.descent)
        return textRectF
    }

    /** 图标 + 文字合在一起的外形: 字退场时桌面也从字形里透出来, 和图标一样"变透明", 不是变黑。 */
    internal fun unionSilhouette(w: Float, h: Float): android.graphics.Path {
        relayout(w, h)
        val key = "u$w/$h"
        if (union != null && unionKey == key) return union!!
        val path = android.graphics.Path(silhouette(w, h))
        val fm = word.fontMetrics
        word.getTextPath(BRAND, 0, BRAND.length, textLeft, baseline, path)
        union = path
        unionKey = key
        return path
    }

    private var union: android.graphics.Path? = null
    private var unionKey = ""

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
        // ascent 是负值, 基线在"图标底 + 间距"再抬高 -ascent; 之前写成 +ascent, 字就压到图标上了。
        baseline = top + iconSide + gap - fm.ascent
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
        val radius = iconSide * ICON_CORNER_RATIO
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
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
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
/**
 * 开屏遮罩: 一块黑 + 标志(或一段开机影片), 最后"镜头推进"地把桌面让出来。
 *
 * 这里刻意只用一条时间线, 全部在主线程上、只靠 alpha 和 scale 两个量:
 * 影片放完 → 画面往你脸上推(scale 1→3) 同时这一层整体淡出 → 桌面在下面自然露出来;
 * 没有影片 → 同一个动作, 只是推的是自己画的那个标志。
 *
 * 以前那套"黑幕上挖一个标志形状的洞 + 后台线程从影片帧里抠标志"在盒子上去过一次:
 * 洞后面是还没起播的 SurfaceView(黑), 抠帧失败/影片不出帧时整条链路就停在黑屏上。
 * alpha + scale 不会"卡住", 最坏也只是淡出一块静止的黑幕。
 */
class SplashVeil(context: Context, withVideo: Boolean = true) : FrameLayout(context) {
    private val ctx = context
    private val art = SplashArt(context)
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val handler = Handler(Looper.getMainLooper())

    /** 有开机影片就放影片(苹果 TV 那段), 没有就走自己画的标志。 */
    val usesVideo: Boolean = withVideo && hasBootVideo(context)

    var onRevealed: (() -> Unit)? = null

    private var player: MediaPlayer? = null
    /** setSurface 传出去的 Surface 必须留个引用: 只传一个临时对象, 它可能被回收, 之后一帧都不再来。 */
    private var playerSurface: Surface? = null
    private var videoView: TextureView? = null
    private var videoFrameSeen = false
    /** 影片停下来的那一帧(取成位图自己画: TextureView 自己的 alpha 在盒子上不可靠)。 */
    private var still: Bitmap? = null
    /** 我们自己画的标志 + 文字, 烤成一张透明底位图: 透明用 paint.alpha, 绕开绘制里任何 alpha 损耗。 */
    private var brand: Bitmap? = null
    private var videoEnded = false
    /** 影片起不来(编码不支持/文件坏了/迟迟不出帧)。 */
    private var broken = false
    private var removingVideo = false
    private var hardCapArmed = false

    private var started = false
    private var transitioning = false
    private var removed = false

    private var pivotX = 0f
    private var pivotY = 0f

    /** 标志和文字的不透明度: 推进时先把自己淡成透明, 桌面从字缝里透出来。 */
    private var brandFade = 1f
    /** 黑幕的不透明度: 最后才收, 收完桌面就全露出来。 */
    private var blackFade = 1f
    /** 画面(影片或标志)的缩放: 1 = 原样, 越大越"贴脸"。 */
    private var zoom = 1f

    init {
        setWillNotDraw(false)
        if (usesVideo) {
            // 影片层等量出尺寸再挂(见 onSizeChanged), 在它出第一帧之前就是纯黑。
        }
    }

    // ---------------------------------------------------------------- 影片

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measure()
        bakeBrand()
        // 全局硬期限: 不管影片准没准备起来, 7.5 秒必须开始收尾。
        if (!hardCapArmed) {
            hardCapArmed = true
            handler.postDelayed({ if (!transitioning && !removed) endVideo() }, HARD_CAP_MS)
        }
        if (usesVideo && videoView == null && !broken && !transitioning) attachVideo()
        // 没有影片: 量好就自己开始走时间线, 不依赖任何"桌面画好了"的信号。
        if (!usesVideo && !transitioning && width > 0 && height > 0) maybeStartIdle()
    }

    private fun bakeBrand() {
        if (brand != null || width <= 0 || height <= 0) return
        brand = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { b ->
                art.drawBrand(Canvas(b), width.toFloat(), height.toFloat(), 1f)
            }
        }.getOrNull()
    }

    private fun measure() {
        if (width <= 0 || height <= 0) return
        val box = art.box(width.toFloat(), height.toFloat())
        pivotX = box.centerX
        pivotY = box.centerY
        videoView?.pivotX = width / 2f
        videoView?.pivotY = height / 2f
    }

    private fun attachVideo() {
        val tv = TextureView(ctx)
        // 不透明的话, 影片还没出帧时它就是一块死黑, 把下面我们自己画的标志盖掉(上一版冷启动
        // "logo 没了变黑屏"就是这么来的)。
        tv.isOpaque = false
        videoView = tv
        tv.pivotX = width / 2f
        tv.pivotY = height / 2f
        addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startPlayer(tv)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fitVideo(tv)
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                if (!videoFrameSeen) markFrameSeen()
            }
        }
        // 2.5 秒还没出第一帧就别耗着: 当它坏了, 走标志那套。
        handler.postDelayed({
            if (!videoFrameSeen && !videoEnded && !transitioning) {
                broken = true
                releasePlayer()
                videoView?.let { removeView(it) }
                videoView = null
                maybeStartIdle()
            }
        }, VIDEO_FIRST_FRAME_MS)
    }

    private fun markFrameSeen() {
        if (videoFrameSeen) return
        videoFrameSeen = true
        videoView?.let { fitVideo(it) }
        invalidate()
    }

    private fun startPlayer(tv: TextureView) {
        val st = tv.surfaceTexture ?: return
        releasePlayer()
        val mp = MediaPlayer()
        player = mp
        runCatching {
            st.setDefaultBufferSize(1920, 1080)
            ctx.assets.openFd(VIDEO_ASSET).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            val sf = Surface(st)
            playerSurface = sf
            mp.setSurface(sf)
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) markFrameSeen()
                false
            }
            mp.setVolume(0f, 0f)
            mp.isLooping = false
            mp.setOnPreparedListener { p ->
                p.start()
                // 影片尾巴前 1.2 秒先让壁纸/预览开工: 等镜头推进时, 后面已经是真画面而不是黑。
                val tail = (p.duration - 1200).coerceAtLeast(0)
                handler.postDelayed({ BootVeil.revealed = true }, tail.toLong())
                measure()
                fitVideo(tv)
            }
            mp.setOnCompletionListener { endVideo() }
            // 硬期限: 影片的 completion 回调在某些盒子上不回来, 到点自己收尾, 绝不干等。
            val hold = (runCatching { mp.duration }.getOrDefault(5000) + 400).coerceIn(1500, 8000)
            handler.postDelayed({ if (!videoEnded && !removed) endVideo() }, hold.toLong())
            mp.setOnErrorListener { _, _, _ ->
                broken = true
                runCatching { releasePlayer() }
                videoView?.let { removeView(it) }
                videoView = null
                maybeStartIdle()
                true
            }
            mp.prepareAsync()
        }.onFailure {
            broken = true
            videoView = null
            maybeStartIdle()
        }
    }

    /**
     * 影片放完(或到硬期限): 把停在屏幕上的最后一帧取成位图, 撤掉影片层, 之后就用这张位图
     * 走"贴脸 + 淡成透明"。不再依赖 TextureView 自己的 alpha, 也不依赖影片层的任何回调。
     */
    private fun endVideo() {
        if (videoEnded || removingVideo) return
        removingVideo = true
        videoEnded = true
        val tv = videoView
        if (tv != null && videoFrameSeen && width > 0 && height > 0) {
            still = runCatching { tv.getBitmap(width, height) }.getOrNull()
        }
        player?.let { p ->
            player = null
            runCatching { p.setSurface(null) }
            // release 可能阻塞几十毫秒: 放后台线程, 屏幕上这时是 still 那张图。
            Thread { runCatching { p.release() } }.start()
        }
        runCatching { playerSurface?.release() }
        playerSurface = null
        videoView?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
        }
        videoView = null
        removingVideo = false
        beginTransition()
    }

    /** 影片按"铺满屏幕、居中裁切"缩放, 不是拉伸。 */
    private fun fitVideo(tv: TextureView) {
        val p = player ?: return
        val vw = p.videoWidth.takeIf { it > 0 } ?: return
        val vh = p.videoHeight
        if (vh <= 0) return
        val s = maxOf(width.toFloat() / vw, height.toFloat() / vh)
        val m = android.graphics.Matrix()
        m.setScale(s, s, vw / 2f, vh / 2f)
        m.postTranslate((width - vw * s) / 2f, (height - vh * s) / 2f)
        tv.setTransform(m)
    }

    private fun releasePlayer() {
        val p = player
        player = null
        if (p != null) runCatching {
            p.setSurface(null)
            p.reset()
            p.release()
        }
        runCatching { playerSurface?.release() }
        playerSurface = null
    }

    // ---------------------------------------------------------------- 时间线

    /** 桌面报"好了": 有影片就等它放完, 没有就马上推进。 */
    fun revealNow() {
        if (removed) return
        if (usesVideo && !broken && !videoEnded) return
        beginTransition()
    }

    /** 桌面报"好了"之后再等一会儿才推进: 底板和卡片刚上屏的那一下不算好光学, 停半秒更稳。 */
    fun armReveal(delayMs: Long) {
        handler.postDelayed({ revealNow() }, delayMs)
    }

    fun forceFinish() {
        if (removed) return
        removed = true
        still?.recycle()
        still = null
        brand?.recycle()
        brand = null
        runCatching { releasePlayer() }
        videoView = null
        BootVeil.revealed = true
        (parent as? ViewGroup)?.removeView(this)
        onRevealed?.invoke()
    }

    /** 没有影片时: 静静地停一小会儿再推进, 免得一闪而过。 */
    private fun maybeStartIdle() {
        if (started || removed) return
        started = true
        handler.postDelayed({ if (!removed && !transitioning) beginTransition() }, IDLE_MS)
    }

    private fun beginTransition() {
        if (transitioning || removed) return
        transitioning = true
        started = true
        val at = SystemClock.uptimeMillis()
        val frame = object : Runnable {
            override fun run() {
                if (removed) return
                val t = ((SystemClock.uptimeMillis() - at).toFloat() / ZOOM_MS).coerceIn(0f, 1f)
                val e = t * t * (3f - 2f * t)          // smoothstep: 起手不突兀, 落点不急刹
                zoom = 1f + 2.2f * e                  // 1 → 3.2: 标志/画面往你脸上推
                // 标志和文字先把自己淡成透明的(0 → 55%), 黑幕留到最后 45% 才收 —— 这样看着是
                // "字透掉了、然后黑幕也开了", 而不是整块画面一起变暗。
                // 先把黑幕开掉(桌面露出来), 再让放大中的图标和文字在桌面上淡成透明的 ——
                // 这样是"透过标志看见桌面", 而不是"标志消失在一片黑里"(后者看着就像没透明)。
                blackFade = 1f - smooth01(0f, 0.35f, t)
                brandFade = 1f - smooth01(0.25f, 1f, t)
                videoView?.let { v ->
                    v.scaleX = zoom
                    v.scaleY = zoom
                    v.alpha = 1f - smooth01(0.1f, 0.85f, t)
                }
                invalidate()
                if (t < 1f) handler.postDelayed(this, 8L) else done()
            }
        }
        handler.postDelayed(frame, 8L)
    }

    private fun done() {
        if (removed) return
        removed = true
        still?.recycle()
        still = null
        brand?.recycle()
        brand = null
        runCatching { releasePlayer() }
        videoView = null
        BootVeil.revealed = true
        (parent as? ViewGroup)?.removeView(this)
        onRevealed?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // 影片出第一帧之前不空着: 先画自己的标志(视频那边还没画面), 出帧之后自然盖过去。
        val videoShown = usesVideo && !broken && videoFrameSeen && videoView != null && still == null
        if (videoShown) return
        if (blackFade > 0f) {
            canvas.drawColor(Color.argb((blackFade * 255).toInt().coerceIn(0, 255), 0, 0, 0))
        }
        val frameBitmap = still
        if (frameBitmap != null) {
            // 影片的最后一帧: 位图按 zoom 放大、按 brandFade 淡成透明 —— 和品牌那条走同一条曲线。
            if (brandFade > 0f) {
                framePaint.alpha = (brandFade * 255).toInt().coerceIn(0, 255)
                canvas.save()
                canvas.scale(zoom, zoom, pivotX, pivotY)
                canvas.drawBitmap(frameBitmap, null, RectF(0f, 0f, w, h), framePaint)
                canvas.restore()
            }
        } else {
            val b = brand
            if (b != null && brandFade > 0f) {
                framePaint.alpha = (brandFade * 255).toInt().coerceIn(0, 255)
                canvas.save()
                canvas.scale(zoom, zoom, pivotX, pivotY)
                canvas.drawBitmap(b, 0f, 0f, framePaint)
                canvas.restore()
            }
        }
    }

    private fun smooth01(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        /** 开机影片放在 assets 里: 有它就放影片, 没有就走自己画的标志。 */
        const val VIDEO_ASSET = "boot.mp4"

        /** 从挂上遮罩算起, 最多等这么久就必须开始收尾。 */
        private const val HARD_CAP_MS = 7500L

        /** 影片迟迟不出第一帧就认为它坏了。 */
        private const val VIDEO_FIRST_FRAME_MS = 2500L

        /** 没有影片时停留多久再推进。 */
        private const val IDLE_MS = 900L

        /** 推进(缩放 + 淡出)的时长。 */
        private const val ZOOM_MS = 720f

        fun hasBootVideo(context: Context): Boolean =
            runCatching { context.assets.list("")?.contains(VIDEO_ASSET) == true }.getOrDefault(false)
    }
}


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
        // 没有影片时: 先把壁纸/预览放起来, 遮罩还盖着(看不见), 这 1.5 秒足够解码器出第一帧。
        // 否则过渡时"洞"里透出来的是一块还没起播的黑 SurfaceView —— 图标淡掉像在黑底上放大。
        // 有影片时不能提前起: 两个解码器一起跑, 影片会卡; 影片自己在尾巴前 1.2 秒才放它们开工。
        if (veil?.usesVideo != true) revealed = true
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
