package id.pribadi.rakaat

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Menjalankan satu sesi: kamera depan -> deteksi pose -> mesin hitung -> umpan balik. */
class SessionRunner(
    private val activity: ComponentActivity,
    private val target: Int?,
    private val prefs: Prefs
) : RakaatEngine.Listener {

    var count by mutableStateOf(0)
        private set
    var message by mutableStateOf("")
        private set
    var flash by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    var debug by mutableStateOf("")
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val fb = Feedback(activity, prefs) { flashNow() }
    private val engine = RakaatEngine(this)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
    )
    private var provider: ProcessCameraProvider? = null
    private var stopped = false

    fun start() {
        message = "Letakkan HP di antara tempat duduk dan tempat sujud, atau di samping tempat sujud. " +
            "Lalu berdiri dan mulai sholat."
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (stopped) return@addListener
            val p = future.get()
            provider = p
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
            p.unbindAll()
            p.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(activity))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || finished || stopped) {
            proxy.close()
            return
        }
        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(img)
            .addOnSuccessListener { pose ->
                if (stopped || finished) return@addOnSuccessListener
                val (src, tilt) = PostureMath.measure(pose)
                engine.onFrame(SystemClock.elapsedRealtime(), src, tilt)
                if (prefs.debug) {
                    debug = (if (engine.ready) "siap" else "kalibrasi") +
                        " src=$src tilt=${tilt?.toInt()} dasar=${engine.baseline.toInt()}" +
                        " dev=${engine.deviation.toInt()} bungkuk=${engine.episodes}"
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun flashNow() {
        flash = true
        handler.postDelayed({ flash = false }, 250)
    }

    override fun onReady() {
        message = ""
        fb.ready()
    }

    override fun onNotReady() {
        fb.warn()
    }

    override fun onRakaat(total: Int) {
        count = total
        if (target != null && total >= target) {
            finished = true
            message = "Selesai"
            fb.done()
            provider?.unbindAll()
        } else {
            fb.rakaat()
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.removeCallbacksAndMessages(null)
        provider?.unbindAll()
        detector.close()
        executor.shutdown()
        fb.release()
    }
}
