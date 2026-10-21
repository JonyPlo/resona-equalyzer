package com.jony.resona

import org.json.JSONArray
import org.json.JSONObject

/** Copia de seguridad completa: los 6 perfiles (3 de altavoz, 3 de auriculares) y los ajustes de volumen dinamico. */
data class Backup(
    val speaker: List<Profile>,
    val headphones: List<Profile>,
    val activeSpeaker: Int,
    val activeHeadphones: Int,
    val dynamicEnabled: Boolean,
    val dynLow: Int,
    val dynHigh: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", "resona_backup")
        put("version", 1)
        put("speaker", JSONArray(speaker.map { it.toJson() }))
        put("headphones", JSONArray(headphones.map { it.toJson() }))
        put("activeSpeaker", activeSpeaker)
        put("activeHeadphones", activeHeadphones)
        put("dynamicEnabled", dynamicEnabled)
        put("dynLow", dynLow)
        put("dynHigh", dynHigh)
    }

    companion object {
        private fun profiles(arr: JSONArray): List<Profile> {
            require(arr.length() == ProfileStore.SLOTS) { "Se esperaban ${ProfileStore.SLOTS} perfiles" }
            return List(arr.length()) {
                val p = Profile.fromJson(arr.getJSONObject(it))
                p.copy(name = p.name.trim().take(40).ifBlank { "Perfil ${it + 1}" })
            }
        }

        fun fromJson(o: JSONObject): Backup {
            val low = o.optInt("dynLow", 30).coerceIn(1, 98)
            val high = o.optInt("dynHigh", 70).coerceIn(low + 1, 99)
            return Backup(
                speaker = profiles(o.getJSONArray("speaker")),
                headphones = profiles(o.getJSONArray("headphones")),
                activeSpeaker = o.optInt("activeSpeaker", 0).coerceIn(0, ProfileStore.SLOTS - 1),
                activeHeadphones = o.optInt("activeHeadphones", 0).coerceIn(0, ProfileStore.SLOTS - 1),
                dynamicEnabled = o.optBoolean("dynamicEnabled", false),
                dynLow = low,
                dynHigh = high
            )
        }
    }
}
