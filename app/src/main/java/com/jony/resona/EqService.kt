package com.jony.resona

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

class EqService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager

    private var wasPlaying = false
    private var lastOutput = Output.SPEAKER

    /**
     * true = los efectos se crearon MIENTRAS sonaba audio, asi que estan enganchados a la salida real.
     * false = se crearon en silencio (arranque, reinicio del sistema...) y hay que recrearlos
     * UNA vez cuando empiece la primera reproduccion.
     */
    private var effectsKnownGood = false
    private var lastPlaybackEnd = 0L
    private var pendingReason = ""

    private fun isPlaybackActive(): Boolean = try {
        audioManager.activePlaybackConfigurations.isNotEmpty()
    } catch (_: Throwable) {
        false
    }

    /** Crea (o recrea) los efectos. Recrearlos en pleno audio produce un "click", asi que se hace lo minimo. */
    private fun createEffects(reason: String) {
        lastStatus = AudioEffectsEngine.start()
        val playing = isPlaybackActive()
        effectsKnownGood = playing && !lastStatus.contains("FALLO")
        DebugLog.add(
            "Efectos creados ($reason), sonando=$playing, enganchados=$effectsKnownGood: " +
                    lastStatus.replace("\n", " | ")
        )
        updateNotification()
    }

    private val recreateEffects = Runnable {
        if (isPlaybackActive()) createEffects(pendingReason)
        else DebugLog.add("Recreacion omitida: ya no hay audio sonando")
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val playing = !configs.isNullOrEmpty()
            val now = SystemClock.elapsedRealtime()

            if (playing && !wasPlaying) {
                val silentMs = now - lastPlaybackEnd
                handler.removeCallbacks(volumePoll)
                handler.post(volumePoll)
                val needs = !effectsKnownGood || silentMs > REATTACH_AFTER_SILENCE_MS
                if (needs) {
                    pendingReason = if (!effectsKnownGood) "primera reproduccion tras arranque/reinicio"
                    else "silencio de ${silentMs / 1000}s"
                    DebugLog.add("Reproduccion iniciada: recreo efectos en 300 ms ($pendingReason)")
                    handler.removeCallbacks(recreateEffects)
                    handler.postDelayed(recreateEffects, 300)
                } else {
                    DebugLog.add("Reproduccion iniciada: efectos ya enganchados, no se tocan")
                }
            } else if (!playing && wasPlaying) {
                lastPlaybackEnd = now
                DebugLog.add("Reproduccion terminada")
            }
            wasPlaying = playing
        }
    }

    /** Lee el volumen de musica actual y se lo informa al motor (para el volumen dinamico). */
    private fun reportVolume() {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val v = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) AudioEffectsEngine.setVolumePercent(v * 100f / max)
        } catch (_: Throwable) {
        }
    }

    /** Respaldo: mientras suena audio, relee el volumen cada segundo por si el aviso del sistema no llega. */
    private val volumePoll = object : Runnable {
        override fun run() {
            reportVolume()
            if (wasPlaying) handler.postDelayed(this, 1000)
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC) {
                reportVolume()
            }
        }
    }

    /** Detecta cuando se enchufan o desenchufan auriculares (cable o Bluetooth). */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            checkOutput("dispositivo conectado")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            checkOutput("dispositivo desconectado")
        }
    }

    private fun checkOutput(reason: String) {
        val now = OutputDetector.current(audioManager)
        if (now == lastOutput) return
        lastOutput = now
        DebugLog.add("Salida cambiada a ${now.label} ($reason)")
        AudioEffectsEngine.setOutput(now)
        reportVolume()
        handler.postDelayed({ reportVolume() }, 700)
        // La salida fisica cambio: los efectos pueden necesitar engancharse de nuevo (un click unico)
        effectsKnownGood = false
        pendingReason = "cambio de salida a ${now.label}"
        handler.removeCallbacks(recreateEffects)
        handler.postDelayed(recreateEffects, 500)
        updateNotification()
    }

    override fun onCreate() {
        super.onCreate()
        ProfileStore.init(this)
        DebugLog.init(this)
        DebugLog.add("Servicio creado")

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Resona activo", NotificationManager.IMPORTANCE_LOW)
        )

        val notification = buildNotification("Ecualizador activo")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        audioManager = getSystemService(AudioManager::class.java)
        lastPlaybackEnd = SystemClock.elapsedRealtime()
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        wasPlaying = isPlaybackActive()

        reportVolume()
        lastOutput = OutputDetector.current(audioManager)
        AudioEffectsEngine.setOutput(lastOutput)
        ContextCompat.registerReceiver(
            this, volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_EXPORTED
        )
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        DebugLog.add("Salida actual: ${lastOutput.label}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null significa que el sistema reinicio el servicio (START_STICKY)
        ProfileStore.init(this)
        DebugLog.init(this)
        createEffects(if (intent == null) "reinicio del sistema" else "desde la app")
        return START_STICKY
    }

    override fun onDestroy() {
        DebugLog.add("Servicio destruido")
        handler.removeCallbacks(recreateEffects)
        try { audioManager.unregisterAudioPlaybackCallback(playbackCallback) } catch (_: Throwable) {}
        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Throwable) {}
        handler.removeCallbacks(volumePoll)
        try { unregisterReceiver(volumeReceiver) } catch (_: Throwable) {}
        AudioEffectsEngine.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Resona")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_resona)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    /** La notificacion refleja si los efectos realmente se crearon. */
    private fun updateNotification() {
        val ok = !lastStatus.contains("FALLO")
        val text = if (ok) "Ecualizador activo - ${lastOutput.label}" else "Error al crear los efectos"
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "resona_active"
        private const val NOTIF_ID = 1

        /** Si el audio estuvo en silencio mas que esto, se recrean los efectos al volver a sonar. */
        private const val REATTACH_AFTER_SILENCE_MS = 30 * 60 * 1000L

        @Volatile var lastStatus: String = ""

    }
}