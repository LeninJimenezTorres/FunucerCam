package com.example.funucercam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.core.Preview
import android.util.Size
import androidx.camera.core.CameraSelector
import android.widget.Toast
import com.example.funucercam.databinding.ActivityMainBinding
import androidx.camera.core.ImageAnalysis
import android.graphics.YuvImage
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageView
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var currentBitmap: Bitmap? = null
    private var sharpenFactor: Float = 5f
    private val REQUEST_CODE_PERMISSIONS = 100
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    private var temperatureValue: Float = 0f
    private var sharpenValue: Float = 5f
    private var baseBitmap: Bitmap? = null

    private lateinit var camera: androidx.camera.core.Camera

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeCameraExecutor()
        setupSliderListener()
        setupTemperatureSlider()
        setupTouchToFocus()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestCameraPermissions()
        }

        setupCaptureButton()
    }

    private fun initializeCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupSliderListener() {
        binding.sharpenSlider.addOnChangeListener { _, value, _ ->
            sharpenValue = value
            updateFilteredImage()
        }
    }

    private fun setupTouchToFocus() {
        binding.sharpenedView.setOnTouchListener { _, event ->
            val factory = binding.previewView.meteringPointFactory
            val point = factory.createPoint(event.x, event.y)
            val action = androidx.camera.core.FocusMeteringAction.Builder(point).build()
            camera.cameraControl.startFocusAndMetering(action)
            true
        }
    }

    private fun requestCameraPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateSharpenedImage(originalBitmap: Bitmap) {
        baseBitmap?.let { original ->
            val sharpened = applySharpenFilter(original, sharpenValue)
            val warmed = applyTemperatureFilter(sharpened, temperatureValue)

            currentBitmap = warmed
            runOnUiThread {
                binding.sharpenedView.setImageBitmap(warmed)
                binding.sharpenedView.rotation = 90f
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = buildPreview()
            val analyzer = buildImageAnalyzer()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, selector, preview, analyzer)
            } catch (e: Exception) {
                showToast("Error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

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

    private fun setupTemperatureSlider() {
        binding.temperatureSlider.addOnChangeListener { _, value, _ ->
            temperatureValue = value
            updateFilteredImage()
        }
    }

    private fun updateFilteredImage() {
        baseBitmap?.let { original ->
            val sharpened = applySharpenFilter(original, sharpenValue)
            val warmed = applyTemperatureFilter(sharpened, temperatureValue)

            currentBitmap = warmed
            runOnUiThread {
                binding.sharpenedView.setImageBitmap(warmed)
                binding.sharpenedView.rotation = 90f
            }
        }
    }

    private fun buildPreview(): Preview {
        return Preview.Builder()
            .setTargetResolution(Size(640, 480))
            .build()
            .also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
    }

    private fun buildImageAnalyzer(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setTargetRotation(binding.previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    val bitmap = imageProxy.toBitmap()
                    baseBitmap = bitmap          // Guarda imagen original
                    updateFilteredImage()        // Aplica filtros actuales
                    imageProxy.close()
                }
            }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
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

    private fun setupCaptureButton() {
        binding.captureButton.setOnClickListener {
            val viewBitmap = getBitmapFromView(binding.sharpenedView)
            if (viewBitmap != null) {
                saveBitmapToGallery(viewBitmap)
            } else {
                showToast("No se pudo capturar la imagen")
            }
        }
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
        val fos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)?.let {
                contentResolver.openOutputStream(it)
            }
        } else {
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val image = File(imagesDir, filename)
            FileOutputStream(image)
        }

        fos?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
            showToast("Imagen guardada 📷")
        } ?: showToast("Error al guardar imagen")
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
