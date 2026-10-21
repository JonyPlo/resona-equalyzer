package com.jony.resona

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Pregunta que hacer con un perfil recibido (archivo, texto pegado o "abrir con"). */
@Composable
fun ImportDialog() {
    val p = ImportInbox.pending
    if (p != null) ImportDialogContent(p)
    val b = ImportInbox.pendingBackup
    if (b != null) BackupDialogContent(b)
}

/** Confirmacion antes de reemplazar toda la configuracion con una copia de seguridad. */
@Composable
private fun BackupDialogContent(b: Backup) {
    val context = LocalContext.current
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        onDismissRequest = { ImportInbox.pendingBackup = null },
        title = { Text("Restaurar copia de seguridad") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Esto reemplaza TODOS tus perfiles y ajustes actuales por los de la copia.",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Altavoz: " + b.speaker.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Auriculares: " + b.headphones.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Volumen dinámico: " + (if (b.dynamicEnabled) "encendido" else "apagado") +
                            " (${b.dynLow}% / ${b.dynHigh}%)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                AudioEffectsEngine.restoreBackup(b)
                ImportInbox.pendingBackup = null
                Toast.makeText(context, "Copia restaurada", Toast.LENGTH_SHORT).show()
            }) { Text("Restaurar todo") }
        },
        dismissButton = {
            TextButton(onClick = { ImportInbox.pendingBackup = null }) { Text("Cancelar") }
        }
    )
}

@Composable
private fun ImportDialogContent(p: Profile) {
    val context = LocalContext.current
    var out by remember(p) { mutableStateOf(AudioEffectsEngine.editOutput) }
    var slot by remember(p) { mutableIntStateOf(AudioEffectsEngine.editSlot) }
    val slotNames = remember(out, p) { List(ProfileStore.SLOTS) { ProfileStore.profile(out, it).name } }

    fun finish(apply: Boolean) {
        AudioEffectsEngine.saveImported(out, slot, p, apply)
        ImportInbox.pending = null
        Toast.makeText(context, "Perfil guardado en ${out.label}, perfil ${slot + 1}", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        onDismissRequest = { ImportInbox.pending = null },
        title = { Text("Perfil recibido") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("\"${p.name}\"", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Bandas (dB): " + p.gains.joinToString(" ") { "%+.1f".format(it) },
                    style = MaterialTheme.typography.bodySmall
                )
                val bass = if (p.bassOn) "${p.bassPct.roundToInt()}%" else "apagado"
                val loud = if (p.loudOn) "${p.loudPct.roundToInt()}%" else "apagado"
                Text("Bass: $bass - Loudness: $loud", style = MaterialTheme.typography.bodySmall)

                HorizontalDivider()
                Text("Guardar en:", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (o in Output.values()) {
                        val select = { out = o }
                        if (out == o) Button(onClick = select) { Text(o.label) }
                        else OutlinedButton(onClick = select) { Text(o.label) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 0 until ProfileStore.SLOTS) {
                        val select = { slot = i }
                        val m = Modifier.weight(1f)
                        val pad = PaddingValues(horizontal = 4.dp)
                        if (slot == i) Button(onClick = select, modifier = m, contentPadding = pad) {
                            Text("${i + 1}", maxLines = 1)
                        } else OutlinedButton(onClick = select, modifier = m, contentPadding = pad) {
                            Text("${i + 1}", maxLines = 1)
                        }
                    }
                }
                Text(
                    "Reemplaza a: \"${slotNames[slot]}\"",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (ProfileStore.dynamicEnabled) {
                    Text(
                        "Con el volumen dinámico encendido, ese perfil suena cuando el volumen esté en su rango.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = { finish(true) }) { Text("Guardar y aplicar") }
                TextButton(onClick = { finish(false) }) { Text("Solo guardar") }
            }
        },
        dismissButton = {
            TextButton(onClick = { ImportInbox.pending = null }) { Text("Cancelar") }
        }
    )
}