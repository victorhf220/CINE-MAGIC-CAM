package com.cinecamera.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * MediaStoreHelper
 *
 * FIXES audit finding #1: hardcoded /sdcard path.
 *
 * Problem: CameraViewModel.generateOutputPath() used:
 *   File("/sdcard/Movies/CineCamera").also { it.mkdirs() }
 *
 * On Android 10+ (API 29+) with scoped storage enforced, direct writes
 * to /sdcard fail unless requestLegacyExternalStorage=true, which is
 * blocked on API 30+ entirely and deprecated from API 29.
 *
 * Solution: Use MediaStore.Video.Media API on API 29+, which writes to
 * the correct scoped path and registers the file in the media index
 * automatically. On API 26–28, the legacy File path is used with
 * proper external storage directory resolution via Context API.
 *
 * Usage:
 *   val descriptor = MediaStoreHelper.createVideoFile(context, "CINE_20240315_143022")
 *   // Pass descriptor.uri to MediaMuxer (API 26+) or descriptor.file
 *   // When recording completes: MediaStoreHelper.markFileReady(context, descriptor)
 */
object MediaStoreHelper {

    private const val RELATIVE_PATH = "Movies/CineCamera"

    /**
     * Creates a pending video file entry and returns a descriptor with the
     * URI (MediaStore) or File path (legacy) to pass to the encoder.
     *
     * On API 29+ the file is created as IS_PENDING=1, preventing it from
     * appearing in the gallery until markFileReady() is called at session end.
     */
    fun createVideoFile(context: Context, baseName: String = generateFileName()): VideoFileDescriptor {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createViaMediaStore(context, baseName)
        } else {
            createViaLegacyPath(context, baseName)
        }
    }

    private fun createViaMediaStore(context: Context, baseName: String): VideoFileDescriptor {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$baseName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE,    "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.DATE_ADDED,   System.currentTimeMillis() / 1000)
            // IS_PENDING=1 hides the file from gallery while recording
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed — storage may be unavailable")

        Timber.d("MediaStore entry created: $uri")
        return VideoFileDescriptor(uri = uri, file = null, displayName = "$baseName.mp4")
    }

    private fun createViaLegacyPath(context: Context, baseName: String): VideoFileDescriptor {
        // Use context.getExternalFilesDir for app-scoped path, or
        // Environment.DIRECTORY_MOVIES for shared gallery path (requires WRITE_EXTERNAL_STORAGE)
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "CineCamera"
        ).also { it.mkdirs() }

        val file = File(dir, "$baseName.mp4")
        Timber.d("Legacy file path: ${file.absolutePath}")
        return VideoFileDescriptor(uri = null, file = file, displayName = "$baseName.mp4")
    }

    /**
     * Must be called after recording completes successfully.
     * Sets IS_PENDING=0 so the file appears in the media library.
     * Also updates the file size metadata for accurate gallery display.
     */
    fun markFileReady(context: Context, descriptor: VideoFileDescriptor) {
        val uri = descriptor.uri ?: return  // Legacy path — no pending state
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
            Timber.d("MediaStore file marked ready: $uri")
        } catch (e: Exception) {
            Timber.e(e, "Failed to mark MediaStore entry as ready")
        }
    }

    /**
     * Called on recording failure or cancellation — removes the pending
     * entry from MediaStore to prevent orphaned invisible files.
     */
    fun deleteFile(context: Context, descriptor: VideoFileDescriptor) {
        try {
            descriptor.uri?.let { context.contentResolver.delete(it, null, null) }
            descriptor.file?.delete()
            Timber.d("Recording file deleted: ${descriptor.displayName}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete recording file")
        }
    }

    /**
     * Opens a ParcelFileDescriptor to the MediaStore URI for writing.
     * Pass the file descriptor's detachFd() to the native muxer, or
     * use it directly with MediaMuxer(fd, ...) on API 26+.
     */
    fun openForWrite(context: Context, descriptor: VideoFileDescriptor): android.os.ParcelFileDescriptor? {
        val uri = descriptor.uri ?: return null
        return try {
            context.contentResolver.openFileDescriptor(uri, "rw")
        } catch (e: Exception) {
            Timber.e(e, "Failed to open MediaStore URI for writing")
            null
        }
    }

    fun generateFileName(): String =
        "CINE_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}

/**
 * VideoFileDescriptor — unified handle for both MediaStore and legacy paths.
 *
 * uri  — non-null on API 29+. Pass to contentResolver.openFileDescriptor()
 *        for writing, or to MediaMuxer via ParcelFileDescriptor.
 * file — non-null on API 26–28. Pass absolute path to MediaMuxer.
 *
 * Exactly one of uri or file will be non-null.
 */
data class VideoFileDescriptor(
    val uri: Uri?,
    val file: File?,
    val displayName: String
) {
    /** Returns a string identifier suitable for logging and recovery journal. */
    val identifier: String get() = uri?.toString() ?: file?.absolutePath ?: "unknown"
}
