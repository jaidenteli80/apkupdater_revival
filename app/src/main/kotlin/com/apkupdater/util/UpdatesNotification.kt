package com.apkupdater.util

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.apkupdater.R
import com.apkupdater.ui.activity.MainActivity


class UpdatesNotification(private val context: Context) {

    companion object {
        const val UPDATE_ID = 1
        const val UPDATE_ACTION = "updateAction"
    }

    private fun getBaseBuilder(): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = UPDATE_ACTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, context.getString(R.string.notification_channel_id))
            .setSmallIcon(R.drawable.ic_install)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notification_channel_name)
            val descriptionText = context.getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(context.getString(R.string.notification_channel_id), name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission")
    fun showUpdateNotification(count: Int) {
        if (!areNotificationsEnabled() || count == 0) return
        createNotificationChannel()

        val text = context.resources.getQuantityString(R.plurals.notification_update_description, count, count)
        val builder = getBaseBuilder()
            .setContentTitle(context.getString(R.string.notification_update_title))
            .setContentText(text)
            .setAutoCancel(true)

        NotificationManagerCompat.from(context).notify(UPDATE_ID, builder.build())
    }

    @SuppressLint("MissingPermission")
    fun showStatus(title: String, text: String) {
        if (!areNotificationsEnabled()) return
        createNotificationChannel()

        val builder = getBaseBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)

        NotificationManagerCompat.from(context).notify(UPDATE_ID, builder.build())
    }

    @SuppressLint("MissingPermission")
    fun updateProgress(title: String, text: String, progress: Int, indeterminate: Boolean) {
        if (!areNotificationsEnabled()) return
        createNotificationChannel()

        val builder = getBaseBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("APKUpdater Revival")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)

        NotificationManagerCompat.from(context).notify(UPDATE_ID, builder.build())
    }

    fun checkNotificationPermission(launcher: ManagedActivityResultLauncher<String, Boolean>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!areNotificationsEnabled()) {
                launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

}
