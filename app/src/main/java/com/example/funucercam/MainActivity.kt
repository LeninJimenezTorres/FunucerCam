package com.example.funucercam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.provider.MediaStore
import android.util.Size
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.funucercam.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private enum class FlashMode {
    OFF, ON, AUTO
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var currentBitmap: Bitmap? = null
    private var sharpenFactor: Float = 5f
    private val REQUEST_CODE_PERMISSIONS = 100
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    private lateinit var camera: Camera
    private var flashMode = FlashMode.OFF
    private lateinit var imageCapture: ImageCapture

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        setupSliderListener()
        setupTouchToFocus()
        setupCaptureButton()
        setupFlashToggleButton()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestCameraPermissions()
        }
    }

    private fun setupSliderListener() {
        binding.sharpenSlider.addOnChangeListener { _, value, _ ->
            sharpenFactor = value
            currentBitmap?.let { updateSharpenedImage(it) }
        }
    }

    private fun setupFlashToggleButton() {
        binding.flashToggleButton.setOnClickListener {
            flashMode = when (flashMode) {
                FlashMode.OFF -> FlashMode.ON
                FlashMode.ON -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.OFF
            }
            updateFlashUI()
            startCamera()
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

    private fun captureFilteredWithFlash() {
        if (!::camera.isInitialized) return

        // Define punto central para enfocar
        val factory = binding.previewView.meteringPointFactory
        val centerPoint = factory.createPoint(
            binding.previewView.width / 2f,
            binding.previewView.height / 2f
        )

        val focusAction = FocusMeteringAction.Builder(centerPoint)
            .setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()

        // Iniciar enfoque
        val future = camera.cameraControl.startFocusAndMetering(focusAction)

        future.addListener({
            val result = future.get()
            if (result.isFocusSuccessful) {
                // Flash tipo linterna antes de capturar
                if (flashMode == FlashMode.ON || flashMode == FlashMode.AUTO) {
                    camera.cameraControl.enableTorch(true)
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    val filteredBitmap = getBitmapFromView(binding.sharpenedView)
                    if (filteredBitmap != null) {
                        saveBitmapToGallery(filteredBitmap)
                    } else {
                        showToast("No se pudo obtener imagen filtrada")
                    }

                    if (flashMode == FlashMode.ON || flashMode == FlashMode.AUTO) {
                        camera.cameraControl.enableTorch(false)
                    }
                }, 200) // breve delay para iluminación tras enfoque
            } else {
                showToast("❌ Falló el enfoque")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getBitmapFromView(view: ImageView): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val filename = "captura_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FunucerCam")
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            val stream: OutputStream? = resolver.openOutputStream(it)
            stream?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                showToast("Imagen guardada 📷")
            }
        } ?: showToast("Error al guardar imagen")
    }

    private fun buildImageCapture(): ImageCapture {
        val flash = when (flashMode) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
        }

        return ImageCapture.Builder()
            .setTargetRotation(binding.previewView.display.rotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flash)
            .build()
    }

    private fun buildPreview(): Preview {
        return Preview.Builder()
            .setTargetResolution(Size(640, 480))
            .build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
    }

    private fun buildImageAnalyzer(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setTargetRotation(binding.previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    val bitmap = imageProxy.toBitmap()
                    currentBitmap = bitmap
                    updateSharpenedImage(bitmap)
                    imageProxy.close()
                }
            }
    }

    private fun updateSharpenedImage(originalBitmap: Bitmap) {
        val sharpened = applySharpenFilter(originalBitmap, sharpenFactor)
        runOnUiThread {
            binding.sharpenedView.setImageBitmap(sharpened)
            binding.sharpenedView.rotation = 90f
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                imageCapture = buildImageCapture()
                camera = cameraProvider.bindToLifecycle(
                    this, selector, buildPreview(), buildImageAnalyzer(), imageCapture
                )
            } catch (e: Exception) {
                showToast("Error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestCameraPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                showToast("Permisos no concedidos")
                finish()
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

fun ImageProxy.toBitmap(): Bitmap {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
    val yuv = out.toByteArray()

    val originalBitmap = BitmapFactory.decodeByteArray(yuv, 0, yuv.size)
    val rotationDegrees = when (this.imageInfo.rotationDegrees) {
        0 -> 90
        90 -> 0
        180 -> 270
        270 -> 180
        else -> 0
    }

    val matrix = Matrix().apply {
        postRotate(rotationDegrees.toFloat())
        if (rotationDegrees == 90) postScale(-1f, 1f)
    }

    return Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
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
