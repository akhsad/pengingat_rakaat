package id.pribadi.rakaat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.Prayer
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Plan(val title: String, val target: Int?)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("rakaat", Context.MODE_PRIVATE)

    var vib: Boolean
        get() = sp.getBoolean("vib", true)
        set(v) { sp.edit().putBoolean("vib", v).apply() }
    var tasbih: Boolean
        get() = sp.getBoolean("tasbih", false)
        set(v) { sp.edit().putBoolean("tasbih", v).apply() }
    var flash: Boolean
        get() = sp.getBoolean("flash", false)
        set(v) { sp.edit().putBoolean("flash", v).apply() }
    var debug: Boolean
        get() = sp.getBoolean("debug", false)
        set(v) { sp.edit().putBoolean("debug", v).apply() }

    fun saveCoords(lat: Double, lon: Double) {
        sp.edit().putString("lat", lat.toString()).putString("lon", lon.toString()).apply()
    }

    fun coords(): Pair<Double, Double>? {
        val la = sp.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lo = sp.getString("lon", null)?.toDoubleOrNull() ?: return null
        return la to lo
    }
}

/** Daftar sholat fardhu dan deteksi dari waktu sholat (dihitung offline). */
object Prayers {
    data class P(val key: String, val name: String, val rakaat: Int)

    private fun isFriday() = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY

    fun list(): List<P> {
        val l = mutableListOf(
            P("subuh", "Subuh", 2), P("zuhur", "Zuhur", 4), P("asar", "Asar", 4),
            P("magrib", "Magrib", 3), P("isya", "Isya", 4)
        )
        if (isFriday()) l.add(2, P("jumat", "Jumat", 2))
        return l
    }

    fun plan(key: String): Plan {
        val p = list().firstOrNull { it.key == key } ?: list().first { it.key == "zuhur" }
        return Plan(p.name, p.rakaat)
    }

    @SuppressLint("MissingPermission")
    private fun coords(ctx: Context, prefs: Prefs): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val best = lm.getProviders(true)
                    .mapNotNull { lm.getLastKnownLocation(it) }
                    .maxByOrNull { it.time }
                if (best != null) {
                    prefs.saveCoords(best.latitude, best.longitude)
                    return best.latitude to best.longitude
                }
            } catch (_: Exception) {
            }
        }
        return prefs.coords()
    }

    /** Kunci sholat fardhu yang sedang berlaku, atau null jika lokasi belum diketahui. */
    fun detect(ctx: Context, prefs: Prefs): String? {
        val c = coords(ctx, prefs) ?: return null
        val now = Date()
        val times = PrayerTimes(
            Coordinates(c.first, c.second),
            DateComponents.from(now),
            CalculationMethod.SINGAPORE.parameters
        )
        val key = when (times.currentPrayer(now)) {
            Prayer.FAJR -> "subuh"
            Prayer.ASR -> "asar"
            Prayer.MAGHRIB -> "magrib"
            Prayer.ISHA -> "isya"
            else -> if (times.currentPrayer(now) == Prayer.NONE) "isya" else "zuhur"
        }
        return if (key == "zuhur" && isFriday()) "jumat" else key
    }
}

/** Umpan balik: getar, suara tasbih, kedip layar (sesuai pilihan di pengaturan). */
class Feedback(ctx: Context, private val prefs: Prefs, private val onFlash: () -> Unit) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var arabic = false

    init {
        tts = TextToSpeech(ctx) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                if (t.setLanguage(Locale("ar")) >= TextToSpeech.LANG_AVAILABLE) {
                    arabic = true
                    ttsReady = true
                } else {
                    ttsReady = t.setLanguage(Locale("id", "ID")) >= TextToSpeech.LANG_AVAILABLE
                }
            }
        }
    }

    private fun buzz(pattern: LongArray) {
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    private fun speak() {
        if (!ttsReady) return
        val b = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.5f) }
        tts?.speak(if (arabic) "سبحان الله" else "Subhanallah", TextToSpeech.QUEUE_FLUSH, b, "tasbih")
    }

    /** Isyarat siap sebelum takbir. */
    fun ready() { if (prefs.vib) buzz(longArrayOf(0, 60)) }

    /** Peringatan: tubuh belum terdeteksi sebelum sholat dimulai. */
    fun warn() = buzz(longArrayOf(0, 80, 80, 80, 80, 80))

    fun rakaat() {
        if (prefs.vib) buzz(longArrayOf(0, 150))
        if (prefs.tasbih) speak()
        if (prefs.flash) onFlash()
    }

    fun done() {
        if (prefs.vib) buzz(longArrayOf(0, 200, 100, 200, 100, 400))
        if (prefs.tasbih) speak()
        if (prefs.flash) onFlash()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
