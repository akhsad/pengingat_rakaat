package id.pribadi.rakaat

import android.graphics.PointF
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Mengukur kemiringan badan (derajat dari tegak) dari hasil deteksi pose. */
object PostureMath {
    private const val MIN_LIKELIHOOD = 0.5f

    private fun avg(vararg l: PoseLandmark?): PointF? {
        val ok = l.filterNotNull().filter { it.inFrameLikelihood >= MIN_LIKELIHOOD }
        if (ok.isEmpty()) return null
        return PointF(
            ok.map { it.position.x }.average().toFloat(),
            ok.map { it.position.y }.average().toFloat()
        )
    }

    /** Hasil: (sumber sumbu, kemiringan). Sumber 0 = pinggul ke bahu, 1 = bahu ke hidung. Kemiringan null jika tubuh tidak terbaca. */
    fun measure(pose: Pose): Pair<Int, Float?> {
        val shoulder = avg(
            pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER),
            pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        )
        val hip = avg(
            pose.getPoseLandmark(PoseLandmark.LEFT_HIP),
            pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        )
        val nose = avg(pose.getPoseLandmark(PoseLandmark.NOSE))

        val src: Int
        val top: PointF
        val bottom: PointF
        if (shoulder != null && hip != null) {
            src = 0; top = shoulder; bottom = hip
        } else if (shoulder != null && nose != null) {
            src = 1; top = nose; bottom = shoulder
        } else {
            return Pair(-1, null)
        }
        val vx = top.x - bottom.x
        val vy = top.y - bottom.y
        if (hypot(vx, vy) < 20f) return Pair(src, null)
        val deg = Math.toDegrees(atan2(abs(vx), -vy).toDouble()).toFloat()
        return Pair(src, deg)
    }
}

/**
 * Mesin hitung rakaat.
 * Satu rakaat = 3 kali membungkuk (ruku, sujud, sujud). Duduk di antara dua sujud
 * dan tahiyat tidak dihitung. Jika urutan meleset, berdiri/duduk tegak >= UPRIGHT_SNAP_MS
 * dipakai untuk menyelaraskan ulang.
 * Seluruh nilai ambang bisa disetel di companion object setelah uji di HP.
 */
class RakaatEngine(private val listener: Listener) {

    interface Listener {
        fun onReady()
        fun onNotReady()
        fun onRakaat(total: Int)
    }

    companion object {
        const val CALIB_MS = 2000L        // lama berdiri diam untuk menentukan posisi dasar
        const val WARN_MS = 15000L        // peringatan jika belum terdeteksi
        const val STABLE_DEG = 10f        // toleransi kestabilan saat kalibrasi
        const val ON_DEG = 40f            // mulai dianggap membungkuk
        const val OFF_DEG = 22f           // dianggap tegak kembali
        const val MIN_EPISODE_MS = 500L   // bungkukan lebih singkat diabaikan
        const val LOST_MS = 600L          // tubuh hilang dari gambar selama ini = dianggap sujud
        const val UPRIGHT_SNAP_MS = 8000L // tegak selama ini = awal/akhir rakaat
    }

    private data class Sample(val t: Long, val src: Int, val tilt: Float)

    var ready = false
        private set
    var rakaat = 0
        private set
    var episodes = 0
        private set
    var deviation = 0f
        private set
    var baseline = 0f
        private set

    private var startedAt = -1L
    private var lastWarn = 0L
    private var lockedSrc = -1
    private val window = ArrayDeque<Sample>()
    private var inEpisode = false
    private var episodeStart = 0L
    private var lostSince = -1L
    private var uprightSince = -1L
    private var snapped = false

    fun onFrame(now: Long, src: Int, rawTilt: Float?) {
        if (startedAt < 0) {
            startedAt = now
            lastWarn = now
        }
        if (!ready) {
            calibrate(now, src, rawTilt)
            return
        }
        val tilt = if (rawTilt != null && src == lockedSrc) rawTilt else null

        val bent = when {
            tilt == null -> {
                if (lostSince < 0) lostSince = now
                inEpisode || now - lostSince >= LOST_MS
            }
            else -> {
                lostSince = -1
                deviation = abs(tilt - baseline)
                if (inEpisode) deviation >= OFF_DEG else deviation >= ON_DEG
            }
        }

        if (bent && !inEpisode) {
            inEpisode = true
            episodeStart = now
            uprightSince = -1
            snapped = false
        } else if (!bent && inEpisode) {
            inEpisode = false
            uprightSince = -1
            if (now - episodeStart >= MIN_EPISODE_MS) completeEpisode()
        }

        if (!bent && !inEpisode) {
            if (uprightSince < 0) uprightSince = now
            if (tilt != null && deviation < OFF_DEG) baseline += 0.02f * (tilt - baseline)
            if (!snapped && episodes % 3 != 0 && now - uprightSince >= UPRIGHT_SNAP_MS) {
                snapped = true
                if (episodes % 3 == 1) episodes-- else completeEpisode()
            }
        }
    }

    private fun completeEpisode() {
        episodes++
        if (episodes % 3 == 0) {
            rakaat++
            listener.onRakaat(rakaat)
        }
    }

    private fun calibrate(now: Long, src: Int, tilt: Float?) {
        if (now - lastWarn >= WARN_MS) {
            lastWarn = now
            listener.onNotReady()
        }
        if (tilt == null) {
            window.clear()
            return
        }
        window.addLast(Sample(now, src, tilt))
        while (window.isNotEmpty() && now - window.first().t > CALIB_MS) window.removeFirst()
        if (window.size < 10) return
        if (window.last().t - window.first().t < CALIB_MS - 300) return
        val s = window.first().src
        if (window.any { it.src != s }) return
        val vals = window.map { it.tilt }.sorted()
        val med = vals[vals.size / 2]
        if (vals.all { abs(it - med) < STABLE_DEG }) {
            baseline = med
            lockedSrc = s
            ready = true
            window.clear()
            listener.onReady()
        }
    }
}
