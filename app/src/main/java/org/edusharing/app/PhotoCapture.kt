package org.edusharing.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native camera capture support for `eduBridge.takePhoto()`
 * (docs/android-app/eduBridge-contract.md §5): writes the system camera's output to a
 * cache file, then reads it back as an EXIF-corrected, size-capped JPEG base64 string.
 */
object PhotoCapture {
    private const val IMAGES_SUBDIR = "images"
    // Longer-side cap so a raw ~12+ MP camera photo doesn't blow up bitmap memory or the
    // base64 payload handed to evaluateJavascript.
    private const val MAX_DIMENSION = 2048
    private const val JPEG_QUALITY = 85

    /** Creates an empty temp file the camera app will write its JPEG into. */
    fun createTempImageFile(context: Context): File {
        val dir = File(context.cacheDir, IMAGES_SUBDIR).apply { mkdirs() }
        val name = "photo_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        return File(dir, name)
    }

    fun uriForFile(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun deleteQuietly(file: File) {
        runCatching { file.delete() }
    }

    /**
     * Reads [file] (a JPEG written by the camera app), applies its EXIF rotation, downsamples
     * to [MAX_DIMENSION] on the longer side if needed, and returns it re-encoded as base64
     * JPEG. Returns `null` if the file can't be decoded (corrupt / empty capture).
     */
    fun readAsRotatedJpegBase64(file: File): String? {
        if (!file.exists() || file.length() == 0L) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val rotated = rotateIfRequired(decoded, orientation)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (rotated !== decoded) decoded.recycle()
        rotated.recycle()

        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    private fun rotateIfRequired(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
