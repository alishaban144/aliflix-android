package com.aliflix.app.update

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.aliflix.app.BuildConfig
import com.aliflix.app.data.SafeHttpTransport
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val minimumSdk: Int,
    val notes: String,
)

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data class UpToDate(val versionName: String) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

enum class InstallLaunchResult {
    INSTALLER_OPENED,
    PERMISSION_REQUIRED,
    FAILED,
}

object UpdateManifestParser {
    fun parse(json: String): UpdateInfo {
        val value = JSONObject(json)
        val apkUrl = value.getString("apkUrl").trim()
        require(apkUrl.startsWith("https://")) {
            "The update APK must use HTTPS."
        }
        val sha256 = value.getString("sha256").trim().lowercase()
        require(sha256.matches(Regex("[a-f0-9]{64}"))) {
            "The update manifest has an invalid SHA-256 value."
        }
        return UpdateInfo(
            versionCode = value.getInt("versionCode"),
            versionName = value.getString("versionName").trim(),
            apkUrl = apkUrl,
            sha256 = sha256,
            minimumSdk = value.optInt("minimumSdk", 30),
            notes = value.optString("notes").trim(),
        )
    }
}

class AppUpdateManager(
    private val activity: ComponentActivity,
    private val manifestUrl: String = BuildConfig.UPDATE_MANIFEST_URL,
) {
    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (manifestUrl.isBlank()) {
            return@withContext UpdateCheckResult.Error(
                "The GitHub update source has not been configured yet.",
            )
        }
        runCatching {
            val info = UpdateManifestParser.parse(fetchText(manifestUrl))
            when {
                info.minimumSdk > Build.VERSION.SDK_INT -> UpdateCheckResult.Error(
                    "The latest release requires Android API ${info.minimumSdk} or newer.",
                )
                info.versionCode > BuildConfig.VERSION_CODE -> UpdateCheckResult.Available(info)
                else -> UpdateCheckResult.UpToDate(BuildConfig.VERSION_NAME)
            }
        }.getOrElse { error ->
            UpdateCheckResult.Error(
                error.message?.takeIf(String::isNotBlank)
                    ?: "The update check failed.",
            )
        }
    }

    suspend fun download(
        info: UpdateInfo,
        onProgress: suspend (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updateDirectory = File(activity.cacheDir, UPDATE_DIRECTORY).apply { mkdirs() }
            val partial = File(updateDirectory, "${info.sha256.lowercase()}.part")
            val destination = File(updateDirectory, UPDATE_FILE_NAME)
            var completed = false
            var lastFailure: Exception? = null
            var lastProgress = -1
            for (attempt in 0 until 5) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (attempt > 0) kotlinx.coroutines.delay((1_000L shl (attempt - 1)).coerceAtMost(8_000))
                var connection: HttpURLConnection? = null
                try {
                    var offset = partial.length()
                    connection = openConnection(info.apkUrl, offset)
                    if (connection.responseCode == 416) {
                        // The retained file may already be complete; the hash is authoritative.
                        completed = true
                        break
                    }
                    val append = offset > 0 && connection.responseCode == 206 &&
                        connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true
                    if (connection.responseCode == 206 && offset > 0 && !append) {
                        partial.delete()
                        throw java.io.IOException("Invalid download range")
                    }
                    if (!append) offset = 0L
                    val contentLength = connection.contentLengthLong
                    val total = if (contentLength > 0) offset + contentLength else -1L
                    var copied = offset
                    connection.inputStream.buffered(64 * 1024).use { input ->
                        java.io.FileOutputStream(partial, append).buffered(64 * 1024).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                copied += count
                                if (total > 0) {
                                    val progress = ((copied * 100) / total).toInt().coerceIn(0, 99)
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        withContext(Dispatchers.Main.immediate) { onProgress(progress) }
                                    }
                                }
                            }
                        }
                    }
                    if (total > 0 && copied != total) throw java.io.IOException("Download interrupted")
                    completed = true
                    break
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: java.io.IOException) { lastFailure = failure }
                finally { connection?.disconnect() }
            }
            if (!completed) throw lastFailure ?: java.io.IOException("Download interrupted. Retry")
            val digest = MessageDigest.getInstance("SHA-256")
            partial.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(info.sha256, ignoreCase = true)) {
                partial.delete()
                error("Update verification failed. Retry")
            }
            destination.delete()
            check(partial.renameTo(destination)) { "Update could not be prepared" }
            withContext(Dispatchers.Main.immediate) { onProgress(100) }
            Result.success(destination)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    }

    fun launchInstaller(apk: File): InstallLaunchResult {
        if (!apk.isFile) return InstallLaunchResult.FAILED
        if (!activity.packageManager.canRequestPackageInstalls()) {
            val opened = runCatching {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:${activity.packageName}".toUri(),
                    ),
                )
            }.isSuccess
            return if (opened) {
                InstallLaunchResult.PERMISSION_REQUIRED
            } else {
                InstallLaunchResult.FAILED
            }
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.update-files",
            apk,
        )
        val opened = runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                },
            )
        }.isSuccess
        return if (opened) {
            InstallLaunchResult.INSTALLER_OPENED
        } else {
            InstallLaunchResult.FAILED
        }
    }

    private fun fetchText(url: String): String {
        val connection = openConnection(url)
        return try {
            val length = connection.contentLengthLong
            check(length <= MAX_MANIFEST_BYTES || length < 0) {
                "The update manifest is too large."
            }
            val text = SafeHttpTransport.readResponseText(connection)
            check(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_MANIFEST_BYTES) {
                "The update manifest is too large."
            }
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, offset: Long = 0): HttpURLConnection {
        val formFactor = if (BuildConfig.IS_TV) "TV" else "Mobile"
        val extraHeaders = mutableMapOf(
            "Accept" to "application/json, application/octet-stream",
            "User-Agent" to "Aliflix-$formFactor/${BuildConfig.VERSION_NAME}",
        )
        extraHeaders["Accept-Encoding"] = "identity"
        if (offset > 0) extraHeaders["Range"] = "bytes=$offset-"
        val connection = SafeHttpTransport.openConnection(
            urlString = url,
            connectTimeoutMs = 20_000,
            readTimeoutMs = 90_000,
            headers = extraHeaders,
        )
        val status = connection.responseCode
        if (status !in 200..299 && !(offset > 0 && status == 416)) {
            connection.disconnect()
            throw java.io.IOException("Update server: $status")
        }
        return connection
    }

    private companion object {
        const val UPDATE_DIRECTORY = "updates"
        val UPDATE_FILE_NAME =
            if (BuildConfig.IS_TV) "aliflix-tv-update.apk" else "aliflix-mobile-update.apk"
        const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}
