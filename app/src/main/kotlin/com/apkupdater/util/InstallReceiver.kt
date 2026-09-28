package com.apkupdater.util

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.apkupdater.data.ui.AppInstallStatus
import org.koin.java.KoinJavaComponent

@Suppress("UnsafeIntentLaunch", "QueryPermissionsNeeded")
class InstallReceiver : BroadcastReceiver() {
    @SuppressLint("UnsafeIntentLaunch")
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val installLog = KoinJavaComponent.get<InstallLog>(InstallLog::class.java)
        val id = installLog.currentInstallId

        Log.d("InstallReceiver", "onReceive status=$status, id=$id")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }

                confirmIntent?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (confirmIntent != null) {
                    context.startActivity(confirmIntent)
                } else {
                    installLog.emitStatus(AppInstallStatus(success = false, id = id, snack = true, errorMessage = "User action required but intent missing"))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                installLog.log("PackageInstaller Success for ID $id")
                installLog.emitStatus(AppInstallStatus(success = true, id = id, snack = true))
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation failed ($status)"
                installLog.log("PackageInstaller Error $status: $message")
                var friendlyError = message
                if (friendlyError.contains("REJECTED_BY_BUILDTYPE") || friendlyError.contains("UPDATE_INCOMPATIBLE") || friendlyError.contains("signatures do not match") || status == -3001) {
                    friendlyError = "Signature Mismatch: Update signature does not match installed version. Uninstall the app first to update."
                } else if (friendlyError.contains("VERIFICATION_FAILURE") || friendlyError.contains("BLOCKED") || status == PackageInstaller.STATUS_FAILURE_BLOCKED) {
                    friendlyError = "Blocked by Google Play Protect. Tap 'More details' -> 'Install anyway' in system popup."
                }
                installLog.emitStatus(AppInstallStatus(success = false, id = id, snack = true, errorMessage = friendlyError))
            }
        }
    }
}
