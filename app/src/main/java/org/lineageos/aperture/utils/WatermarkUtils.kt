/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
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
    private const val PADDING_FRACTION = 0.035f

    fun drawWatermark(
        bitmap: Bitmap,
        timestampMillis: Long,
        location: Location? = null,
        watermarkManualControl: Boolean,
        watermarkCustomText: String,
        watermarkShowDate: Boolean,
        watermarkShowTime: Boolean,
        watermarkShowLocation: Boolean,
        watermarkShowAddress: Boolean,
        watermarkShowDeviceName: Boolean,
        watermarkShowBackground: Boolean,
        watermarkTextSize: Float,
        watermarkTextColor: Int,
        watermarkFont: String,
        address: String?,
    ): Bitmap {
        val shortSide = minOf(bitmap.width, bitmap.height)

        val textSizePx = shortSide * watermarkTextSize
        val paddingPx = (shortSide * PADDING_FRACTION).toInt()
        val lineSpacing = (textSizePx * 0.3f).toInt()

        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        val canvas = Canvas(mutableBitmap)

        val timeStr = TIME_FORMAT.format(timestampMillis)
        val dateStr = DATE_FORMAT.format(timestampMillis)

        // Right side: Date/Time (Top), Coordinates (Bottom)
        val lineRight1 = when {
            watermarkShowDate && watermarkShowTime -> "$timeStr | $dateStr"
            watermarkShowDate -> dateStr
            watermarkShowTime -> timeStr
            else -> null
        }
        val lineRight2 = if (watermarkShowLocation) location?.let {
            String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude)
        } else null

        // Left side: Device Name (Top), Address (Bottom)
        val deviceName = watermarkCustomText.ifEmpty { Build.MODEL }
        val lineLeft1 = if (watermarkShowDeviceName) deviceName else null
        val lineLeft2 = if (watermarkShowAddress) address else null

        val typeface = try {
            Typeface.create(watermarkFont, Typeface.BOLD)
        } catch (e: Exception) {
            Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSizePx
            this.typeface = typeface
            color = watermarkTextColor
            style = Paint.Style.FILL
        }
        val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSizePx
            this.typeface = typeface
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = (textSizePx * 0.1f).coerceIn(1f, 3f)
        }

        val fontMetrics = paintFill.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent

        // Y positions logic: Dynamic per side
        val yBase2 = mutableBitmap.height - paddingPx - fontMetrics.descent
        val yTopIfTwoLines = yBase2 - textHeight - lineSpacing

        // Draw Background
        if (watermarkShowBackground) {
            val hasAnyText = lineLeft1 != null || lineLeft2 != null || lineRight1 != null || lineRight2 != null
            if (hasAnyText) {
                val bgPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }
                
                // Determine if we need background for 1 or 2 lines based on the side with most lines
                val maxLines = if (lineLeft2 != null || lineRight2 != null) 2 else 1
                val top = if (maxLines == 2) yTopIfTwoLines + fontMetrics.ascent - paddingPx / 4
                          else yBase2 + fontMetrics.ascent - paddingPx / 4
                val bottom = yBase2 + fontMetrics.descent + paddingPx / 4

                val rect = Rect(0, top.toInt(), mutableBitmap.width, bottom.toInt())
                canvas.drawRect(rect, bgPaint)
            }
        }

        // Draw Right Watermark
        val xRight = mutableBitmap.width - paddingPx
        val yRight = if (lineRight2 != null) yTopIfTwoLines else yBase2
        lineRight1?.let {
            canvas.drawText(it, xRight - paintFill.measureText(it), yRight, paintStroke)
            canvas.drawText(it, xRight - paintFill.measureText(it), yRight, paintFill)
        }
        lineRight2?.let {
            canvas.drawText(it, xRight - paintFill.measureText(it), yBase2, paintStroke)
            canvas.drawText(it, xRight - paintFill.measureText(it), yBase2, paintFill)
        }

        // Draw Left Watermark
        val xLeft = paddingPx.toFloat()
        val yLeft = if (lineLeft2 != null) yTopIfTwoLines else yBase2
        lineLeft1?.let {
            canvas.drawText(it, xLeft, yLeft, paintStroke)
            canvas.drawText(it, xLeft, yLeft, paintFill)
        }
        lineLeft2?.let {
            canvas.drawText(it, xLeft, yBase2, paintStroke)
            canvas.drawText(it, xLeft, yBase2, paintFill)
        }

        return mutableBitmap
    }

    private fun getAddressFromLocation(context: Context, location: Location?): String? {
        if (location == null) return null
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            val address = addresses?.firstOrNull() ?: return null
            
            val street = address.thoroughfare // Nama Jalan
            val houseNumber = address.subThoroughfare // Nomor Rumah/Gedung
            
            if (street != null) {
                if (houseNumber != null) "$street No. $houseNumber" else street
            } else {
                // Jika jalan tidak ditemukan, ambil area yang lebih kecil agar tetap pendek
                address.subLocality ?: address.locality ?: address.getAddressLine(0).take(30)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to get address from location", e)
            null
        }
    }

    /**
     * Applies timestamp and optional location watermark to JPEG bytes.
     */
    fun applyWatermarkToJpegBytes(
        context: Context,
        jpegBytes: ByteArray,
        timestampMillis: Long,
        location: Location? = null,
        watermarkManualControl: Boolean = false,
        watermarkCustomText: String = "",
        watermarkShowDate: Boolean = true,
        watermarkShowTime: Boolean = true,
        watermarkShowLocation: Boolean = true,
        watermarkShowAddress: Boolean = false,
        watermarkShowDeviceName: Boolean = true,
        watermarkShowBackground: Boolean = false,
        watermarkTextSize: Float = 0.028f,
        watermarkTextColor: Int = Color.WHITE,
        watermarkFont: String = "sans-serif",
    ): ByteArray? {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
            val rotated = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270)
                else -> bitmap
            }
            if (rotated != bitmap) bitmap.recycle()

            val address = if (watermarkShowAddress) getAddressFromLocation(context, location) else null

            val watermarked = drawWatermark(
                rotated, timestampMillis, location, watermarkManualControl,
                watermarkCustomText, watermarkShowDate, watermarkShowTime, watermarkShowLocation,
                watermarkShowAddress, watermarkShowDeviceName, watermarkShowBackground,
                watermarkTextSize, watermarkTextColor, watermarkFont, address
            )
            if (watermarked != rotated) rotated.recycle()
            ByteArrayOutputStream().use { out ->
                watermarked.compress(Bitmap.CompressFormat.JPEG, 95, out)
                watermarked.recycle()
                
                // Copy EXIF from original bytes to watermarked bytes
                val watermarkedBytes = out.toByteArray()
                val newExif = ExifInterface(ByteArrayInputStream(watermarkedBytes))
                
                // Copy relevant attributes
                val tags = arrayOf(
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_FLASH,
                    ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                    ExifInterface.TAG_WHITE_BALANCE
                )
                
                for (tag in tags) {
                    exif.getAttribute(tag)?.let { value ->
                        newExif.setAttribute(tag, value)
                    }
                }
                
                watermarkedBytes
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to apply watermark to bytes", e)
            null
        }
    }

    /**
     * Loads an image from [uri] and applies watermark.
     */
    suspend fun applyWatermarkToUri(
        uri: Uri,
        timestampMillis: Long,
        contentResolver: android.content.ContentResolver,
        context: Context,
        location: Location? = null,
        watermarkManualControl: Boolean = false,
        watermarkCustomText: String = "",
        watermarkShowDate: Boolean = true,
        watermarkShowTime: Boolean = true,
        watermarkShowLocation: Boolean = true,
        watermarkShowAddress: Boolean = false,
        watermarkShowDeviceName: Boolean = true,
        watermarkShowBackground: Boolean = false,
        watermarkTextSize: Float = 0.028f,
        watermarkTextColor: Int = Color.WHITE,
        watermarkFont: String = "sans-serif",
    ): Boolean = withContext(Dispatchers.IO) {
        val address = if (watermarkShowAddress) getAddressFromLocation(context, location) else null

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val exif = ExifInterface(ByteArrayInputStream(bytes))
                
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext false
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                val rotated = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90)
                    ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180)
                    ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270)
                    else -> bitmap
                }
                if (rotated != bitmap) bitmap.recycle()
                
                val watermarked = drawWatermark(
                    rotated, timestampMillis, location, watermarkManualControl,
                    watermarkCustomText, watermarkShowDate, watermarkShowTime, watermarkShowLocation,
                    watermarkShowAddress, watermarkShowDeviceName, watermarkShowBackground,
                    watermarkTextSize, watermarkTextColor, watermarkFont, address
                )
                if (watermarked != rotated) rotated.recycle()
                
                val outputStream = ByteArrayOutputStream()
                watermarked.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                val watermarkedBytes = outputStream.toByteArray()
                watermarked.recycle()
                
                // Write watermarked bytes back
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(watermarkedBytes)
                } ?: return@withContext false
                
                // RE-APPLY EXIF after writing file
                contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val newExif = ExifInterface(pfd.fileDescriptor)
                    
                    val tags = arrayOf(
                        ExifInterface.TAG_MAKE,
                        ExifInterface.TAG_MODEL,
                        ExifInterface.TAG_DATETIME,
                        ExifInterface.TAG_FLASH,
                        ExifInterface.TAG_FOCAL_LENGTH,
                        ExifInterface.TAG_GPS_LATITUDE,
                        ExifInterface.TAG_GPS_LONGITUDE,
                        ExifInterface.TAG_GPS_LATITUDE_REF,
                        ExifInterface.TAG_GPS_LONGITUDE_REF,
                        ExifInterface.TAG_EXPOSURE_TIME,
                        ExifInterface.TAG_F_NUMBER,
                        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                        ExifInterface.TAG_WHITE_BALANCE,
                        ExifInterface.TAG_ORIENTATION // Set to normal as we already rotated the bitmap
                    )
                    
                    for (tag in tags) {
                        if (tag == ExifInterface.TAG_ORIENTATION) {
                            newExif.setAttribute(tag, ExifInterface.ORIENTATION_NORMAL.toString())
                        } else {
                            exif.getAttribute(tag)?.let { value ->
                                newExif.setAttribute(tag, value)
                            }
                        }
                    }
                    newExif.saveAttributes()
                }

                true
            } ?: false
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to apply watermark", e)
            false
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
