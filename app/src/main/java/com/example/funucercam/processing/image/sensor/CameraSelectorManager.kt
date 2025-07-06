package com.example.funucercam.processing.image.sensor

import androidx.camera.core.CameraSelector

class CameraSelectorManager(
    private var currentCamera: Int,
    private val onCameraUpdated: (Int) -> Unit,
    private val onUIUpdate: (String) -> Unit
) {

    fun switchCamera(): Int {
        currentCamera = if (currentCamera == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        val icon = if (currentCamera == CameraSelector.LENS_FACING_BACK) "📷 Trasera" else "🤳 Frontal"
        onUIUpdate(icon)
        onCameraUpdated(currentCamera)
        return currentCamera
    }

    fun getCurrentSelector(): androidx.camera.core.CameraSelector {
        return androidx.camera.core.CameraSelector.Builder()
            .requireLensFacing(currentCamera)
            .build()
    }

    fun getCurrentCamera(): Int = currentCamera
}
