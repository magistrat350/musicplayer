package com.magistrat.musicplayer.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.magistrat.musicplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val name: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/** Prueft die GitHub-Releases auf eine neuere APK, laedt sie herunter und startet die Installation. */
object Updater {
    private const val REPO = "magistrat350/musicplayer"
    private const val PREFS = "updater"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIPPED = "skipped_version"

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    /** Gefundenes Update, das dem Nutzer angeboten werden soll. */
    val available: StateFlow<UpdateInfo?> = _available

    fun dismiss(context: Context, skip: Boolean) {
        val info = _available.value
        if (skip && info != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SKIPPED, info.versionCode).apply()
        }
        _available.value = null
    }

    /** Automatische Pruefung beim Start, hoechstens alle 6 Stunden. Uebersprungene Versionen werden nicht erneut angeboten. */
    suspend fun checkIfDue(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < 6 * 60 * 60 * 1000L) return
        val info = try {
            check()
        } catch (e: Exception) {
            null
        } ?: return
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        if (info.versionCode != prefs.getInt(KEY_SKIPPED, -1)) _available.value = info
    }

    /** Manuelle Pruefung. Liefert das Update oder null, wenn die App aktuell ist. Wirft bei Netzwerkfehlern. */
    suspend fun checkNow(): UpdateInfo? {
        val info = check()
        _available.value = info
        return info
    }

    private suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" } ?: "arm64-v8a"
        val assetName = "MusicPlayer-$abi.apk"

        val releases = JSONArray(body)
        var best: UpdateInfo? = null
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val code = r.optString("tag_name").removePrefix("build-").toIntOrNull() ?: continue
            if (code <= currentVersionCode || (best != null && code <= best.versionCode)) continue
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name") == assetName) {
                    best = UpdateInfo(
                        versionCode = code,
                        name = r.optString("name").ifBlank { "Build $code" },
                        apkUrl = a.optString("browser_download_url"),
                        sizeBytes = a.optLong("size"),
                    )
                }
            }
        }
        best
    }

    /** Laedt die APK in den Cache. [onProgress] erhaelt Werte von 0 bis 1. */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "MusicPlayer-${info.versionCode}.apk")
        var url = URL(info.apkUrl)
        var conn: HttpURLConnection
        // GitHub leitet auf einen anderen Host um -> Weiterleitungen selbst verfolgen
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                redirects++
                continue
            }
            if (code != 200) {
                conn.disconnect()
                throw IllegalStateException("Download fehlgeschlagen (HTTP $code)")
            }
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
        try {
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastReported = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                withContext(Dispatchers.Main) { onProgress(read.toFloat() / total) }
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        target
    }

    /** Darf die App Pakete installieren? (Ab Android 8 muss das einmal erlaubt werden.) */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
