package com.example.capture

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

/**
 * Safely converts an Android [Image] (`PixelFormat.RGBA_8888`) into an in-memory [Bitmap]
 * while strictly respecting `pixelStride`, `rowStride`, and arbitrary `rowPadding`
 * (including when `rowPadding` is not a multiple of `pixelStride`), and guaranteeing
 * `image.close()` in a `finally` block.
 */
object ImageFrameConverter {

    /**
     * Acquires pixel data from [image] and always closes [image] inside `finally`.
     */
    fun acquireBitmapAndClose(image: Image): Bitmap {
        try {
            val width = image.width
            val height = image.height
            val planes = image.planes
            require(planes != null && planes.isNotEmpty()) { "Image planes 为空" }
            val plane = planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            return rgbaBufferToBitmap(
                buffer = buffer,
                width = width,
                height = height,
                pixelStride = pixelStride,
                rowStride = rowStride
            )
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Converts an `RGBA_8888` [ByteBuffer] with arbitrary [pixelStride] and [rowStride]
     * (where `rowPadding = rowStride - pixelStride * width`) into an `ARGB_8888` [Bitmap].
     */
    fun rgbaBufferToBitmap(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int
    ): Bitmap {
        val argbPixels = rgbaBufferToArgbIntArray(
            buffer = buffer,
            width = width,
            height = height,
            pixelStride = pixelStride,
            rowStride = rowStride
        )
        return Bitmap.createBitmap(argbPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Pure buffer-to-ARGB extraction handling `pixelStride`, `rowStride`, and `rowPadding`.
     */
    fun rgbaBufferToArgbIntArray(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int
    ): IntArray {
        require(width > 0 && height > 0) { "非法图像尺寸: ${width}x$height" }
        require(pixelStride >= 4) { "非法 pixelStride: $pixelStride (RGBA_8888 至少为 4)" }
        require(rowStride >= width * pixelStride) {
            "非法 rowStride: $rowStride < width($width) * pixelStride($pixelStride)"
        }

        val dup = buffer.duplicate()
        dup.rewind()
        val requiredBytes = (height - 1) * rowStride + (width - 1) * pixelStride + 4
        require(dup.remaining() >= requiredBytes) {
            "图像缓冲区字节不足: remaining=${dup.remaining()}, required=$requiredBytes"
        }

        val outArgb = IntArray(width * height)
        val rowBytes = ByteArray(width * pixelStride)
        var outIdx = 0

        for (y in 0 until height) {
            val rowStartOffset = y * rowStride
            dup.position(rowStartOffset)
            dup.get(rowBytes, 0, width * pixelStride)

            var byteOff = 0
            for (x in 0 until width) {
                val r = rowBytes[byteOff].toInt() and 0xFF
                val g = rowBytes[byteOff + 1].toInt() and 0xFF
                val b = rowBytes[byteOff + 2].toInt() and 0xFF
                val a = rowBytes[byteOff + 3].toInt() and 0xFF
                outArgb[outIdx++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                byteOff += pixelStride
            }
        }
        return outArgb
    }
}
