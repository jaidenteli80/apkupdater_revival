package com.apkupdater.util

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import com.apkupdater.data.ui.AppInstallStatus
import com.apkupdater.data.ui.AppInstallProgress
import com.apkupdater.prefs.Prefs
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

class SessionInstaller(
    private val context: Context,
    private val installLog: InstallLog,
    private val prefs: Prefs,
) {
    companion object {
        const val INSTALL_ACTION = "com.apkupdater.INSTALL_ACTION"
    }

    init {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { try { installer.abandonSession(it.sessionId) } catch (_: Exception) {} }
    }

    fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallSettings() {
        if (Build.VERSION.SDK_INT >= 26) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun finish() {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { try { installer.abandonSession(it.sessionId) } catch (_: Exception) {} }
    }

    suspend fun installXapk(id: Int, packageName: String, stream: InputStream) {
        install(id, packageName, stream)
    }

    suspend fun install(id: Int, packageName: String, stream: InputStream) {
        val tempFile = File(getTempDir(), "install_${id}_${System.currentTimeMillis()}.tmp")
        try {
            withContext(Dispatchers.IO) {
                copyWithProgress(id, stream, tempFile)
            }
            install(id, packageName, tempFile)
        } catch (e: Exception) {
            Log.e("SessionInstaller", "Install failed", e)
            installLog.emitStatus(AppInstallStatus(success = false, id = id, snack = true, errorMessage = "Install Error: ${e.message}"))
        } finally {
            tempFile.delete()
        }
    }

    fun getContext() = context

    suspend fun install(id: Int, packageName: String, file: File) {
        if (isZipWithApks(file)) {
            installLog.log("Detected Bundle/XAPK for $packageName")
            installXapkFile(id, packageName, file)
        } else {
            install(id, packageName, listOf(file))
        }
    }

    private fun isZipWithApks(file: File): Boolean {
        return try {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().any {
                    it.name.endsWith(".apk", ignoreCase = true) && !it.isDirectory
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun installXapkFile(id: Int, packageName: String, file: File) {
        installLog.log("Extracting Bundle/XAPK for $packageName")
        val tempDir = File(getTempDir(), "xapk_ext_${id}_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val apks = mutableListOf<File>()
        try {
            withContext(Dispatchers.IO) {
                ZipFile(file).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.endsWith(".apk", ignoreCase = true) && !it.isDirectory }
                        .forEach { entry ->
                            val name = entry.name.substringAfterLast("/")
                            val tempFile = File(tempDir, name)
                            zip.getInputStream(entry).use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            apks.add(tempFile)
                        }
                }
            }

            if (apks.isEmpty()) {
                throw Exception("No APKs found in bundle")
            }

            apks.sortByDescending { it.name.contains("base", ignoreCase = true) || it.length() > 5 * 1024 * 1024 }

            install(id, packageName, apks)

        } catch (e: Exception) {
            Log.e("SessionInstaller", "XAPK extraction failed", e)
            installLog.log("Bundle error for $packageName: ${e.message}")
            installLog.emitStatus(AppInstallStatus(success = false, id = id, snack = true, errorMessage = "Bundle Error: ${e.message}"))
            tempDir.deleteRecursively()
        }
    }

    suspend fun install(id: Int, packageName: String, files: List<File>) {
        val rootEnabled = prefs.rootInstall.get() && withContext(Dispatchers.IO) { Shell.getShell().isRoot }

        if (rootEnabled) {
            installLog.log("Using Root installer for $packageName")
            runRootInstallFiles(id, packageName, files)
        } else {
            installLog.log("Using PackageInstaller for $packageName")
            installNew(id, packageName, files)
        }
    }

    private fun copyWithProgress(id: Int, input: InputStream, output: File) {
        val buffer = ByteArray(128 * 1024)
        var totalRead = 0L
        var lastEmitted = 0L
        input.use { inStream ->
            output.outputStream().use { outStream ->
                var bytes = inStream.read(buffer)
                while (bytes >= 0) {
                    outStream.write(buffer, 0, bytes)
                    totalRead += bytes
                    if ((totalRead - lastEmitted) > 512 * 1024) {
                        installLog.emitProgress(AppInstallProgress(id, totalRead))
                        lastEmitted = totalRead
                    }
                    bytes = inStream.read(buffer)
                }
                installLog.emitProgress(AppInstallProgress(id, totalRead))
                outStream.flush()
            }
        }
        if (totalRead == 0L) throw Exception("Empty stream")
    }

    private suspend fun runRootInstallFiles(id: Int, packageName: String, files: List<File>) = withContext(Dispatchers.IO) {
        val tmpFiles = mutableListOf<String>()
        try {
            files.forEach { file ->
                val tmpPath = "/data/local/tmp/${file.name}"
                Shell.cmd("rm -f '$tmpPath'").exec()
                Shell.cmd("cp '${file.absolutePath}' '$tmpPath' || cat '${file.absolutePath}' > '$tmpPath'").exec()
                Shell.cmd("chmod 666 '$tmpPath'").exec()
                tmpFiles.add(tmpPath)
            }

            val flags = "-r -d -t"
            val bypassFlag = if (Build.VERSION.SDK_INT >= 34) " --bypass-low-target-sdk-block" else ""

            installLog.log("Root: Installing $packageName (${files.size} files)")

            val res = if (files.size == 1) {
                val r = Shell.cmd("pm install $flags$bypassFlag '${tmpFiles[0]}'").exec()
                if (r.isSuccess) r else {
                    runSessionRootInstall(tmpFiles, bypassFlag)
                }
            } else {
                runSessionRootInstall(tmpFiles, bypassFlag)
            }

            if (!res.isSuccess) {
                val lastError = (res.out + res.err).joinToString("\n")
                installLog.log("Root Error: $packageName - $lastError")

                var friendlyError = lastError
                if (lastError.contains("REJECTED_BY_BUILDTYPE") || lastError.contains("UPDATE_INCOMPATIBLE") || lastError.contains("signatures do not match") || lastError.contains("-3001")) {
                    friendlyError = "Signature Mismatch: Update signature does not match installed version. Uninstall the app first to update."
                } else if (lastError.contains("INSTALL_FAILED_MISSING_SHARED_LIBRARY")) {
                    friendlyError = "Missing shared library (e.g. TrichromeLibrary)"
                } else if (lastError.contains("is a persistent app")) {
                    friendlyError = "Persistent system apps cannot be updated this way"
                } else if (lastError.contains("VERIFICATION_FAILURE") || lastError.contains("BLOCKED")) {
                    friendlyError = "Blocked by Google Play Protect. Tap 'More details' -> 'Install anyway' in system popup."
                }

                installLog.emitStatus(AppInstallStatus(success = false, id = id, snack = true, errorMessage = "Root failed: $friendlyError"))
            } else {
                installLog.log("Root: $packageName success")
                installLog.emitStatus(AppInstallStatus(success = true, id = id, snack = true))
            }
        } catch (e: Exception) {
            installLog.emitStatus(AppInstallStatus(success = false, id = id, snack = true, errorMessage = "Root Crash: ${e.message}"))
        } finally {
            tmpFiles.forEach { Shell.cmd("rm -f '$it'").exec() }
        }
    }

    private fun runSessionRootInstall(tmpFiles: List<String>, bypassFlag: String): Shell.Result {
        val flags = "-r -d -t"
        val createRes = Shell.cmd("pm install-create $flags$bypassFlag").exec()
        val sessionId = createRes.out.firstOrNull { it.contains("Success: created install session [") }
            ?.substringAfter("[")?.substringBefore("]")

        if (sessionId == null) {
            return createRes
        }

        var allWritesOk = true
        tmpFiles.forEachIndexed { index, path ->
            val writeRes = Shell.cmd("pm install-write $sessionId split$index '$path'").exec()
            if (!writeRes.isSuccess) {
                allWritesOk = false
            }
        }

        return if (allWritesOk) {
            Shell.cmd("pm install-commit $sessionId").exec()
        } else {
            Shell.cmd("pm install-abandon $sessionId").exec()
            createRes
        }
    }

    private fun getTempDir(): File {
        val base = context.externalCacheDir ?: context.cacheDir
        return File(base, "installer_temp").apply { mkdirs() }
    }

    @SuppressLint("RequestInstallPackagesPolicy")
    private fun installNew(
        id: Int,
        packageName: String,
        files: List<File>
    ): Boolean {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

        try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            params.setAppLabel(appInfo.loadLabel(context.packageManager))
        } catch (_: Exception) {
            params.setAppLabel(packageName)
        }

        params.setAppPackageName(packageName)
        if (Build.VERSION.SDK_INT >= 33) params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)

        val totalSize = files.sumOf { it.length() }
        params.setSize(totalSize)

        return try {
            val sessionId = packageInstaller.createSession(params)
            installLog.currentInstallId = id
            packageInstaller.openSession(sessionId).use { session ->
                var baseFound = false
                files.forEachIndexed { index, file ->
                    val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                    val isBase = (info != null && (info.packageName == packageName || files.size == 1)) || file.name.contains("base", ignoreCase = true)
                    val name = if (isBase && !baseFound) {
                        baseFound = true
                        "base.apk"
                    } else {
                        "split_$index.apk"
                    }

                    file.inputStream().use { input ->
                        session.openWrite(name, 0, file.length()).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                val broadcastIntent = Intent(INSTALL_ACTION).apply {
                    setPackage(context.packageName)
                    putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                }
                val pending = PendingIntent.getBroadcast(context, sessionId, broadcastIntent, FLAG_UPDATE_CURRENT or FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
            true
        } catch (e: Exception) {
            Log.e("SessionInstaller", "Session failed", e)
            installLog.emitStatus(AppInstallStatus(success = false, id = id, snack = true, errorMessage = "Session failure: ${e.message}"))
            false
        }
    }
}
