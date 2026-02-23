/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

private const val LOG_TAG = "WatermarkUtils"

/**
 * Format: jam 24 jam | Tanggal Bulan Tahun (e.g. "19:00 | 26 Februari 2026").
 */
private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale("id"))
private val DATE_FORMAT = SimpleDateFormat("d MMMM yyyy", Locale("id"))

object WatermarkUtils {

    /**
     * Fraction of the shorter side of the image used for text size (proportional, kept smaller).
     */
    private const val TEXT_SIZE_FRACTION = 0.018f

    /**
     * Padding from edges as fraction of the shorter side (proportional, not too close).
     */
    private const val PADDING_FRACTION = 0.032f

    /**
     * Draws a timestamp and optional coordinates at the bottom-right. Line 1: jam | tanggal.
     * Line 2 (if location present): koordinat angka saja (e.g. -6.21, 106.85).
     */
    fun drawWatermark(
        bitmap: Bitmap,
        timestampMillis: Long,
        location: Location? = null,
    ): Bitmap {
        val shortSide = minOf(bitmap.width, bitmap.height)
        val textSizePx = (shortSide * TEXT_SIZE_FRACTION).toInt().coerceAtLeast(10)
        val paddingPx = (shortSide * PADDING_FRACTION).toInt().coerceAtLeast(6)
        val lineSpacing = (textSizePx * 0.4f).toInt().coerceAtLeast(2)

        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        val canvas = Canvas(mutableBitmap)

        val timeStr = TIME_FORMAT.format(timestampMillis)
        val dateStr = DATE_FORMAT.format(timestampMillis)
        val line1 = "$timeStr | $dateStr"
        val line2 = location?.let {
            String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude)
        }

        val typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val textSize = textSizePx.toFloat()
        val paintMeasure = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.typeface = typeface
        }
        val textHeight = paintMeasure.fontMetrics.descent - paintMeasure.fontMetrics.ascent
        val xRight = mutableBitmap.width - paddingPx
        val x1 = xRight - paintMeasure.measureText(line1)
        val x2 = line2?.let { xRight - paintMeasure.measureText(it) }
        val yBase = mutableBitmap.height - paddingPx - paintMeasure.fontMetrics.descent
        val y1 = yBase
        val y2 = line2?.let { yBase - textHeight - lineSpacing }

        // Teks putih + outline tipis biar terbaca di background terang/gelap (seperti watermark HP bawaan)
        val strokeWidthPx = (textSizePx * 0.08f).coerceIn(1f, 3f)
        val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.typeface = typeface
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.typeface = typeface
            color = 0xFF000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
        }
        canvas.drawText(line1, x1, y1, paintStroke)
        canvas.drawText(line1, x1, y1, paintFill)
        if (line2 != null && x2 != null && y2 != null) {
            canvas.drawText(line2, x2, y2, paintStroke)
            canvas.drawText(line2, x2, y2, paintFill)
        }

        return mutableBitmap
    }

    /**
     * Applies timestamp and optional location watermark to JPEG bytes.
     * Preserves EXIF orientation. Call from IO dispatcher.
     *
     * @return Watermarked JPEG bytes, or null on failure.
     */
    fun applyWatermarkToJpegBytes(
        jpegBytes: ByteArray,
        timestampMillis: Long,
        location: Location? = null,
    ): ByteArray? {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                jpegBytes, 0, jpegBytes.size
            ) ?: return null
            val rotated = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270)
                else -> bitmap
            }
            if (rotated != bitmap) bitmap.recycle()
            val watermarked = drawWatermark(rotated, timestampMillis, location)
            if (watermarked != rotated) rotated.recycle()
            ByteArrayOutputStream().use { out ->
                watermarked.compress(Bitmap.CompressFormat.JPEG, 95, out)
                watermarked.recycle()
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to apply watermark to bytes", e)
            null
        }
    }

    /**
     * Loads an image from [uri], draws the timestamp and optional location watermark,
     * and writes the result back. Runs on IO dispatcher. Preserves EXIF orientation.
     *
     * @return true if watermark was applied and written successfully, false otherwise.
     */
    suspend fun applyWatermarkToUri(
        uri: Uri,
        timestampMillis: Long,
        contentResolver: android.content.ContentResolver,
        location: Location? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val exif = ExifInterface(ByteArrayInputStream(bytes))
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)

                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext false
                val rotated = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90)
                    ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180)
                    ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270)
                    else -> bitmap
                }
                if (rotated != bitmap) bitmap.recycle()

                val watermarked = drawWatermark(rotated, timestampMillis, location)
                if (watermarked != rotated) rotated.recycle()

                val outputStream = ByteArrayOutputStream()
                watermarked.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                watermarked.recycle()

                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(outputStream.toByteArray())
                } ?: return@withContext false
                true
            } ?: false
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to apply watermark", e)
            false
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
    }
}
