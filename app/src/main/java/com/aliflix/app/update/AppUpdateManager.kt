package com.aliflix.app.update

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import com.aliflix.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
        runCatching {
            val updateDirectory = File(activity.cacheDir, UPDATE_DIRECTORY).apply {
                mkdirs()
            }
            val partial = File(updateDirectory, "$UPDATE_FILE_NAME.part")
            val destination = File(updateDirectory, UPDATE_FILE_NAME)
            partial.delete()

            val connection = openConnection(info.apkUrl)
            try {
                val totalBytes = connection.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                var copiedBytes = 0L
                var lastProgress = -1
                connection.inputStream.buffered().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copiedBytes += count
                            if (totalBytes > 0) {
                                val progress = ((copiedBytes * 100L) / totalBytes)
                                    .toInt()
                                    .coerceIn(0, 100)
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    withContext(Dispatchers.Main.immediate) {
                                        onProgress(progress)
                                    }
                                }
                            }
                        }
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                check(actualHash.equals(info.sha256, ignoreCase = true)) {
                    "The downloaded APK failed its integrity check."
                }
                destination.delete()
                check(partial.renameTo(destination)) {
                    "The downloaded APK could not be prepared."
                }
                withContext(Dispatchers.Main.immediate) {
                    onProgress(100)
                }
                destination
            } catch (error: Exception) {
                partial.delete()
                throw error
            } finally {
                connection.disconnect()
            }
        }
    }

    fun launchInstaller(apk: File): InstallLaunchResult {
        if (!apk.isFile) return InstallLaunchResult.FAILED
        if (!activity.packageManager.canRequestPackageInstalls()) {
            val opened = runCatching {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:${activity.packageName}"),
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
            connection.inputStream.bufferedReader().use { reader ->
                val text = reader.readText()
                check(text.toByteArray().size <= MAX_MANIFEST_BYTES) {
                    "The update manifest is too large."
                }
                text
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json, application/octet-stream")
        val formFactor = if (BuildConfig.IS_TV) "TV" else "Mobile"
        connection.setRequestProperty(
            "User-Agent",
            "Aliflix-$formFactor/${BuildConfig.VERSION_NAME}",
        )
        connection.connect()
        check(connection.responseCode in 200..299) {
            "The update server returned HTTP ${connection.responseCode}."
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
