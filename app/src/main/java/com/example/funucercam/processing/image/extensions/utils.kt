package com.example.funucercam.processing.image.extensions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

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

fun showToast(message: String, context: Context) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    } else {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
