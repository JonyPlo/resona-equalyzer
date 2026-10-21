package com.jony.resona

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Guarda en el telefono los perfiles: 3 para el altavoz y 3 para auriculares,
 * el perfil activo de cada salida y si el servicio debe estar encendido.
 * Los perfiles del altavoz conservan las mismas claves de antes, asi no se pierden.
 */
object ProfileStore {
    const val SLOTS = 3
    private const val PREFS = "resona_prefs"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun slotKey(o: Output, slot: Int) = if (o == Output.SPEAKER) "slot$slot" else "hp_slot$slot"
    private fun activeKey(o: Output) = if (o == Output.SPEAKER) "active" else "hp_active"

    fun activeSlot(o: Output): Int = prefs.getInt(activeKey(o), 0).coerceIn(0, SLOTS - 1)

    fun setActiveSlot(o: Output, slot: Int) {
        prefs.edit().putInt(activeKey(o), slot).apply()
    }

    /** true = el usuario quiere el ecualizador encendido (se usa al abrir la app y al arrancar el telefono). */
    var serviceWanted: Boolean
        get() = prefs.getBoolean("service_wanted", true)
        set(v) { prefs.edit().putBoolean("service_wanted", v).apply() }

    // ---- Volumen dinamico (vale para las dos salidas) ----
    var dynamicEnabled: Boolean
        get() = prefs.getBoolean("dyn_on", false)
        set(v) { prefs.edit().putBoolean("dyn_on", v).apply() }

    /** Por debajo de este volumen (%) suena el Perfil 1. */
    var dynLow: Int
        get() = prefs.getInt("dyn_low", 30).coerceIn(1, 98)
        set(v) { prefs.edit().putInt("dyn_low", v).apply() }

    /** Desde este volumen (%) suena el Perfil 3; entre los dos umbrales, el Perfil 2. */
    var dynHigh: Int
        get() = prefs.getInt("dyn_high", 70).coerceIn(2, 99)
        set(v) { prefs.edit().putInt("dyn_high", v).apply() }

    // ---- Copia de seguridad completa ----
    fun createBackup(): Backup = Backup(
        speaker = List(SLOTS) { profile(Output.SPEAKER, it) },
        headphones = List(SLOTS) { profile(Output.HEADPHONES, it) },
        activeSpeaker = activeSlot(Output.SPEAKER),
        activeHeadphones = activeSlot(Output.HEADPHONES),
        dynamicEnabled = dynamicEnabled,
        dynLow = dynLow,
        dynHigh = dynHigh
    )

    /** Reemplaza TODOS los perfiles y los ajustes de volumen dinamico por los de la copia. */
    fun restoreBackup(b: Backup) {
        val e = prefs.edit()
        for (i in 0 until SLOTS) {
            e.putString(slotKey(Output.SPEAKER, i), b.speaker[i].toJson().toString())
            e.putString(slotKey(Output.HEADPHONES, i), b.headphones[i].toJson().toString())
        }
        e.putInt(activeKey(Output.SPEAKER), b.activeSpeaker.coerceIn(0, SLOTS - 1))
        e.putInt(activeKey(Output.HEADPHONES), b.activeHeadphones.coerceIn(0, SLOTS - 1))
        e.putBoolean("dyn_on", b.dynamicEnabled)
        e.putInt("dyn_low", b.dynLow)
        e.putInt("dyn_high", b.dynHigh)
        e.apply()
    }

    /** Altavoz: perfil de Echo. Auriculares: plano (sin EQ), porque un EQ pensado para el parlante suele sobrar ahi. */
    fun defaultFor(o: Output, slot: Int): Profile {
        val base = Profile.default(slot)
        return if (o == Output.SPEAKER) base
        else base.copy(
            gains = List(10) { 0f },
            bassOn = false, bassPct = 0f,
            loudOn = false, loudPct = 0f
        )
    }

    fun profile(o: Output, slot: Int): Profile {
        val raw = prefs.getString(slotKey(o, slot), null)
        if (raw != null) {
            try { return Profile.fromJson(JSONObject(raw)) } catch (_: Throwable) {}
        }
        return defaultFor(o, slot)
    }

    fun saveProfile(o: Output, slot: Int, p: Profile) {
        prefs.edit().putString(slotKey(o, slot), p.toJson().toString()).apply()
    }
}