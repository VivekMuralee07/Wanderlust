package com.wanderlust.app.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wanderlust.app.BuildConfig
import com.wanderlust.app.MainActivity
import com.wanderlust.app.WanderlustApp
import com.wanderlust.app.data.WanderlustDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backups: one .zip with the database, every ticket/attachment, memory photo and trip cover,
 * plus non-secret settings. The API key is never included.
 */
class BackupManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("wanderlust_backup", Context.MODE_PRIVATE)
    private val folders = listOf("attachments", "memories", "covers")

    var autoBackupUri: Uri?
        get() = prefs.getString("uri", null)?.let(Uri::parse)
        private set(v) { prefs.edit().putString("uri", v?.toString()).apply() }

    /** 0 = off, else days between automatic backups. */
    var autoBackupDays: Int
        get() = prefs.getInt("days", 0)
        private set(v) { prefs.edit().putInt("days", v).apply() }

    var lastBackupAt: Long
        get() = prefs.getLong("last", 0)
        private set(v) { prefs.edit().putLong("last", v).apply() }

    var lastError: String?
        get() = prefs.getString("error", null)
        private set(v) { prefs.edit().putString("error", v).apply() }

    fun suggestedName(): String = "Wanderlust-backup-${java.time.LocalDate.now()}.zip"

    // ------------------------------------------------------------------------------------- export

    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write to that location")
        out.use { export(it) }
        lastBackupAt = System.currentTimeMillis()
        lastError = null
    }

    private fun export(stream: OutputStream) {
        val app = (context.applicationContext as WanderlustApp).container
        // flush the write-ahead log so the .db file is complete
        app.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val s = app.settings.state.value
        val manifest = JSONObject()
            .put("format", 1)
            .put("app", "com.wanderlust.app")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("dbVersion", WanderlustDatabase.VERSION)
            .put("filesDir", context.filesDir.absolutePath)
            .put("createdAt", System.currentTimeMillis())
            .put("settings", JSONObject().put("name", s.travelerName).put("home", s.homeCity).put("homeCurrency", s.homeCurrency).put("model", s.model))
        ZipOutputStream(stream.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString(2).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("db/${WanderlustDatabase.NAME}"))
            context.getDatabasePath(WanderlustDatabase.NAME).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            folders.forEach { folder ->
                File(context.filesDir, folder).walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("files/" + f.relativeTo(context.filesDir).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------ restore

    data class BackupInfo(val appVersion: String, val createdAt: Long)

    /**
     * Replaces ALL current data with the backup, then restarts the app. Older backups are upgraded by
     * the database migrations on the next start; backups from a newer app version are refused.
     */
    suspend fun restoreFrom(uri: Uri): BackupInfo = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            (context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open that file")).use { unzip(it, staging) }
            val manifestFile = File(staging, "manifest.json")
            if (!manifestFile.exists()) throw IOException("That isn't a Wanderlust backup")
            val m = JSONObject(manifestFile.readText())
            if (m.optString("app") != "com.wanderlust.app") throw IOException("That isn't a Wanderlust backup")
            if (m.optInt("dbVersion") > WanderlustDatabase.VERSION) throw IOException("This backup is from a newer version of Wanderlust — update the app first")
            val dbFile = File(staging, "db/${WanderlustDatabase.NAME}")
            if (!dbFile.exists()) throw IOException("The backup has no database")

            // remap absolute file paths from the old phone/app to this one
            val oldDir = m.optString("filesDir")
            val newDir = context.filesDir.absolutePath
            if (oldDir.isNotBlank() && oldDir != newDir) {
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    listOf("attachments" to "filePath", "memories" to "filePath", "journal_entries" to "photoPath", "trips" to "coverPath")
                        .forEach { (t, c) -> db.execSQL("UPDATE $t SET $c = replace($c, ?, ?) WHERE $c IS NOT NULL", arrayOf(oldDir, newDir)) }
                    runCatching { db.execSQL("UPDATE expenses SET receiptPath = replace(receiptPath, ?, ?) WHERE receiptPath IS NOT NULL", arrayOf(oldDir, newDir)) }
                }
            }

            // swap in the database and files
            val app = (context.applicationContext as WanderlustApp).container
            app.db.close()
            val target = context.getDatabasePath(WanderlustDatabase.NAME)
            listOf("", "-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
            dbFile.copyTo(target, overwrite = true)
            folders.forEach { folder ->
                File(context.filesDir, folder).deleteRecursively()
                File(staging, "files/$folder").takeIf { it.exists() }?.copyRecursively(File(context.filesDir, folder), overwrite = true)
            }
            m.optJSONObject("settings")?.let { st ->
                app.settings.update {
                    it.copy(
                        travelerName = st.optString("name", it.travelerName), homeCity = st.optString("home", it.homeCity),
                        homeCurrency = st.optString("homeCurrency", it.homeCurrency), model = st.optString("model", it.model),
                    )
                }
            }
            BackupInfo(m.optString("appVersion"), m.optLong("createdAt"))
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun unzip(input: InputStream, dest: File) {
        val root = dest.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(root)) throw IOException("Invalid backup file") // zip-slip guard
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
            }
        }
    }

    /** Restart so the app reopens the restored database. */
    fun restartApp() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }

    // ----------------------------------------------------------------------------- automatic backups

    /** [uri] from ACTION_CREATE_DOCUMENT (e.g. a file in Google Drive); [days] 0 turns it off. */
    fun configureAuto(uri: Uri?, days: Int) {
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            autoBackupUri = uri
        }
        autoBackupDays = days
        val wm = WorkManager.getInstance(context)
        if (days <= 0 || autoBackupUri == null) wm.cancelUniqueWork(WORK)
        else wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(days.toLong(), TimeUnit.DAYS).build())
    }

    suspend fun runAutoBackup(): Boolean {
        val uri = autoBackupUri ?: return false
        return try { exportTo(uri); true } catch (e: Exception) { lastError = e.message ?: "Backup failed"; false }
    }

    companion object { const val WORK = "auto_backup" }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if ((applicationContext as WanderlustApp).container.backups.runAutoBackup()) Result.success() else Result.retry()
}
