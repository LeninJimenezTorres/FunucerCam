package com.example.funucercam.processing.image.parameters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.camera.core.Camera
import com.example.funucercam.databinding.ActivityMainBinding

fun applyTemperatureFilter(bitmap: Bitmap, temperature: Float): Bitmap {
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

fun applySaturationFilter(bitmap: Bitmap, saturation: Float): Bitmap {
    val bmp = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(bmp)
    val paint = Paint()

    val colorMatrix = ColorMatrix()
    colorMatrix.setSaturation(saturation.coerceIn(0f, 2f))

    paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
    canvas.drawBitmap(bmp, 0f, 0f, paint)

    return bmp
}

fun setupExposureSlider(camera: Camera, binding: ActivityMainBinding) {
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
