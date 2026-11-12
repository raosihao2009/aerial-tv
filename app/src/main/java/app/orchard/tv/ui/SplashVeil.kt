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
private const val ICON_CORNER_RATIO = 0.24f

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
        // 横排: 图标在左、字在右, 整组在屏幕正中间; 两者垂直居中对齐。
        iconSide = word.textSize * 1.15f
        val gap = word.textSize * 0.42f
        val total = iconSide + gap + textW
        left = w / 2f - total / 2f
        textLeft = left + iconSide + gap
        top = h / 2f - iconSide / 2f
        val fm = word.fontMetrics
        // 让文字的大写中线对齐图标中线: 基线 = 中线 + (ascent + descent) / 2 的相反数。
        baseline = h / 2f - (fm.ascent + fm.descent) / 2f
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

    private var bgShader: android.graphics.Shader? = null
    private var bgKey = ""
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 开屏的底色: 斜向的深色渐变(墨黑蓝 → 靛青 → 暗紫), 中间压一层冷色辉光, 四角再压一点
     * 暗角。不用纯黑 —— 纯黑看着像"没画东西", 渐变才像一块有光的面。
     */
    fun drawBackdrop(canvas: Canvas, w: Float, h: Float) {
        val key = "$w/$h"
        if (bgShader == null || bgKey != key) {
            bgKey = key
            // 底色直接取图标那套配色(青绿 → 蓝 → 靛紫), 压到很低的亮度当"背光面":
            // 这样开屏和图标是同一个色彩家族, 不会一块蓝一块紫地打架。
            // 底色压到接近黑: 真正的高级感来自"暗", 不是来自一块发亮的彩色。
            bgShader = android.graphics.LinearGradient(
                0f, 0f, w, h,
                intArrayOf(
                    Color.rgb(38, 12, 16),    // 左下: 暖红的暗面
                    Color.rgb(46, 14, 34),    // 转紫
                    Color.rgb(38, 16, 52),    // 深紫
                    Color.rgb(22, 16, 60),    // 右: 蓝紫
                ),
                floatArrayOf(0f, 0.4f, 0.75f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        bgPaint.shader = bgShader
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        // 中心偏上的冷色辉光: 让标志那一块"有光", 渐变不至于平。
        // 两块光: 标志左下一点暖红的光(像被灯从下面打亮), 右上一点冷紫的辉光, 中间偏亮、
        // 四角压暗 —— 就是那种"通透的高级渐变"。
        val warm = android.graphics.RadialGradient(
            w * 0.30f, h * 0.66f, maxOf(w, h) * 0.40f,
            intArrayOf(Color.argb(110, 210, 74, 52), Color.argb(40, 170, 52, 90), Color.argb(0, 30, 12, 20)),
            floatArrayOf(0f, 0.55f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        bgPaint.shader = warm
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val glow = android.graphics.RadialGradient(
            w * 0.72f, h * 0.34f, maxOf(w, h) * 0.45f,
            intArrayOf(Color.argb(80, 108, 96, 226), Color.argb(34, 78, 62, 170), Color.argb(0, 12, 10, 20)),
            floatArrayOf(0f, 0.55f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        bgPaint.shader = glow
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        // 暗角: 四边压暗, 中间那一块更亮, 像被打了光。
        val vignette = android.graphics.RadialGradient(
            w / 2f, h / 2f, maxOf(w, h) * 0.78f,
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(120, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        bgPaint.shader = vignette
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        bgPaint.shader = null
    }

    /** 屏幕下方那行小字: 桌面还在准备。图标/字下面偏下, 水平居中。 */
    fun drawHint(canvas: Canvas, w: Float, h: Float, text: String) {
        relayout(w, h)
        val d = density
        head.textSize = 18f * d
        head.alpha = 255
        val fm = head.fontMetrics
        canvas.drawText(text, w / 2f - head.measureText(text) / 2f, h - 38f * d - fm.descent, head)
    }

    private val density = context.resources.displayMetrics.density

    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = runCatching { ResourcesCompat.getFont(context, R.font.inter) }.getOrNull()
            ?: Typeface.create("sans-serif", Typeface.NORMAL)
        letterSpacing = 0.06f
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
    private val d = context.resources.displayMetrics.density
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** 0 时整张都不画: 遮罩盖上来之后每帧再画一遍这张字, 就是白花的钱。 */
    private var alpha = 255

    override fun draw(canvas: Canvas) {
        if (alpha <= 0) return
        // 窗口背景在整棵视图树后面, 它画的标志不可能压住遮罩层 —— 它只负责"内容视图还没建好"的
        // 那几帧(冷启动开机最先看到的那一下)。过渡动画的标志仍只由 MarkView 一份负责。
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        art.drawBackdrop(canvas, w, h)
        art.drawBrand(canvas, w, h, 1f)
        art.drawHint(canvas, w, h, "桌面正在准备中")
        BootVeil.debugNote?.let {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 226, 92)
                textSize = 16f * d
            }
            canvas.drawText(it, 24f * d, 48f * d, p)
        }
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
 * 开屏遮罩: 一块黑 + 横排的图标与字 + 一行"桌面正在准备中"。影片放完(或到硬期限)就直接
 * 让开桌面, 不做任何转场动画。
 *
 * 这里刻意不依赖任何"过渡"机制: 没有挖洞、没有后台抠帧、没有位图 alpha、没有缩放动画。
 * 之前那些做法在盒子上反复出现"洞后面是黑"、"淡出的是还没画的黑桌面", 于是看起来要么卡住
 * 要么没有过渡。现在只剩"画"和"撤"两个动作。
 */
class SplashVeil(context: Context, withVideo: Boolean = true) : FrameLayout(context) {
    private val ctx = context
    private val art = SplashArt(context)
    private val handler = Handler(Looper.getMainLooper())
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 185, 195)
        textSize = 30f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    /** 有开机影片就放影片(苹果 TV 那段), 没有就只停在这张图上。 */
    val usesVideo: Boolean = withVideo && hasBootVideo(context)

    var onRevealed: (() -> Unit)? = null

    private var player: MediaPlayer? = null
    private var playerSurface: Surface? = null
    private var videoView: TextureView? = null
    private var videoFrameSeen = false
    private var videoEnded = false
    /** 影片起不来(编码不支持/文件坏了/迟迟不出帧)。 */
    private var broken = false
    private var removingVideo = false
    private var hardCapArmed = false

    private var finishing = false
    private var removed = false

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (usesVideo && videoView == null && !broken && !finishing) attachVideo()
        // 全局硬期限: 不管影片准没准备起来, 到点就撤。
        if (!hardCapArmed) {
            hardCapArmed = true
            handler.postDelayed({ if (!finished()) finishSoon(force = true) }, HARD_CAP_MS)
        }
        if (!usesVideo) {
            // 没有影片就一直停在这张图上, 由桌面报"好了"/兜底来撤。
        }
    }

    // ---------------------------------------------------------------- 影片

    private fun attachVideo() {
        val tv = TextureView(ctx)
        // 不透明的话, 影片还没出帧时它就是一块死黑, 把下面我们画的标志盖掉。
        tv.isOpaque = false
        videoView = tv
        addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startPlayer(tv)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fitVideo(tv)
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                if (!videoFrameSeen) {
                    videoFrameSeen = true
                    fitVideo(tv)
                }
            }
        }
        // 2.5 秒还没出第一帧就别耗着: 当它坏了, 直接露出桌面。
        handler.postDelayed({
            if (!videoFrameSeen && !videoEnded && !finishing) {
                broken = true
                releasePlayer()
                videoView?.let { removeView(it) }
                videoView = null
                finishSoon(force = true)
            }
        }, VIDEO_FIRST_FRAME_MS)
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
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) videoFrameSeen = true
                false
            }
            mp.setVolume(0f, 0f)
            mp.isLooping = false
            mp.setOnPreparedListener { p ->
                p.start()
                // 影片尾巴前 1.2 秒先让壁纸/预览开工: 撤遮罩时后面已经是真画面。
                val tail = (p.duration - 1200).coerceAtLeast(0)
                handler.postDelayed({ BootVeil.revealed = true }, tail.toLong())
                fitVideo(tv)
            }
            mp.setOnCompletionListener { endVideo() }
            // 硬期限: completion 回调在某些盒子上不回来, 到点自己收。
            val hold = (runCatching { mp.duration }.getOrDefault(5000) + 400).coerceIn(1500, 8000)
            handler.postDelayed({ if (!videoEnded && !finished()) endVideo() }, hold.toLong())
            mp.prepareAsync()
        }.onFailure {
            broken = true
            videoView = null
            finishSoon(force = true)
        }
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

    private fun endVideo() {
        if (videoEnded || removingVideo) return
        removingVideo = true
        videoEnded = true
        player?.let { p ->
            player = null
            runCatching { p.setSurface(null) }
            Thread { runCatching { p.release() } }.start()
        }
        runCatching { playerSurface?.release() }
        playerSurface = null
        // 影片停的那一帧就停在那儿, 直接撤掉遮罩。
        videoView?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        videoView = null
        removingVideo = false
        finishSoon(force = true)
    }

    // ---------------------------------------------------------------- 撤

    private fun finished(): Boolean = removed || finishing

    /** 桌面报"好了"(或影片放完): 放开桌面, 留一帧时间, 撤。 */
    fun revealNow() {
        if (finished()) return
        finishSoon()
    }

    /** 桌面报"好了"之后再等一会儿才撤: 底板和卡片刚上屏的那一下不算好光学。 */
    fun armReveal(delayMs: Long) {
        handler.postDelayed({ revealNow() }, delayMs)
    }

    fun forceFinish() {
        finishNow()
    }

    private fun finishSoon(force: Boolean = false) {
        if (finishing || removed) return
        // 有影片正在放的时候, 桌面报"好了"只是记一笔: 影片该放完再撤, 不然看半段就被切了。
        if (!force && usesVideo && !videoEnded && !broken) return
        finishing = true
        // 先把桌面放开, 让它在我们还盖着的时候画出来, 撤的时候才不会先看到一块黑。
        BootVeil.revealed = true
        handler.postDelayed({ finishNow() }, LEAD_MS)
    }

    private fun finishNow() {
        if (removed) return
        removed = true
        finishing = true
        runCatching { releasePlayer() }
        videoView = null
        BootVeil.revealed = true
        (parent as? ViewGroup)?.removeView(this)
        onRevealed?.invoke()
    }

    // ---------------------------------------------------------------- 画

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // 影片真的出帧了: 画面在 TextureView 里, 这里让开。
        if (usesVideo && !broken && videoFrameSeen && videoView != null) return
        art.drawBackdrop(canvas, w, h)
        art.drawBrand(canvas, w, h, 1f)
        drawHint(canvas, w, h)
        BootVeil.debugNote?.let {
            notePaint.textSize = 16f * resources.displayMetrics.density
            canvas.drawText(it, 24f * resources.displayMetrics.density, 48f * resources.displayMetrics.density, notePaint)
        }
    }

    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 226, 92)
        textSize = 24f
    }

    /**
     * "桌面正在准备中": 放在桌面的轻提示(直播切换/回退那种玻璃胶囊)同一高度 —— 底部 28dp 再加
     * 10dp 内边距, 水平居中、14sp。这样开屏撤掉时那行字和桌面的提示是同一条线, 不会有跳动。
     */
    private fun drawHint(canvas: Canvas, w: Float, h: Float) {
        val text = HINT
        val d = resources.displayMetrics.density
        hint.textSize = 18f * d
        val fm = hint.fontMetrics
        val baseline = h - (28f + 10f) * d - fm.descent
        canvas.drawText(text, w / 2f - hint.measureText(text) / 2f, baseline, hint)
    }

    companion object {
        /** 开机影片放在 assets 里: 有它就放影片, 没有就只停在静态图上。 */
        const val VIDEO_ASSET = "boot.mp4"

        /** 桌面下方那行小字。 */
        private const val HINT = "桌面正在准备中"

        /** 影片迟迟不出第一帧就认为它坏了。 */
        private const val VIDEO_FIRST_FRAME_MS = 2500L

        /** 从挂上遮罩算起, 最多停这么久。 */
        private const val HARD_CAP_MS = 10_000L

        /** 放开桌面到撤掉遮罩之间留的时间: 给壁纸/卡片画第一帧。 */
        private const val LEAD_MS = 260L

        fun hasBootVideo(context: Context): Boolean =
            runCatching { context.assets.list("")?.contains(VIDEO_ASSET) == true }.getOrDefault(false)
    }
}

object BootVeil {
    /** 临时调试角标: 首列是直播/非直播、走到哪个分支(打在开屏左上角)。 */
    var debugNote: String? = null

    /** 每挂一次遮罩 +1: HomeScreen 里的揭罩轮询按它重启, 热启动也会重新跑一遍。 */
    var generation by mutableStateOf(0)

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
        generation++
    }

    fun hasArmed(): Boolean = armed

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

    /** 只放开桌面内容(壁纸/直播预览开工), 遮罩还盖着 —— 直播预览要出帧才能判断撤的时机。 */
    fun revealContent() {
        if (!revealed) revealed = true
    }

    /** 兜底: 桌面一直没报"好了"也把黑幕揭开。 */
    fun reveal() {
        if (signaled) return
        signaled = true
        veil?.revealNow()
    }
}
