package id.pribadi.rakaat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(this@MainActivity) }
            }
        }
    }
}

@Composable
fun App(activity: ComponentActivity) {
    val prefs = remember { Prefs(activity) }
    var plan by remember { mutableStateOf<Plan?>(null) }
    val p = plan
    if (p == null) {
        Home(activity, prefs) { plan = it }
    } else {
        SessionScreen(activity, p, prefs) { plan = null }
    }
}

@Composable
fun Home(activity: ComponentActivity, prefs: Prefs, onStart: (Plan) -> Unit) {
    var locTick by remember { mutableStateOf(0) }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        locTick += 1
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) locLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val auto = remember(locTick) { Prayers.detect(activity, prefs) }

    var fardhu by remember { mutableStateOf(true) }
    var selected by remember(auto) { mutableStateOf(auto ?: "zuhur") }
    var vib by remember { mutableStateOf(prefs.vib) }
    var tasbih by remember { mutableStateOf(prefs.tasbih) }
    var flash by remember { mutableStateOf(prefs.flash) }
    var debug by remember { mutableStateOf(prefs.debug) }
    var pending by remember { mutableStateOf<Plan?>(null) }

    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pending?.let(onStart)
    }

    val start = {
        val plan = if (fardhu) Prayers.plan(selected) else Plan("Sholat sunat", null)
        val camOk = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (camOk) {
            onStart(plan)
        } else {
            pending = plan
            camLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Penghitung rakaat", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = fardhu, onClick = { fardhu = true }, label = { Text("Fardhu") })
            FilterChip(selected = !fardhu, onClick = { fardhu = false }, label = { Text("Sunat") })
        }

        if (fardhu) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Prayers.list().forEach { pr ->
                    FilterChip(
                        selected = selected == pr.key,
                        onClick = { selected = pr.key },
                        label = { Text("${pr.name} (${pr.rakaat})") }
                    )
                }
            }
            Text(
                if (auto != null) "Waktu sholat terdeteksi otomatis. Ketuk untuk mengganti."
                else "Lokasi belum tersedia. Pilih waktu sholat secara manual.",
                fontSize = 13.sp,
                color = Color.Gray
            )
        } else {
            Text("Hanya menghitung jumlah rakaat. Akhiri sesi dengan ketuk dua kali setelah salam.",
                fontSize = 13.sp, color = Color.Gray)
        }

        Text("Umpan balik saat sholat", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vib, onClick = { vib = !vib; prefs.vib = vib }, label = { Text("Getar") })
            FilterChip(selected = tasbih, onClick = { tasbih = !tasbih; prefs.tasbih = tasbih }, label = { Text("Suara tasbih") })
            FilterChip(selected = flash, onClick = { flash = !flash; prefs.flash = flash }, label = { Text("Kedip layar") })
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Mode uji (tampilkan data deteksi)")
            Switch(checked = debug, onCheckedChange = { debug = it; prefs.debug = it })
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = { start() }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("Mulai", fontSize = 20.sp)
        }
    }
}

@Composable
fun SessionScreen(activity: ComponentActivity, plan: Plan, prefs: Prefs, onEnd: () -> Unit) {
    val runner = remember { SessionRunner(activity, plan.target, prefs) }

    DisposableEffect(Unit) {
        val w = activity.window
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = w.attributes
        lp.screenBrightness = 0.15f
        w.attributes = lp
        runner.start()
        onDispose {
            runner.stop()
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val lp2 = w.attributes
            lp2.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            w.attributes = lp2
        }
    }

    BackHandler { onEnd() }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (runner.flash) Color(0xFF2B2B2B) else Color.Black)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onEnd() }) },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(
                runner.count.toString(),
                color = Color(0xFFD0D0D0),
                fontSize = 140.sp,
                fontWeight = FontWeight.Light
            )
            plan.target?.let {
                Text("${plan.title}, $it rakaat", color = Color(0xFF9A9A9A), fontSize = 18.sp)
            }
            if (plan.target == null) {
                Text(plan.title, color = Color(0xFF9A9A9A), fontSize = 18.sp)
            }
            if (runner.message.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    runner.message,
                    color = Color(0xFF9A9A9A),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
            }
            if (prefs.debug) {
                Spacer(Modifier.height(24.dp))
                Text(
                    runner.debug,
                    color = Color(0xFFA0A0A0),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(24.dp))
            Text("Ketuk dua kali untuk mengakhiri", color = Color(0xFF707070), fontSize = 12.sp)
        }
    }
}
