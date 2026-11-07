package app.orchard.tv.system

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import androidx.media3.exoplayer.ExoPlayer

/**
 * Percent (0..100) software volume for the live TV: the box's system stream only has ~21 steps
 * (Dangbei/Amlogic), far too coarse. We lock STREAM_MUSIC to its maximum and scale every live
 * ExoPlayer with a float gain instead.
 *
 * - System volume stays pinned at max; whatever step the firmware applies when a volume key
 *   arrives is undone immediately after.
 * - ExoPlayer gain uses a perceptual x^2 curve: the low end stays usable, the top end loud.
 */
object SoftwareVolume {
    private const val PREFS = "software_volume"
    private const val KEY = "pct"
    const val MAX = 100

    @Volatile var pct: Int = 80
        private set

    private lateinit var prefs: SharedPreferences
    private val listeners = mutableListOf<(Float) -> Unit>()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pct = prefs.getInt(KEY, 80)
        lockSystem(context)
    }

    /** Observe float gain changes; returns an unsubscribe token. */
    fun onChanged(l: (Float) -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }

    /** Pin the music stream to its maximum so the software gain is the only thing that scales. */
    fun lockSystem(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching {
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != max)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        }
    }

    /** Returning from a third-party app: the user may have moved the coarse system steps there.
     *  Map the current system step back onto 0..100 (e.g. 21-step box → step*5) so the software
     *  gain picks up where they left off, instead of snapping back to whatever it was before. */
    fun absorbSystemLevel(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        runCatching {
            val step = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val next = Math.round(step * 100f / max)
            if (next in 0..MAX && next != pct) set(next)
        }
    }

    /** Leave Aerial for another app: map 0..100 pct onto the system's coarse steps
     *  (e.g. 21-step box → step = pct/5). We can't keep fine control outside this process,
     *  so the best we can do is hand a roughly equivalent loudness to the next app. */
    fun applyCoarseSystem(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching {
            val step = Math.round(pct * max / 100f).coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
        }
    }

    fun change(delta: Int) = set(pct + delta)

    @Synchronized
    fun set(v: Int) {
        pct = v.coerceIn(0, MAX)
        if (this::prefs.isInitialized) prefs.edit().putInt(KEY, pct).apply()
        val gain = floatVolume()
        listeners.toList().forEach { runCatching { it(gain) } }
    }

    /** 0..1 player gain; x^2 makes the first 30% usable instead of near-silent. */
    fun floatVolume(): Float {
        val x = (pct / 100f).coerceIn(0f, 1f)
        return x * x
    }

    fun apply(player: ExoPlayer) {
        player.volume = floatVolume()
    }
}
