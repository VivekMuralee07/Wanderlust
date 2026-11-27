package com.wanderlust.app.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Copies user-picked files (tickets, photos) into private app storage so they never disappear. */
class FileStore(private val context: Context) {

    data class Stored(val path: String, val name: String, val mimeType: String)

    suspend fun import(uri: Uri, folder: String): Stored? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = displayName(uri) ?: "file"
            val ext = when {
                mime == "application/pdf" -> "pdf"
                mime.startsWith("image/") -> mime.substringAfter('/').substringBefore('+').ifBlank { "jpg" }
                else -> name.substringAfterLast('.', "bin")
            }
            val dir = File(context.filesDir, folder).apply { mkdirs() }
            val out = File(dir, "${UUID.randomUUID()}.$ext")
            resolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
            Stored(out.absolutePath, name, mime)
        }.getOrNull()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun uriFor(path: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))

    fun open(context: Context, path: String, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(path), mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun readBytes(path: String): ByteArray = File(path).readBytes()

    fun exportsDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }
}
