package com.jony.resona

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Salida de audio para la que se usa cada conjunto de perfiles. */
enum class Output(val label: String) {
    SPEAKER("Altavoz"),
    HEADPHONES("Auriculares")
}

/**
 * Detecta si hay auriculares conectados (con cable, USB o Bluetooth).
 * Limitacion: Android no distingue un parlante Bluetooth de unos auriculares Bluetooth,
 * asi que ambos cuentan como "Auriculares".
 */
object OutputDetector {

    private fun isHeadphoneType(type: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET) return true
        return when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HEARING_AID -> true
            else -> false
        }
    }

    fun current(am: AudioManager): Output = try {
        if (am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isHeadphoneType(it.type) }) Output.HEADPHONES
        else Output.SPEAKER
    } catch (_: Throwable) {
        Output.SPEAKER
    }

    fun current(context: Context): Output =
        current(context.getSystemService(AudioManager::class.java))
}
