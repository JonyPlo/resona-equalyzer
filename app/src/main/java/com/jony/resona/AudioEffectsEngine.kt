package com.jony.resona

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/**
 * Motor de efectos sobre la mezcla global de audio (sesion 0).
 *
 * Maneja dos cosas distintas:
 *  - lo que SUENA: el perfil de la salida real (altavoz o auriculares). Con volumen dinamico
 *    ON, cual de los 3 perfiles suena depende del volumen; si no, es el perfil elegido a mano.
 *  - lo que se EDITA en pantalla: puede ser otro perfil u otra salida.
 * Los cambios editados se aplican al audio solo si coinciden con lo que esta sonando.
 *
 * Los cambios de perfil se hacen con una transicion suave (~0,5 s) para evitar clicks.
 * ProfileStore.init(...) debe haberse llamado antes de usar este objeto.
 */
object AudioEffectsEngine {

    val FREQ_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    const val MIN_DB = -15f
    const val MAX_DB = 15f

    private const val BASS_FREQ = 80f
    private const val BASS_MAX_DB = 15f
    private const val LOUDNESS_MAX_DB = 20f
    private const val CHANNELS = 2

    private const val RAMP_MS = 480L
    private const val RAMP_STEPS = 12

    private val ZONE_NAMES = listOf("bajo", "medio", "alto")

    private val CENTERS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    private val CUTOFFS = floatArrayOf(44f, 88f, 177f, 354f, 707f, 1414f, 2828f, 5657f, 11314f, 20000f)
    private val BASS_WEIGHTS = FloatArray(10) { i ->
        val r = CENTERS[i] / BASS_FREQ
        1f / (1f + r * r)
    }

    // ----- Lo que SUENA -----
    var appliedOutput: Output = Output.SPEAKER
        private set
    var appliedSlot = 0
        private set
    private var applied: Profile = Profile.default(0)

    // ----- Lo que se EDITA en pantalla -----
    var editOutput: Output = Output.SPEAKER
        private set
    var editSlot = 0
        private set
    var current: Profile = Profile.default(0)
        private set

    // ----- Volumen -----
    var volumePct = -1f
        private set
    private var zone = 1

    @Volatile var isActive = false
        private set

    private var loaded = false

    /** Sube cuando algo cambia desde afuera de la pantalla (enchufar auriculares, cambio de volumen...). */
    var uiTick by mutableIntStateOf(0)
        private set

    private var dp: DynamicsProcessing? = null
    private var loud: LoudnessEnhancer? = null

    // Valores que estan realmente enviados al sistema en este momento (cambian suavemente en una transicion)
    private val liveBands = FloatArray(10)
    private var liveLoudMb = 0

    private val rampHandler by lazy { Handler(Looper.getMainLooper()) }
    private var rampRunnable: Runnable? = null

    // ---------- calculo de valores ----------

    private fun effectiveOf(p: Profile, i: Int): Float {
        val bass = if (p.bassOn) BASS_MAX_DB * (p.bassPct / 100f) * BASS_WEIGHTS[i] else 0f
        return (p.gains[i] + bass).coerceIn(-30f, 30f)
    }

    private fun loudnessMbOf(p: Profile) =
        if (p.loudOn) (p.loudPct * LOUDNESS_MAX_DB).roundToInt() else 0

    private fun band(i: Int) = DynamicsProcessing.EqBand(true, CUTOFFS[i], liveBands[i])

    private fun limiter() =
        DynamicsProcessing.Limiter(true, applied.limiterOn, 0, 10f, 200f, 10f, -3f, 0f)

    private fun syncLive() {
        for (i in 0 until 10) liveBands[i] = effectiveOf(applied, i)
        liveLoudMb = loudnessMbOf(applied)
    }

    // ---------- enviar al sistema ----------

    private fun sendBands() {
        val d = dp ?: return
        try {
            for (ch in 0 until CHANNELS) for (b in 0 until 10) d.setPreEqBandByChannelIndex(ch, b, band(b))
        } catch (_: Throwable) {}
    }

    private fun sendLoudness() {
        try { loud?.setTargetGain(liveLoudMb) } catch (_: Throwable) {}
    }

    private fun applyLimiter() {
        val d = dp ?: return
        try {
            for (ch in 0 until CHANNELS) d.setLimiterByChannelIndex(ch, limiter())
        } catch (_: Throwable) {}
    }

    // ---------- aplicar (cambio inmediato) ----------

    private fun cancelRamp() {
        rampRunnable?.let { rampHandler.removeCallbacks(it) }
        rampRunnable = null
    }

    /** Si habia una transicion en curso, la termina de golpe antes de aplicar un cambio puntual. */
    private fun snapIfRamping() {
        if (rampRunnable != null) {
            cancelRamp()
            syncLive()
            sendBands()
            sendLoudness()
            applyLimiter()
        }
    }

    private fun applyBands() {
        snapIfRamping()
        for (i in 0 until 10) liveBands[i] = effectiveOf(applied, i)
        sendBands()
    }

    private fun applyLoudness() {
        snapIfRamping()
        liveLoudMb = loudnessMbOf(applied)
        sendLoudness()
    }

    private fun applyAll() {
        cancelRamp()
        syncLive()
        sendBands()
        applyLimiter()
        sendLoudness()
    }

    // ---------- aplicar (transicion suave) ----------

    /** Lleva los efectos desde los valores actuales hasta el perfil `applied` en ~0,5 s. */
    private fun rampToApplied() {
        cancelRamp()
        if (dp == null && loud == null) { syncLive(); return }

        val fromBands = liveBands.copyOf()
        val toBands = FloatArray(10) { effectiveOf(applied, it) }
        val fromLoud = liveLoudMb
        val toLoud = loudnessMbOf(applied)
        val stepMs = RAMP_MS / RAMP_STEPS

        if (applied.limiterOn) applyLimiter() // si el nuevo perfil lo enciende, protege desde el principio

        var n = 0
        val r = object : Runnable {
            override fun run() {
                synchronized(this@AudioEffectsEngine) {
                    if (rampRunnable !== this) return
                    n++
                    val t = n / RAMP_STEPS.toFloat()
                    for (i in 0 until 10) liveBands[i] = fromBands[i] + (toBands[i] - fromBands[i]) * t
                    liveLoudMb = (fromLoud + (toLoud - fromLoud) * t).roundToInt()
                    sendBands()
                    sendLoudness()
                    if (n >= RAMP_STEPS) {
                        rampRunnable = null
                        applyLimiter()
                    } else {
                        rampHandler.postDelayed(this, stepMs)
                    }
                }
            }
        }
        rampRunnable = r
        rampHandler.postDelayed(r, stepMs)
    }

    // ---------- carga desde el telefono ----------

    private fun computeAppliedSlot() =
        if (ProfileStore.dynamicEnabled) zone else ProfileStore.activeSlot(appliedOutput)

    private fun loadAppliedFromStore() {
        appliedSlot = computeAppliedSlot()
        applied = ProfileStore.profile(appliedOutput, appliedSlot)
    }

    private fun loadEditFromStore() {
        editSlot = ProfileStore.activeSlot(editOutput)
        current = ProfileStore.profile(editOutput, editSlot)
    }

    /** Recarga todo desde el telefono (lo que suena y lo que se edita). */
    @Synchronized
    fun reloadFromStore() {
        loadAppliedFromStore()
        loadEditFromStore()
        loaded = true
        applyAll()
        uiTick++
    }

    /**
     * Indica cual es la salida real (altavoz / auriculares). Carga y aplica el perfil que corresponde.
     * Si la salida cambio, la pantalla pasa a mostrar los perfiles de la nueva salida.
     */
    @Synchronized
    fun setOutput(o: Output) {
        val changed = o != appliedOutput
        appliedOutput = o
        loadAppliedFromStore()
        applyAll()
        if (changed || !loaded) {
            editOutput = o
            loadEditFromStore()
        }
        loaded = true
        uiTick++
    }

    // ---------- volumen dinamico ----------

    private fun zoneOf(pct: Float): Int {
        if (pct < 0f) return 1
        return when {
            pct < ProfileStore.dynLow -> 0
            pct < ProfileStore.dynHigh -> 1
            else -> 2
        }
    }

    private fun switchApplied() {
        loadAppliedFromStore()
        rampToApplied()
        uiTick++
    }

    private fun reevaluateZone() {
        val z = zoneOf(volumePct)
        if (z == zone) return
        zone = z
        val dyn = ProfileStore.dynamicEnabled
        DebugLog.add(
            "Volumen ${volumePct.roundToInt()}% -> rango ${ZONE_NAMES[z]}" +
                    if (dyn) ", perfil ${z + 1}" else " (dinamico apagado)"
        )
        if (dyn) switchApplied() else uiTick++
    }

    /** El servicio informa el volumen de musica actual (0-100). */
    @Synchronized
    fun setVolumePercent(pct: Float) {
        volumePct = pct
        reevaluateZone()
    }

    @Synchronized
    fun setDynamic(on: Boolean) {
        ProfileStore.dynamicEnabled = on
        DebugLog.add("Volumen dinamico ${if (on) "encendido" else "apagado"}")
        switchApplied()
    }

    @Synchronized
    fun setDynLow(v: Int) {
        ProfileStore.dynLow = v.coerceIn(1, ProfileStore.dynHigh - 1)
        reevaluateZone()
        uiTick++
    }

    @Synchronized
    fun setDynHigh(v: Int) {
        ProfileStore.dynHigh = v.coerceIn(ProfileStore.dynLow + 1, 99)
        reevaluateZone()
        uiTick++
    }

    // ---------- ciclo de vida ----------

    /** Crea los efectos con el perfil que corresponde a la salida y el volumen actuales. Devuelve texto de estado. */
    @Synchronized
    fun start(): String {
        release()
        loadAppliedFromStore()
        syncLive()
        if (!loaded) {
            editOutput = appliedOutput
            loadEditFromStore()
            loaded = true
        }
        val sb = StringBuilder()

        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNELS,
                true, 10,
                false, 0,
                false, 0,
                true
            ).build()
            val d = DynamicsProcessing(0, 0, config)
            for (ch in 0 until CHANNELS) {
                d.setLimiterByChannelIndex(ch, limiter())
                for (b in 0 until 10) d.setPreEqBandByChannelIndex(ch, b, band(b))
            }
            d.enabled = true
            dp = d
            sb.append("EQ + bass (${appliedOutput.label} ${appliedSlot + 1}): OK\n")
        } catch (e: Throwable) {
            sb.append("EQ: FALLO (${e.javaClass.simpleName}: ${e.message})\n")
        }

        try {
            val l = LoudnessEnhancer(0)
            l.setTargetGain(liveLoudMb)
            l.enabled = true
            loud = l
            sb.append("Loudness: OK\n")
        } catch (e: Throwable) {
            sb.append("Loudness: FALLO (${e.javaClass.simpleName})\n")
        }

        isActive = try {
            dp?.hasControl() == true && dp?.enabled == true
        } catch (_: Throwable) {
            false
        }

        return sb.toString().trim()
    }

    @Synchronized
    fun release() {
        cancelRamp()
        try { dp?.release() } catch (_: Throwable) {}
        try { loud?.release() } catch (_: Throwable) {}
        dp = null; loud = null
        isActive = false
    }

    // ---------- perfiles ----------

    /**
     * Elige cual de los 3 perfiles del conjunto se edita. Con volumen dinamico apagado tambien pasa a ser
     * el que suena (si es la salida real); con volumen dinamico encendido solo cambia cual se edita.
     */
    @Synchronized
    fun selectSlot(slot: Int) {
        editSlot = slot
        current = ProfileStore.profile(editOutput, slot)
        if (!ProfileStore.dynamicEnabled) {
            ProfileStore.setActiveSlot(editOutput, slot)
            if (editOutput == appliedOutput) {
                appliedSlot = slot
                applied = current
                rampToApplied()
            }
        }
        uiTick++
    }

    /** Cambia que conjunto se edita en pantalla (altavoz / auriculares). No cambia lo que suena. */
    @Synchronized
    fun setEditOutput(o: Output) {
        editOutput = o
        loadEditFromStore()
        uiTick++
    }

    private fun update(p: Profile, bands: Boolean = false, lim: Boolean = false, ld: Boolean = false) {
        current = p
        ProfileStore.saveProfile(editOutput, editSlot, p)
        if (editOutput == appliedOutput && editSlot == appliedSlot) {
            applied = p
            if (bands) applyBands()
            if (lim) applyLimiter()
            if (ld) applyLoudness()
        }
    }

    // ---------- cambios del usuario ----------

    @Synchronized
    fun setBand(index: Int, db: Float) {
        val g = current.gains.toMutableList()
        g[index] = db.coerceIn(MIN_DB, MAX_DB)
        update(current.copy(gains = g), bands = true)
    }

    @Synchronized
    fun setBassOn(on: Boolean) = update(current.copy(bassOn = on), bands = true)

    @Synchronized
    fun setBass(percent: Float) = update(current.copy(bassPct = percent.coerceIn(0f, 100f)), bands = true)

    @Synchronized
    fun setLoudOn(on: Boolean) = update(current.copy(loudOn = on), ld = true)

    @Synchronized
    fun setLoud(percent: Float) = update(current.copy(loudPct = percent.coerceIn(0f, 100f)), ld = true)

    @Synchronized
    fun setLimiter(on: Boolean) = update(current.copy(limiterOn = on), lim = true)

    @Synchronized
    fun setName(name: String) = update(current.copy(name = name))

    /**
     * Guarda un perfil recibido en el lugar elegido. Con `apply` ademas pasa a ser el perfil elegido
     * de esa salida (suena enseguida si esa es la salida real y el volumen dinamico esta apagado).
     */
    @Synchronized
    fun saveImported(o: Output, slot: Int, p: Profile, apply: Boolean) {
        ProfileStore.saveProfile(o, slot, p)
        if (apply) {
            setEditOutput(o)
            selectSlot(slot)
        } else {
            if (editOutput == o && editSlot == slot) current = p
            if (appliedOutput == o && appliedSlot == slot) {
                applied = p
                rampToApplied()
            }
            uiTick++
        }
    }

    /** Restaura una copia de seguridad completa y aplica lo que corresponde a la salida actual. */
    @Synchronized
    fun restoreBackup(b: Backup) {
        ProfileStore.restoreBackup(b)
        loadAppliedFromStore()
        loadEditFromStore()
        loaded = true
        rampToApplied()
        uiTick++
    }

    /** Vuelve el perfil que se esta editando a sus valores iniciales (Echo en altavoz, plano en auriculares). */
    @Synchronized
    fun resetToDefault() = update(
        ProfileStore.defaultFor(editOutput, editSlot).copy(name = current.name),
        bands = true, lim = true, ld = true
    )

    /** EQ plano: bandas en 0, bass y loudness apagados. */
    @Synchronized
    fun resetToFlat() = update(
        current.copy(gains = List(10) { 0f }, bassOn = false, loudOn = false),
        bands = true, ld = true
    )
}