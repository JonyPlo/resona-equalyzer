package com.jony.resona

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Comprobaciones y accesos directos a los ajustes que hacen falta para correr en segundo plano. */
object SetupChecker {
    private const val PREFS = "resona_setup"

    fun isXiaomi(): Boolean = Build.MANUFACTURER.lowercase() in listOf("xiaomi", "redmi", "poco")

    fun notificationsOk(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun batteryOk(ctx: Context): Boolean = try {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Throwable) {
        false
    }

    /**
     * MIUI no tiene API publica para el "Inicio automatico". Esto usa una consulta no oficial
     * (mejor esfuerzo): devuelve null si no se puede saber.
     */
    fun miuiAutostartAllowed(ctx: Context): Boolean? = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val m = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
        )
        (m.invoke(ops, 10008, Process.myUid(), ctx.packageName) as Int) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        null
    }

    // Marcas manuales ("Ya lo hice") para lo que no se puede verificar
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isDone(ctx: Context, key: String) = prefs(ctx).getBoolean(key, false)
    fun setDone(ctx: Context, key: String) = prefs(ctx).edit().putBoolean(key, true).apply()

    // ---- Abrir ajustes ----

    private fun tryStart(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Throwable) {
        false
    }

    fun openAppDetails(ctx: Context) {
        tryStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }

    fun openNotificationSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        if (!tryStart(ctx, i)) openAppDetails(ctx)
    }

    fun openBatterySettings(ctx: Context) {
        if (isXiaomi()) {
            val miui = Intent().setComponent(
                ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            ).putExtra("package_name", ctx.packageName).putExtra("package_label", "Resona")
            if (tryStart(ctx, miui)) return
        }
        val std = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        if (!tryStart(ctx, std)) openAppDetails(ctx)
    }

    fun openAutostartSettings(ctx: Context) {
        val miui = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        if (!tryStart(ctx, miui)) openAppDetails(ctx)
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun SetupRow(
    title: String,
    detail: String,
    state: Boolean?,
    actionLabel: String?,
    onAction: () -> Unit,
    onDone: (() -> Unit)?
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot = when (state) {
                true -> ResonaPalette.Mid
                false -> ResonaPalette.Low
                null -> ResonaPalette.LowMid
            }
            Box(modifier = Modifier.size(9.dp).background(dot, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state != true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (actionLabel != null) ActionButton(actionLabel, onAction, filled = true)
                if (onDone != null) TextButton(onClick = onDone) { Text("Ya lo hice") }
            }
        }
    }
}

/**
 * Avisa de lo que falta para que Resona siga funcionando en segundo plano.
 * No bloquea nada: la app funciona igual, solo que el sistema podria cerrarla.
 */
@Composable
fun SetupCard() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }

    // Releer el estado cada vez que se vuelve a la app (por ejemplo desde los ajustes)
    DisposableEffect(context) {
        val activity = context.findActivity()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) SetupChecker.openNotificationSettings(context)
        tick++
    }

    val xiaomi = SetupChecker.isXiaomi()
    val notifOk = remember(tick) { SetupChecker.notificationsOk(context) }
    val batteryOk = remember(tick) {
        SetupChecker.batteryOk(context) || SetupChecker.isDone(context, "battery")
    }
    val autostart: Boolean? = remember(tick) {
        when {
            !xiaomi -> true
            SetupChecker.isDone(context, "autostart") -> true
            else -> SetupChecker.miuiAutostartAllowed(context)
        }
    }

    val allOk = notifOk && batteryOk && autostart == true
    if (allOk) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(8.dp).background(ResonaPalette.Mid, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                "Segundo plano configurado",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Panel(
        title = "Falta configurar",
        subtitle = "Resona funciona igual, pero el sistema podría cerrarla en segundo plano."
    ) {
        SetupRow(
            title = "Notificaciones",
            detail = "Hacen falta para el aviso permanente que mantiene vivo el servicio.",
            state = notifOk,
            actionLabel = "Permitir",
            onAction = {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else SetupChecker.openNotificationSettings(context)
            },
            onDone = null
        )

        SetupRow(
            title = "Batería sin restricciones",
            detail = "En el ahorro de batería de la app, elegí \"Sin restricciones\".",
            state = batteryOk,
            actionLabel = "Configurar",
            onAction = { SetupChecker.openBatterySettings(context) },
            onDone = { SetupChecker.setDone(context, "battery"); tick++ }
        )

        if (xiaomi) {
            SetupRow(
                title = "Inicio automático (MIUI)",
                detail = "Activá Resona en la lista de inicio automático. " +
                        "Si el estado es dudoso, es que no se puede comprobar desde la app.",
                state = autostart,
                actionLabel = "Abrir ajustes",
                onAction = { SetupChecker.openAutostartSettings(context) },
                onDone = { SetupChecker.setDone(context, "autostart"); tick++ }
            )
        }
    }
}