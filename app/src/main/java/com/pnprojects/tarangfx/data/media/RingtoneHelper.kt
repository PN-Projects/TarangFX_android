package com.pnprojects.tarangfx.data.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class RingtoneTarget(val label: String, val managerType: Int, val column: String, val directory: String) {
    RINGTONE("Ringtone", RingtoneManager.TYPE_RINGTONE, MediaStore.Audio.Media.IS_RINGTONE, android.os.Environment.DIRECTORY_RINGTONES),
    NOTIFICATION("Notification", RingtoneManager.TYPE_NOTIFICATION, MediaStore.Audio.Media.IS_NOTIFICATION, android.os.Environment.DIRECTORY_NOTIFICATIONS),
    ALARM("Alarm", RingtoneManager.TYPE_ALARM, MediaStore.Audio.Media.IS_ALARM, android.os.Environment.DIRECTORY_ALARMS),
}

/** PRD §7 ringtone assignment via WRITE_SETTINGS (granted by the user in system settings). */
@Singleton
class RingtoneHelper @Inject constructor(@ApplicationContext private val context: Context) {

    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    fun writeSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))

    suspend fun assign(uri: Uri, target: RingtoneTarget): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(canWriteSettings()) { "Permission to modify system settings not granted" }
            // Android 11+ derives IS_RINGTONE/IS_ALARM/... from the folder (exports for ringtones are
            // saved under Ringtones/, Notifications/, Alarms/); the update still helps on Android 8-10.
            val values = ContentValues().apply { put(target.column, 1) }
            runCatching { context.contentResolver.update(uri, values, null, null) }
            RingtoneManager.setActualDefaultRingtoneUri(context, target.managerType, uri)
        }
    }
}
