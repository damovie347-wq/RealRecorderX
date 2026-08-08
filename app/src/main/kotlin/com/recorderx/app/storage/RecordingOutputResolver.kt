package com.recorderx.app.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On Android 10+ (API 29), inserting into MediaStore's Video collection needs
 * *no storage permission at all* and is visible to the user in Movies/RecorderX
 * immediately -- so that's the only path used there. Android 8/9 predate
 * scoped storage, so those two versions fall back to writing the public
 * Movies/RecorderX directory directly (requires the legacy
 * WRITE_EXTERNAL_STORAGE permission, already scoped to maxSdkVersion=28 in
 * the manifest).
 */
object RecordingOutputResolver {

    class Output(
        val fileDescriptor: FileDescriptor,
        private val pfd: ParcelFileDescriptor,
        private val mediaStoreUri: Uri?,
        private val legacyFile: File?
    ) {
        /** Call after the muxer has been released. Returns the final,
         * shareable/openable content Uri once the file is fully written and
         * (for the legacy path) indexed by the media scanner. */
        fun finalizeAndGetUri(context: Context, onReady: (Uri?) -> Unit) {
            try { pfd.close() } catch (e: Exception) { /* already closed */ }

            if (mediaStoreUri != null) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                try {
                    context.contentResolver.update(mediaStoreUri, values, null, null)
                    onReady(mediaStoreUri)
                } catch (e: Exception) {
                    onReady(null)
                }
                return
            }

            val file = legacyFile ?: return onReady(null)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("video/mp4")
            ) { _, scannedUri -> onReady(scannedUri) }
        }
    }

    fun createOutputTarget(context: Context, outputTemplate: String): Output? {
        val fileName = resolveFileName(outputTemplate)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createViaMediaStore(context, fileName)
        } else {
            createLegacyFile(fileName)
        }
    }

    /** Exposed for MainActivity's live preview under the Output Template field. */
    fun resolveFileName(template: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val withTimestamp = when {
            template.isBlank() -> timestamp
            template.contains(PLACEHOLDER) -> template.replace(PLACEHOLDER, timestamp)
            // No placeholder in an otherwise non-blank template: keep the user's
            // prefix but still append the timestamp, so two recordings never
            // silently collide/overwrite each other on the legacy (pre-scoped-
            // storage) save path.
            else -> "${template}_$timestamp"
        }
        val sanitized = sanitizeFileName(withTimestamp)
        return sanitized.ifBlank { timestamp }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(INVALID_FILENAME_CHARS, "").trim()

    private fun createViaMediaStore(context: Context, fileName: String): Output? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$fileName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RecorderX")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values) ?: return null
        val pfd = context.contentResolver.openFileDescriptor(uri, "w") ?: return null
        return Output(pfd.fileDescriptor, pfd, mediaStoreUri = uri, legacyFile = null)
    }

    private fun createLegacyFile(fileName: String): Output? {
        @Suppress("DEPRECATION")
        val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "RecorderX")
        if (!moviesDir.exists() && !moviesDir.mkdirs()) return null
        val file = File(moviesDir, "$fileName.mp4")
        val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        )
        return Output(pfd.fileDescriptor, pfd, mediaStoreUri = null, legacyFile = file)
    }

    private const val PLACEHOLDER = "{timestamp}"
    private val INVALID_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|]")
}
