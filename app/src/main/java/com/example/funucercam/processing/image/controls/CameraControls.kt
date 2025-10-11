package com.example.funucercam.processing.image.controls

import android.view.KeyEvent
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import com.example.funucercam.databinding.ActivityMainBinding
import com.example.funucercam.processing.image.sensor.CameraSelectorManager

class CameraControls(
    private val binding: ActivityMainBinding,
    private val imageCapture: ImageCapture,
    private val camera: Camera,
    private var flashMode: FlashMode,
    private var currentCamera: Int,
    private val cameraSelectorManager: CameraSelectorManager,
    private val onStartCamera: () -> Unit,
    private val onCapture: () -> Unit,
    private val onFlashModeChanged: (FlashMode) -> Unit
) {
    enum class FlashMode {
        OFF, ON, AUTO
    }

    fun setup() {
        setupCaptureButton()
        setupFlashToggle()
        setupSwitchCamera()
        updateFlashUI()
    }

    fun handleKeyEvent(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                onCapture()
                true
            }
            else -> false
        }
    }

    private fun setupCaptureButton() {
        binding.captureButton.setOnClickListener {
            onCapture()
        }
    }

    private fun setupFlashToggle() {
        binding.flashToggleButton.setOnClickListener {
            flashMode = when (flashMode) {
                FlashMode.OFF -> FlashMode.ON
                FlashMode.ON -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.OFF
            }
            updateFlashUI()
            onFlashModeChanged(flashMode)
            onStartCamera()
        }
    }

    private fun setupSwitchCamera() {
        binding.switchCameraButton.setOnClickListener {
            binding.processedView.rotation = if (currentCamera == CameraSelector.LENS_FACING_FRONT) -90f else 90f
            cameraSelectorManager.switchCamera()
        }
    }

    fun applyFlashModeToImageCapture() {
        imageCapture.flashMode = when (flashMode) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_ON // No hay modo AUTO nativo sin análisis
        }
    }

    private fun updateFlashUI() {
        when (flashMode) {
            FlashMode.OFF -> {
                camera.cameraControl.enableTorch(false)
                binding.flashToggleButton.text = "🔦 Flash OFF"
            }
            FlashMode.ON -> {
                camera.cameraControl.enableTorch(true)
                binding.flashToggleButton.text = "🔆 Flash ON"
            }
            FlashMode.AUTO -> {
                camera.cameraControl.enableTorch(false)
                binding.flashToggleButton.text = "⚡ Flash AUTO"
            }
        }
    }

    fun getCurrentFlashMode(): FlashMode = flashMode
}
