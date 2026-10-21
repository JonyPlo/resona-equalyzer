package com.jony.resona

import org.json.JSONArray
import org.json.JSONObject

/** Un perfil completo de ecualizacion. Este mismo JSON se usara para exportar/importar/compartir. */
data class Profile(
    val name: String,
    val gains: List<Float>,      // 10 bandas, en dB (-15..15)
    val bassOn: Boolean,
    val bassPct: Float,          // 0..100
    val loudOn: Boolean,
    val loudPct: Float,          // 0..100
    val limiterOn: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", "resona_profile")
        put("version", 1)
        put("name", name)
        put("bands", JSONArray(gains.map { it.toDouble() }))
        put("bassOn", bassOn)
        put("bassPct", bassPct.toDouble())
        put("loudOn", loudOn)
        put("loudPct", loudPct.toDouble())
        put("limiterOn", limiterOn)
    }

    companion object {
        // Perfil "Esta es la buena" de Echo Equalizer, redondeado a 0.1 dB
        val ECHO_GAINS = listOf(6.0f, 8.0f, 10.0f, 15.0f, 2.0f, 2.0f, -2.0f, -1.0f, 6.5f, 10.0f)

        fun default(slot: Int) =
            Profile("Perfil ${slot + 1}", ECHO_GAINS, true, 50f, true, 5f, false)

        fun fromJson(o: JSONObject): Profile {
            val arr = o.getJSONArray("bands")
            require(arr.length() == 10) { "Se esperaban 10 bandas" }
            return Profile(
                name = o.optString("name", "Perfil"),
                gains = List(10) {
                    arr.getDouble(it).toFloat()
                        .coerceIn(AudioEffectsEngine.MIN_DB, AudioEffectsEngine.MAX_DB)
                },
                bassOn = o.optBoolean("bassOn", true),
                bassPct = o.optDouble("bassPct", 0.0).toFloat().coerceIn(0f, 100f),
                loudOn = o.optBoolean("loudOn", true),
                loudPct = o.optDouble("loudPct", 0.0).toFloat().coerceIn(0f, 100f),
                limiterOn = o.optBoolean("limiterOn", false)
            )
        }
    }
}
