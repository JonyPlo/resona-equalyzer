package com.jony.resona

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de eventos que sobrevive a que maten el proceso (se guarda en el telefono).
 * Cada linea lleva hora y PID: si cambia el PID, es que el proceso se reinicio.
 */
object DebugLog {
    private lateinit var prefs: SharedPreferences
    private const val MAX_LINES = 40

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences("resona_log", Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    fun add(msg: String) {
        if (!::prefs.isInitialized) return
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$time [${android.os.Process.myPid()}] $msg"
        val lines = read().lines().filter { it.isNotBlank() }.takeLast(MAX_LINES - 1) + line
        prefs.edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun read(): String = if (::prefs.isInitialized) prefs.getString("log", "") ?: "" else ""

    @Synchronized
    fun clear() {
        if (::prefs.isInitialized) prefs.edit().putString("log", "").apply()
    }
}
