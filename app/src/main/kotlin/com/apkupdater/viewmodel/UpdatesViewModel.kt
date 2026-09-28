package com.apkupdater.viewmodel

import androidx.lifecycle.viewModelScope
import com.apkupdater.R
import com.apkupdater.data.ui.AppInstallStatus
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.UpdateStage
import com.apkupdater.data.ui.UpdatesUiState
import com.apkupdater.data.ui.removeId
import com.apkupdater.data.ui.setError
import com.apkupdater.data.ui.setIsInstalling
import com.apkupdater.data.ui.setProgress
import com.apkupdater.data.ui.setStatus
import com.apkupdater.prefs.Prefs
import com.apkupdater.repository.UpdatesRepository
import com.apkupdater.util.Badger
import com.apkupdater.util.Downloader
import com.apkupdater.util.InstallLog
import com.apkupdater.util.SessionInstaller
import com.apkupdater.util.SnackBar
import com.apkupdater.util.Stringer
import com.apkupdater.util.UpdatesNotification
import com.apkupdater.util.launchWithMutex
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


class UpdatesViewModel(
	private val updatesRepository: UpdatesRepository,
	private val badger: Badger,
	prefs: Prefs,
	downloader: Downloader,
	installer: SessionInstaller,
	snackBar: SnackBar,
	stringer: Stringer,
	installLog: InstallLog,
	notification: UpdatesNotification,
) : InstallViewModel(downloader, installer, prefs, snackBar, stringer, installLog, notification) {

	private val mutex = Mutex()
	private val state = MutableStateFlow<UpdatesUiState>(UpdatesUiState.Loading(UpdateStage.CONNECTING, 0f))
	private var lastProgressUpdate = 0L
    private var loadingJob: Job? = null

	init {
		subscribeToInstallStatus()
		subscribeToInstallProgress { progress ->
			val now = System.currentTimeMillis()
			if ((now - lastProgressUpdate) > 100) { 
				lastProgressUpdate = now
				(state.value as? UpdatesUiState.Success)?.let { currentState ->
					state.value = UpdatesUiState.Success(currentState.updates.toMutableList().setProgress(progress))
				}
			}
		}
	}

	fun state(): StateFlow<UpdatesUiState> = state

	fun refresh(load: Boolean = true) = viewModelScope.launch(Dispatchers.IO) {
		if (load) {
			loadingJob?.cancel()
			loadingJob = viewModelScope.launch {
				smoothLoadingProgress()
			}
		}

		badger.changeUpdatesBadge("")
		var emissionCount = 0
		updatesRepository.updates()
			.catch { e ->
				loadingJob?.cancel()
				state.value = UpdatesUiState.Error(e.message ?: stringer.get(R.string.update_fetch_failed))
			}
			.collectLatest { freshUpdates ->
				emissionCount++
				// Don't cancel immediately on first empty emission if we are simulating progress.
				// But after the first emission, if it's empty, it's likely a real result from a source.
				// Also, if progress is already high, we can finish.
				val progress = (state.value as? UpdatesUiState.Loading)?.progress ?: 0f
				if (freshUpdates.isNotEmpty() || !load || emissionCount > 1 || progress > 0.8f) {
					loadingJob?.cancel()
					val currentUpdates = state.value.updates()
					val mergedUpdates = freshUpdates.map { fresh ->
						val existing = currentUpdates.find { it.packageName == fresh.packageName }
						if (existing != null) {
							fresh.copy(
								isInstalling = existing.isInstalling,
								progress = existing.progress,
								total = existing.total,
								status = existing.status,
								error = existing.error,
							)
						} else {
							fresh
						}
					}
					mutex.withLock {
						setSuccess(mergedUpdates)
					}
				}
			}
            
        // Fallback: If simulation finished but no updates found
        if (state.value is UpdatesUiState.Loading) {
            mutex.withLock {
                setSuccess(emptyList())
            }
        }
	}

	private suspend fun smoothLoadingProgress() {
		var currentProgress = 0f
		state.value = UpdatesUiState.Loading(UpdateStage.CONNECTING, 0f)

		while (currentProgress < 0.2f) {
			currentProgress += 0.05f
			state.value = UpdatesUiState.Loading(UpdateStage.CONNECTING, currentProgress)
			delay(150.milliseconds)
		}

		while (currentProgress < 0.6f) {
			currentProgress += 0.05f
			state.value = UpdatesUiState.Loading(UpdateStage.FETCHING, currentProgress)
			delay(200.milliseconds)
		}

		while (currentProgress < 0.9f) {
			currentProgress += 0.02f
			state.value = UpdatesUiState.Loading(UpdateStage.CHECKING, currentProgress)
			delay(250.milliseconds)
		}
	}

	fun installAll() = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		val isRoot = prefs.rootInstall.get()
		if (isRoot || installer.checkPermission()) {
			val currentUpdates = state.value.updates()
			val toInstall = currentUpdates.filter { !it.isInstalling && !it.isPersistent }

			if (toInstall.isNotEmpty()) {
				var newStateUpdates = currentUpdates.toMutableList()
				toInstall.forEach { update ->
					newStateUpdates = newStateUpdates.setIsInstalling(id = update.id, b = true).toMutableList()
					val job = viewModelScope.launch(Dispatchers.IO) {
						downloadAndInstall(update.id, update.packageName, update.link)
					}
					installJobs[update.id] = job
					job.invokeOnCompletion { installJobs.remove(update.id) }
				}
				state.value = UpdatesUiState.Success(newStateUpdates)
			}
		} else {
			_dialogState.value = InstallDialogState.PermissionRequired
		}
	}

	fun ignoreVersion(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		updatesRepository.ignoreVersion(id)
		badger.changeUpdatesBadge("")
		state.value = UpdatesUiState.Success(state.value.mutableUpdates().removeId(id))
		setSuccess(state.value.mutableUpdates())
	}

	override fun cancelInstall(id: Int): Job = viewModelScope.launch(Dispatchers.IO) {
		super.cancelInstall(id).join()
		(state.value as? UpdatesUiState.Success)?.let { currentState ->
			state.value = UpdatesUiState.Success(currentState.updates.toMutableList().setIsInstalling(id = id, b = false))
		}
		installer.finish()
	}

	override fun finishInstall(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		(state.value as? UpdatesUiState.Success)?.let { currentState ->
			state.value = UpdatesUiState.Success(currentState.updates.toMutableList().removeId(id))
		}
		installer.finish()
	}

	override fun downloadAndRootInstall(update: AppUpdate): Job = viewModelScope.launch(Dispatchers.IO) {
		(state.value as? UpdatesUiState.Success)?.let { currentState ->
			state.value = UpdatesUiState.Success(currentState.updates.toMutableList().setIsInstalling(id = update.id, b = true))
		}
		downloadAndInstall(update.id, update.packageName, update.link)
	}

	override fun downloadAndInstall(update: AppUpdate): Job = viewModelScope.launch(Dispatchers.IO) {
		if (installer.checkPermission()) {
			val currentState = state.value
			if (currentState is UpdatesUiState.Success) {
				state.value = UpdatesUiState.Success(currentState.updates.toMutableList().setIsInstalling(update.id, true))
			}
			downloadAndInstall(update.id, update.packageName, update.link)
		} else {
			_dialogState.value = InstallDialogState.PermissionRequired
		}
	}

	override fun sendInstallSnack(log: AppInstallStatus) {
		val currentState = state.value
		if (currentState is UpdatesUiState.Success) {
			val updates = currentState.updates.toMutableList()
			if (!log.success) {
				state.value = UpdatesUiState.Success(updates.setError(log.id, log.errorMessage ?: stringer.get(R.string.install_failure, "")))
			}
		}

		if (!log.success && !log.errorMessage.isNullOrEmpty()) {
			_dialogState.value = InstallDialogState.GenericError(
				"Installation Notice",
				cleanErrorMessage(log.errorMessage)
			)
		}
	}

	override fun updateAppStatus(id: Int, status: String) {
		val currentState = state.value
		if (currentState is UpdatesUiState.Success) {
			state.value = UpdatesUiState.Success(currentState.updates.toMutableList().setStatus(id, status))
		}
	}

	private fun setSuccess(updates: List<AppUpdate>) {
		state.value = UpdatesUiState.Success(updates)
	}
}
