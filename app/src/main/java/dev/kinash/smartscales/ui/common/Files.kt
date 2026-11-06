package dev.kinash.smartscales.ui.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Up to [limit] bytes of [stream]; `readNBytes` needs API 33. */
fun readAtMost(stream: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** The name the person sees for a picked file, or the last part of its address when the provider gives none. */
fun displayName(context: Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
}
