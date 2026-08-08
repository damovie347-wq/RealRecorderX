package com.recorderx.app.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionManager {

    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Only meaningful on API 26-28; scoped storage makes this a non-issue from API 29 on. */
    fun needsLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    fun hasLegacyStoragePermission(context: Context): Boolean {
        if (!needsLegacyStoragePermission()) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    /** All the runtime (dangerous) permissions a given [settings] selection actually
     * needs, so we only ever prompt for what will actually be used. */
    fun requiredRuntimePermissions(context: Context, wantsMic: Boolean): Array<String> {
        val perms = mutableListOf<String>()
        if (wantsMic && !hasMicPermission(context)) perms += Manifest.permission.RECORD_AUDIO
        if (!hasNotificationPermission(context)) perms += Manifest.permission.POST_NOTIFICATIONS
        if (needsLegacyStoragePermission() && !hasLegacyStoragePermission(context)) {
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return perms.toTypedArray()
    }
}
