package com.jony.resona

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lo que puede traer un archivo de Resona: un perfil suelto o una copia de seguridad completa. */
sealed class Incoming {
    data class One(val profile: Profile) : Incoming()
    data class Full(val backup: Backup) : Incoming()
}

/** Lo recibido (archivo, texto o "abrir con") que espera la confirmacion del usuario. */
object ImportInbox {
    var pending by mutableStateOf<Profile?>(null)
    var pendingBackup by mutableStateOf<Backup?>(null)
    var message by mutableStateOf<String?>(null)

    fun offer(i: Incoming) {
        when (i) {
            is Incoming.One -> { pendingBackup = null; pending = i.profile }
            is Incoming.Full -> { pending = null; pendingBackup = i.backup }
        }
    }
}

/** Guardar, leer y compartir perfiles y copias de seguridad como archivo (.resona) o como texto. */
object ProfileIO {
    const val MIME = "application/x-resona-profile"
    private const val MAX_BYTES = 64 * 1024
    private val CODE = Regex("resona:([A-Za-z0-9+/=]+)")

    private fun clean(p: Profile) =
        p.copy(name = p.name.trim().take(40).ifBlank { "Perfil importado" })

    // ---------- leer ----------

    /** Perfil suelto: acepta el codigo "resona:..." (de un mensaje) o el JSON del archivo. */
    fun parse(text: String): Profile? {
        return try {
            val t = text.trim()
            val m = CODE.find(t)
            val json = if (m != null) String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8) else t
            val o = JSONObject(json)
            if (o.optString("format") != "resona_profile") null else clean(Profile.fromJson(o))
        } catch (_: Throwable) {
            null
        }
    }

    /** Archivo de Resona: detecta si es un perfil suelto o una copia completa. */
    fun parseIncoming(text: String): Incoming? {
        return try {
            val o = JSONObject(text.trim())
            when (o.optString("format")) {
                "resona_backup" -> Incoming.Full(Backup.fromJson(o))
                "resona_profile" -> Incoming.One(clean(Profile.fromJson(o)))
                else -> null
            }
        } catch (_: Throwable) {
            parse(text)?.let { Incoming.One(it) }
        }
    }

    fun readUri(ctx: Context, uri: Uri): Incoming? = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(MAX_BYTES + 1)
            var total = 0
            while (total < buf.size) {
                val n = s.read(buf, total, buf.size - total)
                if (n <= 0) break
                total += n
            }
            if (total > MAX_BYTES) null else parseIncoming(String(buf, 0, total, Charsets.UTF_8))
        }
    } catch (_: Throwable) {
        null
    }

    fun fromClipboard(ctx: Context): Profile? = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (text.isNullOrBlank()) null else parse(text)
    } catch (_: Throwable) {
        null
    }

    // ---------- textos y nombres ----------

    fun profileText(p: Profile): String = p.toJson().toString(2)
    fun backupText(b: Backup): String = b.toJson().toString(2)

    fun fileName(p: Profile): String {
        val base = p.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(30)
        return base.ifBlank { "perfil" } + ".resona"
    }

    fun backupFileName(): String =
        "Resona-copia-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".resona"

    private fun toShareText(p: Profile): String {
        val code = Base64.encodeToString(p.toJson().toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Perfil de Resona: ${p.name}\n" +
                "Para usarlo: copia este mensaje, abre Resona y toca \"Pegar perfil\".\n" +
                "resona:$code"
    }

    // ---------- escribir ----------

    fun writeText(ctx: Context, uri: Uri, text: String): Boolean = try {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        true
    } catch (_: Throwable) {
        false
    }

    // ---------- compartir ----------

    private fun shareJson(ctx: Context, fileName: String, json: String, subject: String) {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, fileName)
        f.writeText(json, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Compartir"))
    }

    /** Comparte un perfil como archivo .resona con el menu de Android (Bluetooth, Quick Share, WhatsApp...). */
    fun shareFile(ctx: Context, p: Profile) =
        shareJson(ctx, fileName(p), profileText(p), "Perfil de Resona: ${p.name}")

    /** Comparte la copia de seguridad completa como archivo (por ejemplo a Drive o a otro chat). */
    fun shareBackup(ctx: Context, b: Backup) =
        shareJson(ctx, backupFileName(), backupText(b), "Copia de seguridad de Resona")

    /** Comparte un perfil como texto (un mensaje con un codigo que se pega en Resona). */
    fun shareText(ctx: Context, p: Profile) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, toShareText(p))
        }
        ctx.startActivity(Intent.createChooser(send, "Compartir perfil"))
    }
}