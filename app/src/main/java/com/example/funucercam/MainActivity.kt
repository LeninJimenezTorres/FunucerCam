package com.example.funucercam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.*
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private enum class FlashMode {
    OFF, ON, AUTO
}

class MainActivity : AppCompatActivity() {
    // region [Variables y inicialización]
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var currentBitmap: Bitmap? = null
    private val REQUEST_CODE_PERMISSIONS = 100
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    // Variables de control
    private var temperatureValue: Float = 0f
    private var sharpenValue: Float = 5f
    private var baseBitmap: Bitmap? = null
    private var saturationValue: Float = 1f
    private var flashMode = FlashMode.OFF

    // Componentes de cámara
    private lateinit var camera: androidx.camera.core.Camera
    private lateinit var imageCapture: ImageCapture
    private var currentCamera = CameraSelector.LENS_FACING_BACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initializeApp()
    }
    // endregion

    // region [Configuración Inicial]
    private fun initializeApp() {
        initializeCameraExecutor()
        setupUIListeners()
        checkCameraPermissions()
    }

    private fun initializeCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun checkCameraPermissions() {
        if (allPermissionsGranted()) startCamera()
        else requestCameraPermissions()
    }
    // endregion

    // region [Manejo de Permisos]
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            startCamera()
        } else {
            showToast("Permisos no concedidos")
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
                showToast("Error al iniciar cámara: ${e.message}")
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

            setupExposureSlider()
            updateFlashUI()
            updateCameraSwitchUI()
        } catch (e: Exception) {
            showToast("Error al cambiar cámara: ${e.message}")
            // Revertir a cámara trasera si hay error
            currentCamera = CameraSelector.LENS_FACING_BACK
            startCamera()
        }
    }

    private fun updateCameraSwitchUI() {
        val icon = if (currentCamera == CameraSelector.LENS_FACING_BACK) {
            "📷 Trasera"
        } else {
            "🤳 Frontal"
        }
        binding.switchCameraButton.text = icon
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
            binding.sharpenedView.apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bitmap)
            }
        }
    }
    // endregion

    // region [Filtros de Imagen]
    private fun applyTemperatureFilter(bitmap: Bitmap, temperature: Float): Bitmap {
        val bmp = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val paint = Paint()

        val red = (temperature / 100f).coerceIn(-1f, 1f)
        val blue = (-temperature / 100f).coerceIn(-1f, 1f)

        val colorMatrix = ColorMatrix(floatArrayOf(
            1f + red, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f + blue, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bmp, 0f, 0f, paint)

        return bmp
    }

    private fun applySaturationFilter(bitmap: Bitmap, saturation: Float): Bitmap {
        val bmp = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val paint = Paint()

        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(saturation.coerceIn(0f, 2f))

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bmp, 0f, 0f, paint)

        return bmp
    }
    // endregion

    // region [Manejo de Flash]
    private fun setupFlashToggleButton() {
        binding.flashToggleButton.setOnClickListener {
            flashMode = when (flashMode) {
                FlashMode.OFF -> FlashMode.ON
                FlashMode.ON -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.OFF
            }
            updateFlashUI()
            startCamera() // Reinicia la cámara para aplicar cambios
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
    // endregion

    // region [Captura de Fotos]
    private fun captureFilteredWithFlash() {
        if (!::imageCapture.isInitialized) return

        configureFlashForCapture()
        prepareFocusForCapture()
    }

    private fun configureFlashForCapture() {
        imageCapture.flashMode = when (flashMode) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_ON
        }
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
                    showToast("Error al capturar: ${exc.message}")
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
                showToast("Imagen guardada 📷")
            } ?: showToast("Error al guardar imagen")
        } catch (e: Exception) {
            showToast("Error al guardar: ${e.message}")
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
        setupFlashToggleButton()
        setupSwitchCameraButton()
    }

    private fun setupSwitchCameraButton() {
        binding.switchCameraButton.setOnClickListener {
            currentCamera = if (currentCamera == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

            if (currentCamera == CameraSelector.LENS_FACING_FRONT) {
                binding.sharpenedView.rotation = -90f
            } else {
                binding.sharpenedView.rotation = 90f
            }

            startCamera()
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
        binding.sharpenedView.setOnTouchListener { _, event ->
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

    private fun setupExposureSlider() {
        val exposureState = camera.cameraInfo.exposureState
        binding.exposureSlider.apply {
            valueFrom = exposureState.exposureCompensationRange.lower.toFloat()
            valueTo = exposureState.exposureCompensationRange.upper.toFloat()
            value = exposureState.exposureCompensationIndex.toFloat()
            addOnChangeListener { _, value, _ ->
                camera.cameraControl.setExposureCompensationIndex(value.toInt())
            }
        }
    }
    // endregion

    // region [Utilidades]
    private fun showToast(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                captureFilteredWithFlash()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
    // endregion
}

fun applySharpenFilter(src: Bitmap, factor: Float = 5f): Bitmap {
    val centerValue = 1f + (factor / 2.5f)
    val edgeValue = -factor / 10f

    val kernel = arrayOf(
        floatArrayOf(0f, edgeValue, 0f),
        floatArrayOf(edgeValue, centerValue, edgeValue),
        floatArrayOf(0f, edgeValue, 0f)
    )

    val width = src.width
    val height = src.height
    val result = Bitmap.createBitmap(width, height, src.config ?: Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    val newPixels = IntArray(width * height)
    src.getPixels(pixels, 0, width, 0, 0, width, height)

    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            var r = 0f
            var g = 0f
            var b = 0f

            for (ky in -1..1) {
                for (kx in -1..1) {
                    val pixel = pixels[(x + kx) + (y + ky) * width]
                    val weight = kernel[ky + 1][kx + 1]
                    r += ((pixel shr 16 and 0xFF) * weight)
                    g += ((pixel shr 8 and 0xFF) * weight)
                    b += ((pixel and 0xFF) * weight)
                }
            }

            val newR = r.coerceIn(0f, 255f).toInt()
            val newG = g.coerceIn(0f, 255f).toInt()
            val newB = b.coerceIn(0f, 255f).toInt()
            newPixels[x + y * width] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
        }
    }

    result.setPixels(newPixels, 0, width, 0, 0, width, height)
    return result
}
