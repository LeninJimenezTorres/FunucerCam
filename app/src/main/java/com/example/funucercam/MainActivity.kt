package com.example.funucercam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.*
import android.provider.MediaStore
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.funucercam.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import android.os.Environment
import android.view.KeyEvent
import android.widget.ImageView
import androidx.camera.core.ImageProxy
import com.example.funucercam.processing.image.controls.CameraControls
import com.example.funucercam.processing.image.extensions.PermissionsManager
import com.example.funucercam.processing.image.extensions.showToast
import com.example.funucercam.processing.image.parameters.applySaturationFilter
import com.example.funucercam.processing.image.parameters.applySharpenFilter
import com.example.funucercam.processing.image.parameters.applyTemperatureFilter
import com.example.funucercam.processing.image.parameters.setupExposureSlider
import com.example.funucercam.processing.image.sensor.CameraSelectorManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    // region [Variables y inicialización]
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var currentBitmap: Bitmap? = null

    private var temperatureValue: Float = 0f
    private var sharpenValue: Float = 5f
    private var baseBitmap: Bitmap? = null
    private var saturationValue: Float = 1f
    private lateinit var cameraControls: CameraControls

    private lateinit var camera: Camera
    private lateinit var imageCapture: ImageCapture
    private var currentCamera = CameraSelector.LENS_FACING_BACK
    private lateinit var cameraSelectorManager: CameraSelectorManager

    private var flashMode = CameraControls.FlashMode.OFF

    lateinit var context: Context

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initializeApp()
        context = this
    }

    private fun initializeApp() {
        cameraSelectorManager = CameraSelectorManager(
            currentCamera = CameraSelector.LENS_FACING_BACK,
            onCameraUpdated = { lensFacing ->
                currentCamera = lensFacing

                // 👇 Aplica rotación correcta según cámara
                binding.processedView.rotation = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    -90f
                } else {
                    90f
                }

                startCamera()
            },
            onUIUpdate = { icon -> binding.switchCameraButton.text = icon }
        )

        initializeCameraExecutor()
        setupUIListeners()
        checkCameraPermissions()
    }

    private fun initializeCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun checkCameraPermissions() {
        if (PermissionsManager.hasPermissions(this)) {
            startCamera()
        } else {
            PermissionsManager.requestPermissions(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (PermissionsManager.isPermissionGranted(requestCode, grantResults)) {
            startCamera()
        } else {
            showToast("Permisos no concedidos", this)
            finish()
        }
    }
    // endregion

    // region [Configuración de Cámara]
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(cameraProvider)
            } catch (e: Exception) {
                showToast("Error al iniciar cámara: ${e.message}", this)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val preview = buildPreview()
        val analyzer = buildImageAnalyzer()
        val selector = CameraSelector.Builder()
            .requireLensFacing(currentCamera)
            .build()

        try {
            cameraProvider.unbindAll()
            imageCapture = buildImageCapture()

            camera = cameraProvider.bindToLifecycle(
                this, selector, preview, analyzer, imageCapture
            )

            cameraControls = CameraControls(
                binding = binding,
                imageCapture = imageCapture,
                camera = camera,
                flashMode = flashMode,
                currentCamera = currentCamera,
                cameraSelectorManager = cameraSelectorManager,
                onStartCamera = { startCamera() },
                onCapture = { captureFilteredWithFlash() },
                onFlashModeChanged = { updatedFlashMode -> flashMode = updatedFlashMode }
            )
            cameraControls.setup()

            setupExposureSlider(camera, binding)
        } catch (e: Exception) {
            showToast("Error al cambiar cámara: ${e.message}", this)
            // Revertir a cámara trasera si hay error
            currentCamera = CameraSelector.LENS_FACING_BACK
            startCamera()
        }
    }

    private fun buildPreview(): Preview {
        return Preview.Builder()
            .setTargetResolution(Size(640, 480))
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
    }

    private fun buildImageCapture(): ImageCapture {
        return ImageCapture.Builder()
            .setTargetRotation(binding.previewView.display.rotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    private fun buildImageAnalyzer(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setTargetRotation(binding.previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    processCameraFrame(imageProxy)
                }
            }
    }
    // endregion

    // region [Procesamiento de Imágenes]
    private fun processCameraFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        baseBitmap = bitmap
        updateFilteredImage()
        imageProxy.close()
    }

    private fun applyAllFilters(original: Bitmap): Bitmap {
        val sharpened = applySharpenFilter(original, sharpenValue)
        val warmed = applyTemperatureFilter(sharpened, temperatureValue)
        return applySaturationFilter(warmed, saturationValue)
    }

    private fun updateFilteredImage() {
        baseBitmap?.let { original ->
            val filtered = applyAllFilters(original)
            currentBitmap = filtered
            updateFilteredImageView(filtered)
        }
    }

    private fun updateFilteredImageView(bitmap: Bitmap) {
        runOnUiThread {
            binding.previewView.alpha = 0f
            binding.previewView.isEnabled = false
            binding.processedView.apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap)
            }
        }
    }
    // endregion

    // region [Captura de Fotos]
    private fun captureFilteredWithFlash() {
        if (!::imageCapture.isInitialized) return

        cameraControls.applyFlashModeToImageCapture()

        prepareFocusForCapture()
    }

    private fun prepareFocusForCapture() {
        val factory = binding.previewView.meteringPointFactory
        val centerPoint = factory.createPoint(
            binding.previewView.width / 2f,
            binding.previewView.height / 2f
        )

        val focusAction = FocusMeteringAction.Builder(centerPoint)
            .setAutoCancelDuration(1, TimeUnit.SECONDS)
            .build()

        camera.cameraControl.startFocusAndMetering(focusAction)
            .addListener({
                Handler(Looper.getMainLooper()).postDelayed({
                    captureAndProcessFrame()
                }, 200)
            }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndProcessFrame() {
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    processCapturedImage(image)
                }

                override fun onError(exc: ImageCaptureException) {
                    showToast("Error al capturar: ${exc.message}", context)
                }
            }
        )
    }

    private fun processCapturedImage(image: ImageProxy) {
        val rotationDegrees = image.imageInfo.rotationDegrees
        val rawBitmap = image.toBitmap()
        image.close()

        val filtered = applyAllFilters(rawBitmap)
        val rotated = rotateBitmap(
            filtered,
            rotationDegrees.toFloat(),
            isFrontCamera = currentCamera == CameraSelector.LENS_FACING_FRONT
        )
        saveBitmapToGallery(rotated)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float, isFrontCamera: Boolean): Bitmap {
        val matrix = Matrix().apply {
            postRotate(degrees)
            if (isFrontCamera) {
                postScale(-1f, 1f) // Efecto espejo para cámara frontal
            }
        }
        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
    }
    // endregion

    // region [Gestión de Archivos]
    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val filename = "captura_${System.currentTimeMillis()}.jpg"

        try {
            val outputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                createContentValues(filename).let { values ->
                    contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let {
                        contentResolver.openOutputStream(it)
                    }
                }
            } else {
                FileOutputStream(File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    filename
                ))
            }

            outputStream?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                showToast("Imagen guardada 📷", this)
            } ?: showToast("Error al guardar imagen", this)
        } catch (e: Exception) {
            showToast("Error al guardar: ${e.message}", this)
        }
    }

    private fun createContentValues(filename: String): ContentValues {
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }
    }
    // endregion

    // region [UI Listeners]
    private fun setupUIListeners() {
        setupSliderListeners()
        setupTouchToFocus()
        setupCaptureButton()
        setupSwitchCameraButton()
    }

    private fun setupSwitchCameraButton() {
        binding.switchCameraButton.setOnClickListener {
            if (currentCamera == CameraSelector.LENS_FACING_FRONT) {
                binding.processedView.rotation = -90f
            } else {
                binding.processedView.rotation = 90f
            }
            cameraSelectorManager.switchCamera()
        }
    }

    private fun setupSliderListeners() {
        binding.sharpenSlider.addOnChangeListener { _, value, _ ->
            sharpenValue = value
            updateFilteredImage()
        }

        binding.temperatureSlider.addOnChangeListener { _, value, _ ->
            temperatureValue = value
            updateFilteredImage()
        }

        binding.saturationSlider.addOnChangeListener { _, value, _ ->
            saturationValue = value
            updateFilteredImage()
        }
    }

    private fun setupTouchToFocus() {
        binding.processedView.setOnTouchListener { _, event ->
            val factory = binding.previewView.meteringPointFactory
            val point = factory.createPoint(event.x, event.y)
            val action = FocusMeteringAction.Builder(point).build()
            camera.cameraControl.startFocusAndMetering(action)
            true
        }
    }

    private fun setupCaptureButton() {
        binding.captureButton.setOnClickListener {
            captureFilteredWithFlash()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return cameraControls.handleKeyEvent(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
    // endregion
}
