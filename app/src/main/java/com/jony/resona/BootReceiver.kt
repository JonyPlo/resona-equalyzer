package com.jony.resona

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Arranca el servicio al prender el telefono, si el usuario lo tenia encendido. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ProfileStore.init(context)
        DebugLog.init(context)
        DebugLog.add("Arranque del telefono recibido (${intent.action}), serviceWanted=${ProfileStore.serviceWanted}")
        if (ProfileStore.serviceWanted) {
            ContextCompat.startForegroundService(context, Intent(context, EqService::class.java))
        }
    }
}