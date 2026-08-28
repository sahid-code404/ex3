package com.sahidcode404.camera.core.camera.capture

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.sahidcode404.camera.core.camera.model.IntSizeValue
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

internal data class PublishedDng(
    val uri: Uri,
    val displayName: String,
)

internal class RawDngMediaStoreWriter(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    @Throws(IOException::class, SecurityException::class)
    fun write(
        captureId: Long,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        image: Image,
        size: IntSizeValue,
    ): PublishedDng {
        val displayName = String.format(
            Locale.US,
            "Camera_%d_%06d.dng",
            System.currentTimeMillis(),
            captureId % 1_000_000L,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeScoped(displayName, characteristics, result, image, size)
        } else {
            writeLegacy(displayName, characteristics, result, image, size)
        }
    }

    private fun writeScoped(
        displayName: String,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        image: Image,
        size: IntSizeValue,
    ): PublishedDng {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, DNG_MIME_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, "$PUBLIC_DIRECTORY/$CAMERA_DIRECTORY")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.WIDTH, size.width)
            put(MediaStore.Images.Media.HEIGHT, size.height)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused RAW DNG insertion")
        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("MediaStore RAW DNG output stream unavailable")
            output.use { stream -> writeDng(stream, characteristics, result, image) }
            val publish = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, publish, null, null)
            return PublishedDng(uri, displayName)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(
        displayName: String,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        image: Image,
        size: IntSizeValue,
    ): PublishedDng {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val directory = File(dcim, CAMERA_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create public Camera directory")
        }
        val file = File(directory, displayName)
        try {
            FileOutputStream(file).use { stream -> writeDng(stream, characteristics, result, image) }
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.TITLE, displayName.removeSuffix(".dng"))
                put(MediaStore.Images.Media.MIME_TYPE, DNG_MIME_TYPE)
                put(MediaStore.Images.Media.DATA, file.absolutePath)
                put(MediaStore.Images.Media.SIZE, file.length())
                put(MediaStore.Images.Media.WIDTH, size.width)
                put(MediaStore.Images.Media.HEIGHT, size.height)
                put(MediaStore.Images.Media.DATE_ADDED, now / 1000L)
                put(MediaStore.Images.Media.DATE_TAKEN, now)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore refused legacy RAW DNG insertion")
            return PublishedDng(uri, displayName)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun writeDng(
        output: OutputStream,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        image: Image,
    ) {
        val creator = DngCreator(characteristics, result)
        try {
            creator.writeImage(output, image)
        } finally {
            creator.close()
        }
    }

    private companion object {
        const val CAMERA_DIRECTORY = "Camera"
        val PUBLIC_DIRECTORY: String = Environment.DIRECTORY_DCIM
        const val DNG_MIME_TYPE = "image/x-adobe-dng"
    }
}
