package com.apkupdater.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apkupdater.R
import com.apkupdater.data.ui.AppInstallProgress
import com.apkupdater.data.ui.AppInstallStatus
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Link
import com.apkupdater.prefs.Prefs
import com.apkupdater.util.Downloader
import com.apkupdater.util.InstallLog
import com.apkupdater.util.SessionInstaller
import com.apkupdater.util.SnackBar
import com.apkupdater.util.Stringer
import com.apkupdater.util.UpdatesNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.ConcurrentHashMap

import com.apkupdater.BuildConfig

sealed class InstallDialogState {
    data object Idle : InstallDialogState()
    data class PersistentApp(val appName: String) : InstallDialogState()
    data object PermissionRequired : InstallDialogState()
    data object PermissionDenied : InstallDialogState()
    data class SelfUpdateRequired(val selfUpdate: AppUpdate) : InstallDialogState()
    data class GenericError(val title: String, val message: String) : InstallDialogState()
}


abstract class InstallViewModel(
    protected val downloader: Downloader,
    protected val installer: SessionInstaller,
    protected val prefs: Prefs,
    protected val snackBar: SnackBar,
    protected val stringer: Stringer,
    protected val installLog: InstallLog,
    protected val notification: UpdatesNotification,
): ViewModel() {

    private var lastProgressUpdate = 0L
    private val maxProgressMap = mutableMapOf<Int, Int>()
    protected val installJobs = ConcurrentHashMap<Int, Job>()
    protected val installNames = ConcurrentHashMap<Int, String>()

    protected val _dialogState = MutableStateFlow<InstallDialogState>(InstallDialogState.Idle)
    val dialogState = _dialogState.asStateFlow()

    private var waitingForPermission = false

    fun dismissDialog() {
        _dialogState.value = InstallDialogState.Idle
    }

    fun openPermissionSettings() {
        _dialogState.value = InstallDialogState.Idle
        waitingForPermission = true
        installer.openInstallSettings()
    }

    fun checkPermissionOnResume() {
        if (waitingForPermission) {
            waitingForPermission = false
            if (!prefs.rootInstall.get() && !installer.checkPermission()) {
                _dialogState.value = InstallDialogState.PermissionDenied
            }
        }
    }
    fun cleanErrorMessage(rawMessage: String?): String {
        if (rawMessage.isNullOrBlank()) return "An error occurred during installation. Please try again."
        val msg = rawMessage.trim()

        return when {
            msg.contains("StandaloneCoroutine", true) || msg.contains("cancelled", true) || msg.contains("JobCancellationException", true) ->
                "The installation was stopped or cancelled."

            msg.contains("UPDATE_INCOMPATIBLE", true) || msg.contains("signatures do not match", true) || msg.contains("REJECTED_BY_BUILDTYPE", true) || msg.contains("-3001") ->
                "Signature Mismatch: Update signature does not match installed version. Please uninstall the app first to update."

            msg.contains("VERIFICATION_FAILURE", true) || msg.contains("BLOCKED", true) ->
                "Blocked by Google Play Protect. Tap 'More details' -> 'Install anyway' in the system popup."

            msg.contains("PARSE_FAILED_NOT_APK", true) || msg.contains("not a valid APK", true) ->
                "The downloaded file is corrupted or not a valid APK. Please try another source."

            msg.contains("MISSING_SHARED_LIBRARY", true) ->
                "Missing required system library for this application."

            msg.contains("persistent", true) ->
                "This is a persistent system app and cannot be updated directly."

            msg.contains("Empty stream", true) || msg.contains("No APKs found", true) ->
                "The update file appears to be empty or corrupted. Please try refreshing."

            msg.contains("Session failure", true) || msg.contains("Root Crash", true) ->
                "The installer session encountered an error. Please try again."

            else -> {
                val cleaned = msg.replace(Regex("^(Install Error|Root Error|Session failure|Root failed|java\\.lang\\.\\w+Exception):\\s*", RegexOption.IGNORE_CASE), "")
                if (cleaned.isBlank()) "An error occurred during installation. Please try again." else cleaned
            }
        }
    }

    fun checkSelfUpdateRequired(update: AppUpdate, availableUpdates: List<AppUpdate>): Boolean {
        if (update.packageName == BuildConfig.APPLICATION_ID) return false
        val selfUpdate = availableUpdates.find { it.packageName == BuildConfig.APPLICATION_ID }
        if (selfUpdate != null) {
            _dialogState.value = InstallDialogState.SelfUpdateRequired(selfUpdate)
            return true
        }
        return false
    }

    fun install(update: AppUpdate, availableUpdates: List<AppUpdate> = emptyList()) {
        installLog.log("Update clicked for ${update.name}")
        installNames[update.id] = update.name

        if (checkSelfUpdateRequired(update, availableUpdates)) {
            return
        }
        
        if (update.isPersistent) {
            installLog.log("Cannot update persistent app: ${update.packageName}")
            _dialogState.value = InstallDialogState.PersistentApp(update.name)
            return
        }

        if (!prefs.rootInstall.get() && !installer.checkPermission()) {
            installLog.log("Permission required for ${update.packageName}")
            _dialogState.value = InstallDialogState.PermissionRequired
            return
        }

        notification.showStatus(update.name, stringer.get(R.string.notif_checking))
        updateAppStatus(update.id, stringer.get(R.string.notif_checking))
        
        val job = if (prefs.rootInstall.get()) {
            installLog.log("Starting root install for ${update.name}")
            downloadAndRootInstall(update)
        } else {
            installLog.log("Starting standard install for ${update.name}")
            downloadAndInstall(update)
        }
        installJobs[update.id] = job
        job.invokeOnCompletion { installJobs.remove(update.id) }
    }

    protected fun subscribeToInstallStatus() = installLog.status().onEach {
        sendInstallSnack(it)
        val appName = installNames[it.id] ?: "App"
        if (it.success) {
            installLog.log("Install success for ID: ${it.id}")
            updateAppStatus(it.id, "Done!")
            notification.showStatus(appName, stringer.get(R.string.notif_success))
            maxProgressMap.remove(it.id)
            installNames.remove(it.id)
            finishInstall(it.id).join()
        } else {
            installLog.log("Install failed for ID: ${it.id}. Error: ${it.errorMessage}")
            notification.showStatus(appName, stringer.get(R.string.notif_failed))
            maxProgressMap.remove(it.id)
            installNames.remove(it.id)
            installLog.emitProgress(AppInstallProgress(it.id, 0L))
            cancelInstall(it.id).join()
        }
    }.launchIn(viewModelScope)

    protected fun subscribeToInstallProgress(
        block: (AppInstallProgress) -> Unit,
    ) = installLog.progress().onEach { progressEvent ->
        val id = progressEvent.id
        val total = progressEvent.total ?: 0L
        val current = progressEvent.progress ?: 0L
        val appName = installNames[id] ?: "App"
        
        var progressPercent = if (total > 0) {
            val p = ((current.toFloat() / total.toFloat()) * 100).toInt()
            if (p >= 98) 100 else p
        } else {
            0
        }
        
        val lastMax = maxProgressMap[id] ?: 0
        if (progressPercent < lastMax) {
            progressPercent = lastMax
        } else {
            maxProgressMap[id] = progressPercent
        }
        
        block(progressEvent.copy(progress = current))

        val now = System.currentTimeMillis()
        if ((now - lastProgressUpdate) > 300) {
            lastProgressUpdate = now
            val statusText = if (progressPercent >= 100) "Installing..." else "Downloading... $progressPercent%"
            notification.updateProgress(appName, statusText, progressPercent, total <= 0L)
        }
    }.launchIn(viewModelScope)

    protected suspend fun downloadAndInstall(id: Int, packageName: String, link: Link) = runCatching {
        installLog.emitProgress(AppInstallProgress(id, 0L))
        installLog.log("Download started for $packageName")
        notification.showStatus(packageName, stringer.get(R.string.notif_downloading))
        updateAppStatus(id, stringer.get(R.string.notif_downloading))
        
        when (link) {
            Link.Empty -> { 
                Log.e("InstallViewModel", "downloadAndInstall: Unsupported.")
                installLog.log("Error: Unsupported link for $packageName")
            }
            is Link.Play -> {
                val (files, headers) = link.getInstallFiles()
                val totalSize = files.sumOf { it.size }.coerceAtLeast(1L)
                installLog.emitProgress(AppInstallProgress(id, 0L, totalSize))
                
                val tempFiles = mutableListOf<File>()
                var currentProgress = 0L
                try {
                    val baseDir = installer.getContext().externalCacheDir ?: installer.getContext().cacheDir
                    val downloadDir = File(baseDir, "installer_cache").apply { mkdirs() }
                    
                    files.forEachIndexed { index, file ->
                        val response = downloader.downloadResponse(file.url, headers)
                            ?: throw Exception("Failed to download split $index")
                        
                        val tempFile = File(downloadDir, "${packageName}_${index}_${System.currentTimeMillis()}.apk")
                        tempFiles.add(tempFile)
                        
                        val buffer = ByteArray(128 * 1024)
                        response.body.byteStream().use { input ->
                            tempFile.outputStream().use { output ->
                                var bytes = input.read(buffer)
                                while (bytes >= 0) {
                                    output.write(buffer, 0, bytes)
                                    currentProgress += bytes
                                    val dynamicTotal = if (currentProgress > totalSize) currentProgress * 2 else totalSize
                                    installLog.emitProgress(AppInstallProgress(id, currentProgress, dynamicTotal))
                                    bytes = input.read(buffer)
                                }
                                output.flush()
                            }
                        }
                    }
                    installLog.emitProgress(AppInstallProgress(id, currentProgress, currentProgress))
                    installer.install(id, packageName, tempFiles)
                } finally {
                    tempFiles.forEach { it.delete() }
                }
            }
            is Link.Url -> {
                var downloadUrl = link.link
                if (downloadUrl.contains("apkmirror.com", ignoreCase = true)) {
                    installLog.log("Resolving direct APK download link from ApkMirror for $packageName")
                    downloadUrl = withContext(Dispatchers.IO) {
                        resolveApkMirrorDownloadUrl(downloadUrl) ?: downloadUrl
                    }
                }

                val response = downloader.downloadResponse(downloadUrl)
                val contentLength = response?.body?.contentLength() ?: -1L
                val totalSize = if (contentLength > 0) contentLength else (link.size.takeIf { it > 0 } ?: (15 * 1024 * 1024L))
                installLog.emitProgress(AppInstallProgress(id, 0L, totalSize))
                
                val isXapk = downloadUrl.contains(".xapk", ignoreCase = true)
                response?.body?.byteStream()?.let { stream ->
                    if (isXapk) {
                        installer.installXapk(id, packageName, stream)
                    } else {
                        installer.install(id, packageName, stream)
                    }
                }
            }
            is Link.Xapk -> {
                val response = downloader.downloadResponse(link.link)
                val contentLength = response?.body?.contentLength() ?: -1L
                val totalSize = if (contentLength > 0) contentLength else 20 * 1024 * 1024L
                installLog.emitProgress(AppInstallProgress(id, 0L, totalSize))
                response?.body?.byteStream()?.let { stream ->
                    installer.installXapk(id, packageName, stream)
                }
            }
        }
        
        val isRoot = prefs.rootInstall.get()
        val nextStatus = if (isRoot) R.string.notif_installing else R.string.notif_confirm
        
        installLog.log("${stringer.get(nextStatus)} $packageName")
        notification.showStatus(packageName, stringer.get(nextStatus))
        updateAppStatus(id, stringer.get(nextStatus))
    }.getOrElse {
        Log.e("InstallViewModel", "Error in downloadAndInstall.", it)
        installLog.log("Download error for $packageName: ${it.message}")
        notification.showStatus(packageName, stringer.get(R.string.notif_failed))
        cancelInstall(id)
    }

    open fun cancelInstall(id: Int): Job {
        installJobs[id]?.cancel()
        return viewModelScope.launch {
            // Placeholder, actual implementation in subclasses will update UI
        }
    }

    private fun resolveApkMirrorDownloadUrl(pageUrl: String): String? {
        return runCatching {
            var doc = Jsoup.connect(pageUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .get()
            
            var downloadHref = doc.select("a.downloadButton").attr("href")
            if (downloadHref.isEmpty()) {
                downloadHref = doc.select("a[href*=-apk-download/]").attr("href")
            }
            if (downloadHref.isNotEmpty()) {
                val fullUrl = if (downloadHref.startsWith("http")) downloadHref else "https://www.apkmirror.com$downloadHref"
                doc = Jsoup.connect(fullUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                    .get()
            }

            var keyUrl = doc.select("a[href*=/download.php]").attr("href")
            if (keyUrl.isEmpty()) {
                keyUrl = doc.select("a[href*=/download/]").attr("href")
            }
            if (keyUrl.isNotEmpty()) {
                if (!keyUrl.startsWith("http")) keyUrl = "https://www.apkmirror.com$keyUrl"
                keyUrl
            } else {
                pageUrl
            }
        }.getOrNull()
    }

    protected abstract fun sendInstallSnack(log: AppInstallStatus)
    protected abstract fun downloadAndInstall(update: AppUpdate): Job
    protected abstract fun downloadAndRootInstall(update: AppUpdate): Job
    protected abstract fun finishInstall(id: Int): Job
    protected abstract fun updateAppStatus(id: Int, status: String)
}
