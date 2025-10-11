package com.example.funucercam.processing.image.extensions

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionsManager {
    private val REQUEST_CODE_PERMISSIONS = 100
    private val REQUIRED_PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA)

    fun hasPermissions(context: Context): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestPermissions(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            REQUIRED_PERMISSIONS,
            REQUEST_CODE_PERMISSIONS
        )
    }

    fun isPermissionGranted(requestCode: Int, grantResults: IntArray): Boolean {
        return requestCode == REQUEST_CODE_PERMISSIONS &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }
}