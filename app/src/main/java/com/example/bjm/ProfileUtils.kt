package com.example.bjm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

object ProfileUtils {
    fun encodeImage(bitmap: Bitmap): String {
        // Resize to tiny thumbnail for MQTT efficiency
        val scaled = Bitmap.createScaledBitmap(bitmap, 120, 120, true)
        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    fun decodeImage(base64: String?): Bitmap? {
        if (base64 == null) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }
}
