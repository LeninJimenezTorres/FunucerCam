package com.example.funucercam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
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
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.camera.core.ImageAnalysis
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var currentBitmap: Bitmap? = null
    private var sharpenFactor: Float = 5f
    private val REQUEST_CODE_PERMISSIONS = 100
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    private lateinit var camera: androidx.camera.core.Camera

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.sharpenSlider.addOnChangeListener { _, value, _ ->
            sharpenFactor = value
            currentBitmap?.let { bitmap ->
                updateSharpenedImage(bitmap)
            }
        }

        binding.sharpenedView.setOnTouchListener { _, event ->
            val factory = binding.previewView.meteringPointFactory
            val point = factory.createPoint(event.x, event.y)

            val action = androidx.camera.core.FocusMeteringAction.Builder(point).build()
            camera.cameraControl.startFocusAndMetering(action)

            true
        }

        // Verificar permisos
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
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

            val preview = Preview.Builder()
                .setTargetResolution(Size(640, 480))
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setTargetRotation(binding.previewView.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val bitmap = imageProxy.toBitmap()
                        currentBitmap = bitmap
                        updateSharpenedImage(bitmap)
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

//    private fun startCamera() {
//        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
//
//        cameraProviderFuture.addListener({
//            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
//
//            // Configurar vista previa
//            val preview = Preview.Builder()
//                .setTargetResolution(Size(1280, 720))
//                .build()
//                .also {
//                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
//                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
////                        val blurEffect = RenderEffect.createBlurEffect(
////                            20f, // radiusX
////                            20f, // radiusY
////                            Shader.TileMode.CLAMP
////                        )
////                        binding.previewView.setRenderEffect(blurEffect)
//
////                        val sharpenMatrix = android.graphics.ColorMatrix(
////                            floatArrayOf(
////                                0f, -1f,  0f, 0f, 0f,
////                                -1f,  5f, -1f, 0f, 0f,
////                                0f, -1f,  0f, 0f, 0f,
////                                0f,  0f,  0f, 1f, 0f
////                            )
////                        )
////
////                        val colorFilter = android.graphics.ColorMatrixColorFilter(sharpenMatrix)
////                        val sharpenEffect = RenderEffect.createColorFilterEffect(colorFilter)
////                        binding.previewView.setRenderEffect(sharpenEffect)
//                    }
//                }
//
//            // Seleccionar cámara trasera
//            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
//
//            try {
//                // Limpiar bindings anteriores y volver a enlazar
//                cameraProvider.unbindAll()
//                cameraProvider.bindToLifecycle(
//                    this, cameraSelector, preview
//                )
//            } catch (e: Exception) {
//                Toast.makeText(
//                    this, "Error al iniciar cámara: ${e.message}", Toast.LENGTH_SHORT
//                ).show()
//            }
//        }, ContextCompat.getMainExecutor(this))
//    }

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

        // Calcular la rotación necesaria
        val rotationDegrees = when (this.imageInfo.rotationDegrees) {
            0 -> 90
            90 -> 0
            180 -> 270
            270 -> 180
            else -> 0
        }

        // Aplicar la rotación
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            // Solo para cámara trasera
            if (rotationDegrees == 90) {
                postScale(-1f, 1f)
            }
        }

        return Bitmap.createBitmap(
            originalBitmap,
            0, 0,
            originalBitmap.width,
            originalBitmap.height,
            matrix,
            true
        )
    }

    fun applySharpenFilter(src: Bitmap, factor: Float = 5f): Bitmap {
        // Ajusta el kernel según el factor de nitidez
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
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val newPixels = IntArray(width * height)

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
                Toast.makeText(
                    this, "Permisos no concedidos", Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
