package com.wanderlust.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.wanderlust.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume

/** A published release that is newer than the installed app. */
data class AvailableUpdate(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateState
    data class ReadyToInstall(val update: AvailableUpdate, val apk: File) : UpdateState
    /** Installed from Google Play: Play's own in-app update flow is used instead. */
    data object PlayUpdateAvailable : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * In-app updates.
 *
 * - Installed from Google Play → Google Play In-App Updates (flexible flow).
 * - Sideloaded → checks the latest GitHub Release of [BuildConfig.UPDATE_REPO], downloads its APK,
 *   verifies it is the same app signed with the same key and a higher version, then opens the
 *   system installer. Android always asks the user to confirm.
 */
class AppUpdater(private val context: Context) {

    private val prefs = context.getSharedPreferences("wanderlust_updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME
    val isDebugBuild: Boolean = BuildConfig.DEBUG

    var autoCheck: Boolean
        get() = prefs.getBoolean("auto_check", true)
        set(v) { prefs.edit().putBoolean("auto_check", v).apply() }

    /** Versions the user chose to skip from the home banner. */
    private var dismissedVersion: String?
        get() = prefs.getString("dismissed", null)
        set(v) { prefs.edit().putString("dismissed", v).apply() }

    val installedFromPlay: Boolean
        get() = runCatching {
            val installer = if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
            installer == "com.android.vending"
        }.getOrDefault(false)

    /** Called on app start: checks at most once every 12 hours, quietly. */
    suspend fun checkInBackground() {
        if (!autoCheck || isDebugBuild) return
        val last = prefs.getLong("last_check", 0)
        if (System.currentTimeMillis() - last < 12 * 3600_000L) return
        check(quiet = true)
    }

    fun shouldShowBanner(s: UpdateState) = when (s) {
        is UpdateState.Available -> s.update.versionName != dismissedVersion
        is UpdateState.PlayUpdateAvailable, is UpdateState.ReadyToInstall, is UpdateState.Downloading -> true
        else -> false
    }

    fun dismissBanner() {
        (state.value as? UpdateState.Available)?.let { dismissedVersion = it.update.versionName }
        _state.value = UpdateState.Idle
    }

    suspend fun check(quiet: Boolean = false) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        _state.value = try {
            if (installedFromPlay) checkPlay() else checkGitHub()
        } catch (e: Exception) {
            if (quiet) UpdateState.Idle else UpdateState.Error(e.message ?: "Couldn't check for updates")
        }
    }

    // ---------------------------------------------------------------------------------------- Play

    private suspend fun checkPlay(): UpdateState = suspendCancellableCoroutine { cont ->
        AppUpdateManagerFactory.create(context).appUpdateInfo
            .addOnSuccessListener { info ->
                val ok = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                cont.resume(if (ok) UpdateState.PlayUpdateAvailable else UpdateState.UpToDate)
            }
            .addOnFailureListener { cont.resume(UpdateState.Error("Google Play: ${it.message}")) }
    }

    /** Starts Play's flexible update; when downloaded, Play prompts to restart. */
    fun startPlayUpdate(activity: Activity) {
        val manager = AppUpdateManagerFactory.create(activity)
        manager.registerListener { st ->
            if (st.installStatus() == com.google.android.play.core.install.model.InstallStatus.DOWNLOADED) manager.completeUpdate()
        }
        manager.appUpdateInfo.addOnSuccessListener { info ->
            manager.startUpdateFlow(info, activity, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
        }
    }

    // -------------------------------------------------------------------------------------- GitHub

    private suspend fun checkGitHub(): UpdateState = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Wanderlust-Android")
            connectTimeout = 15_000; readTimeout = 15_000
        }
        try {
            when (conn.responseCode) {
                404 -> return@withContext UpdateState.UpToDate // no release published yet
                403, 429 -> throw IOException("GitHub rate limit reached — try again later")
                !in 200..299 -> throw IOException("GitHub returned ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val version = json.getString("tag_name").removePrefix("v")
            if (compareVersions(version, currentVersion) <= 0) return@withContext UpdateState.UpToDate
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?: throw IOException("Release $version has no APK attached")
            UpdateState.Available(
                AvailableUpdate(
                    versionName = version,
                    notes = json.optString("body").trim(),
                    apkUrl = apk.getString("browser_download_url"),
                    apkSize = apk.optLong("size"),
                    pageUrl = json.optString("html_url"),
                )
            )
        } finally {
            conn.disconnect()
        }
    }

    suspend fun download(update: AvailableUpdate) {
        _state.value = UpdateState.Downloading(update, 0f)
        _state.value = try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "Wanderlust-${update.versionName}.apk")
                val conn = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Wanderlust-Android")
                    connectTimeout = 20_000; readTimeout = 30_000
                }
                try {
                    if (conn.responseCode !in 200..299) throw IOException("Download failed (${conn.responseCode})")
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.apkSize
                    conn.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastEmit = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0 && done - lastEmit > 256 * 1024) {
                                    lastEmit = done
                                    _state.value = UpdateState.Downloading(update, done.toFloat() / total)
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                verify(out)
                out
            }
            UpdateState.ReadyToInstall(update, file)
        } catch (e: Exception) {
            UpdateState.Error(e.message ?: "Download failed")
        }
    }

    /** Refuses APKs that aren't this app, aren't newer, or are signed with a different key. */
    private fun verify(apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: run { apk.delete(); throw IOException("The downloaded file isn't a valid APK") }
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun fail(msg: String): Nothing { apk.delete(); throw IOException(msg) }
        if (archive.packageName != context.packageName) fail("The update is for a different app")
        if (archive.longVersionCodeCompat() <= installed.longVersionCodeCompat()) fail("The download isn't newer than this version")
        if (signatureDigests(archive) != signatureDigests(installed)) {
            fail("The update is signed with a different key than this install. Uninstall this copy once and install the release APK from GitHub.")
        }
    }

    private fun PackageInfo.longVersionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    private fun signatureDigests(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        else @Suppress("DEPRECATION") info.signatures?.toList().orEmpty()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** True if Android lets Wanderlust open the package installer (the "Install unknown apps" setting). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
    }

    fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }

    fun reset() { _state.value = UpdateState.Idle }

    companion object {
        /** Compares dotted versions like "1.10.0" vs "1.9.2"; ignores suffixes like "-beta". */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pa = parts(a); val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
