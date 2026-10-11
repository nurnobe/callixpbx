package com.callix.pbx.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.callix.pbx.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Release updater for the project's public GitHub Releases page.
 *
 * GitHub is not Google Play, so Android must still display its own final package
 * installer confirmation. That confirmation is intentionally not bypassable. This
 * manager needs no storage permission and asks for "install unknown apps" only once
 * per device/source when Android requires it.
 */
object GithubUpdateManager {
    private const val RELEASE_URL = "https://api.github.com/repos/nurnobe/callixpbx/releases/latest"
    private val client = OkHttpClient()

    data class Release(val versionName: String, val versionCode: Int, val apkUrl: String)

    suspend fun latestUpdate(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CallixPBX-Android/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val root = JSONObject(response.body?.string().orEmpty())
            if (root.optBoolean("prerelease") || root.optBoolean("draft")) return@withContext null
            val tag = root.optString("tag_name").trim()
            val code = versionCode(tag) ?: return@withContext null
            if (code <= BuildConfig.VERSION_CODE) return@withContext null
            val assets = root.optJSONArray("assets") ?: return@withContext null
            var selected: JSONObject? = null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name").lowercase()
                if (!name.endsWith(".apk")) continue
                if (selected == null || name.contains("universal")) selected = asset
            }
            val apk = selected?.optString("browser_download_url").orEmpty()
            if (apk.isBlank()) null else Release(tag.removePrefix("v"), code, apk)
        }
    }

    suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val updates = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(updates, "CallixPBX-${release.versionName}.apk")
        val temporary = File(updates, "CallixPBX-${release.versionName}.part")
        target.delete()
        temporary.delete()
        val request = Request.Builder().url(release.apkUrl)
            .header("User-Agent", "CallixPBX-Android/${BuildConfig.VERSION_NAME}").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Update download failed (${response.code})")
            response.body?.byteStream()?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Update file was empty")
        }
        if (!temporary.renameTo(target)) error("Could not save update")
        target
    }

    fun startInstall(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }

    private fun versionCode(tag: String): Int? {
        val parts = tag.removePrefix("v").trim().split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return major * 100 + minor
    }
}
