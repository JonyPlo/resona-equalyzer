package com.jony.resona

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        ProfileStore.init(this)
        DebugLog.init(this)
        DebugLog.add("App abierta, motor activo en este proceso=${AudioEffectsEngine.isActive}")
        // Salida real (altavoz / auriculares): carga lo guardado en el telefono para mostrarlo
        AudioEffectsEngine.setOutput(OutputDetector.current(this))
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            ResonaAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) { EqScreen() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    @Suppress("DEPRECATION")
    private fun streamUri(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    /** Si la app se abrio desde un archivo de perfil (abrir con / compartir), lo lee y pide confirmacion. */
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent?.data
            Intent.ACTION_SEND -> intent?.let { streamUri(it) }
            else -> null
        } ?: return
        val incoming = ProfileIO.readUri(this, uri)
        if (incoming != null) ImportInbox.offer(incoming)
        else Toast.makeText(this, "Ese archivo no es de Resona", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun EqScreen() {
    val context = LocalContext.current
    val engine = AudioEffectsEngine
    val colors = MaterialTheme.colorScheme

    var p by remember { mutableStateOf(engine.current) }
    var slot by remember { mutableIntStateOf(engine.editSlot) }
    var editOut by remember { mutableStateOf(engine.editOutput) }
    var appliedOut by remember { mutableStateOf(engine.appliedOutput) }
    var dynOn by remember { mutableStateOf(ProfileStore.dynamicEnabled) }
    var dynLow by remember { mutableIntStateOf(ProfileStore.dynLow) }
    var dynHigh by remember { mutableIntStateOf(ProfileStore.dynHigh) }
    var sounding by remember { mutableIntStateOf(engine.appliedSlot) }
    var volPct by remember { mutableFloatStateOf(engine.volumePct) }
    var enabled by remember { mutableStateOf(engine.isActive) }
    var status by remember { mutableStateOf(if (engine.isActive) "Activo" else "Apagado") }
    var log by remember { mutableStateOf(DebugLog.read()) }
    var advanced by remember { mutableStateOf(false) }

    fun refresh() {
        p = engine.current
        slot = engine.editSlot
        editOut = engine.editOutput
        appliedOut = engine.appliedOutput
        dynOn = ProfileStore.dynamicEnabled
        dynLow = ProfileStore.dynLow
        dynHigh = ProfileStore.dynHigh
        sounding = engine.appliedSlot
        volPct = engine.volumePct
    }

    // Cambios que vienen de afuera de la pantalla (por ejemplo al enchufar auriculares)
    val externalTick = engine.uiTick
    LaunchedEffect(externalTick) { refresh() }

    fun startService() {
        ContextCompat.startForegroundService(context, Intent(context, EqService::class.java))
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            status = EqService.lastStatus.ifBlank { "Iniciando..." }
        }, 600)
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startService() }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // Guardar / importar perfiles como archivo
    var exportText by remember { mutableStateOf<String?>(null) }
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val text = exportText
        if (uri != null && text != null) {
            toast(if (ProfileIO.writeText(context, uri, text)) "Archivo guardado" else "No se pudo guardar el archivo")
        }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val incoming = ProfileIO.readUri(context, uri)
            if (incoming != null) ImportInbox.offer(incoming) else toast("Ese archivo no es de Resona")
        }
    }

    fun turnOn() {
        enabled = true
        ProfileStore.serviceWanted = true
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startService()
    }

    fun turnOff() {
        enabled = false
        ProfileStore.serviceWanted = false
        context.stopService(Intent(context, EqService::class.java))
        status = "Apagado"
    }

    // Al abrir la app: si el usuario lo tenia encendido y no esta corriendo, se enciende solo
    LaunchedEffect(Unit) {
        if (ProfileStore.serviceWanted && !engine.isActive) {
            DebugLog.add("App: el motor no estaba activo, arranco el servicio")
            turnOn()
        }
    }

    val slotNames = remember(editOut, p.name, externalTick) {
        List(ProfileStore.SLOTS) { ProfileStore.profile(editOut, it).name }
    }
    val soundingName = remember(appliedOut, sounding, p.name, externalTick) {
        ProfileStore.profile(appliedOut, sounding).name
    }

    ImportDialog()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- Encabezado ----
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text("Resona", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Ecualizador para todo el teléfono",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }

        // ---- Estado general ----
        val subtitle = when {
            !enabled -> "El audio suena sin efectos."
            status.contains("FALLO") -> "No se pudieron crear los efectos. Probá apagar y volver a encender."
            else -> (if (appliedOut == Output.SPEAKER) "Por el altavoz" else "Por los auriculares") +
                    " con $soundingName."
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (enabled) colors.primaryContainer else colors.surface
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (enabled) "Ecualizador activo" else "Ecualizador apagado",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) colors.onPrimaryContainer else colors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = { if (it) turnOn() else turnOff() })
            }
        }

        SetupCard()

        // ---- Perfiles ----
        Panel(
            title = "Perfiles",
            subtitle = if (editOut != appliedOut)
                "Estás editando los de ${editOut.label.lowercase()}; suenan cuando esa salida esté conectada."
            else null
        ) {
            ChoiceRow(
                options = Output.values().map { it.label },
                selected = editOut.ordinal,
                onSelect = { engine.setEditOutput(Output.values()[it]); refresh() }
            )
            ChoiceRow(
                options = slotNames,
                selected = slot,
                onSelect = { engine.selectSlot(it); refresh() }
            )
            OutlinedTextField(
                value = p.name,
                onValueChange = { engine.setName(it.take(40)); refresh() },
                label = { Text("Nombre del perfil") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ---- Ecualizador ----
        Panel(title = "Ecualizador", subtitle = "Ganancia por banda, de -15 a +15 dB", corner = 24.dp) {
            EqCurve(gains = p.gains)
            AudioEffectsEngine.FREQ_LABELS.forEachIndexed { i, label ->
                StepRow(
                    label = "$label Hz",
                    value = p.gains[i],
                    min = AudioEffectsEngine.MIN_DB,
                    max = AudioEffectsEngine.MAX_DB,
                    snapStep = 0.5f,
                    fineStep = 0.1f,
                    format = "%+.1f",
                    accent = spectrumColor(i / 9f)
                ) { engine.setBand(i, it); refresh() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Valores iniciales", { engine.resetToDefault(); refresh() }, Modifier.weight(1f))
                ActionButton("Dejar plano", { engine.resetToFlat(); refresh() }, Modifier.weight(1f))
            }
        }

        // ---- Bass y loudness ----
        Panel(title = "Bass y loudness") {
            SwitchRow(
                title = "Bass boost",
                description = "Realza los graves desde 80 Hz.",
                checked = p.bassOn,
                onChecked = { engine.setBassOn(it); refresh() }
            )
            StepRow(
                label = "",
                value = p.bassPct, min = 0f, max = 100f,
                snapStep = 1f, fineStep = 1f, format = "%.0f%%",
                enabled = p.bassOn,
                accent = ResonaPalette.Low
            ) { engine.setBass(it); refresh() }

            HorizontalDivider(color = colors.outlineVariant)

            SwitchRow(
                title = "Loudness",
                description = "Sube el volumen percibido, hasta 20 dB.",
                checked = p.loudOn,
                onChecked = { engine.setLoudOn(it); refresh() }
            )
            StepRow(
                label = "",
                value = p.loudPct, min = 0f, max = 100f,
                snapStep = 1f, fineStep = 1f, format = "%.0f%%",
                enabled = p.loudOn,
                accent = ResonaPalette.LowMid
            ) { engine.setLoud(it); refresh() }
        }

        // ---- Volumen dinámico ----
        Panel(
            title = "Volumen dinámico",
            trailing = { Switch(checked = dynOn, onCheckedChange = { engine.setDynamic(it); refresh() }) }
        ) {
            Text(
                "Cambia de perfil según el volumen del celular: hasta $dynLow% usa el Perfil 1, " +
                        "de $dynLow% a $dynHigh% el Perfil 2 y desde $dynHigh% el Perfil 3, " +
                        "siempre dentro de la salida que está sonando.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            if (dynOn) {
                val volText = if (volPct >= 0f) "${volPct.roundToInt()}%" else "sin dato"
                Text(
                    "Volumen actual: $volText. Suena $soundingName. " +
                            "Para escuchar un perfil mientras lo editás, poné el volumen en su rango.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            StepRow(
                label = "Hasta",
                value = dynLow.toFloat(), min = 1f, max = 99f,
                snapStep = 1f, fineStep = 1f, format = "%.0f%%"
            ) { engine.setDynLow(it.roundToInt()); refresh() }
            StepRow(
                label = "Desde",
                value = dynHigh.toFloat(), min = 1f, max = 99f,
                snapStep = 1f, fineStep = 1f, format = "%.0f%%"
            ) { engine.setDynHigh(it.roundToInt()); refresh() }
        }

        // ---- Guardar y compartir ----
        Panel(title = "Guardar y compartir") {
            Text("Este perfil: ${p.name}", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    "Compartir archivo",
                    { ProfileIO.shareFile(context, p) },
                    Modifier.weight(1f),
                    filled = true
                )
                ActionButton(
                    "Guardar en archivo",
                    { exportText = ProfileIO.profileText(p); createDoc.launch(ProfileIO.fileName(p)) },
                    Modifier.weight(1f)
                )
            }
            ActionButton("Importar archivo", { openDoc.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())

            HorizontalDivider(color = colors.outlineVariant)

            Text("Copia de seguridad completa", style = MaterialTheme.typography.titleSmall)
            Text(
                "Los 6 perfiles y los ajustes del volumen dinámico en un solo archivo. " +
                        "Sirve para recuperar todo si se borra la app.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    "Guardar copia",
                    {
                        exportText = ProfileIO.backupText(ProfileStore.createBackup())
                        createDoc.launch(ProfileIO.backupFileName())
                    },
                    Modifier.weight(1f),
                    filled = true
                )
                ActionButton(
                    "Compartir copia",
                    { ProfileIO.shareBackup(context, ProfileStore.createBackup()) },
                    Modifier.weight(1f)
                )
            }
            ActionButton("Restaurar copia", { openDoc.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())
        }

        // ---- Avanzado ----
        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "Ocultar opciones avanzadas" else "Opciones avanzadas")
        }
        if (advanced) {
            Panel(title = "Avanzado") {
                SwitchRow(
                    title = "Limitador",
                    description = "Evita la distorsión cuando subís mucho el ecualizador. Se guarda por perfil.",
                    checked = p.limiterOn,
                    onChecked = { engine.setLimiter(it); refresh() }
                )

                HorizontalDivider(color = colors.outlineVariant)

                Text("Registro de eventos", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Actualizar", { log = DebugLog.read() }, Modifier.weight(1f))
                    ActionButton("Borrar", { DebugLog.clear(); log = "" }, Modifier.weight(1f))
                }
                Text(
                    log.lines().filter { it.isNotBlank() }.reversed().joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant
                )
                Text(
                    "Resona 1.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}