package com.eduqode.app.offline

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one file to [target], resuming a partial ".part" file with an HTTP Range request.
 * Plain Java networking (no Android classes), so it is covered by JVM unit tests with a real HTTP server.
 *
 * Throws IOException for problems worth retrying later (network lost, server busy, incomplete data).
 */
class FileDownloader(private val userAgent: String) {

    sealed class Outcome {
        data class Done(val size: Long) : Outcome()
        data class Failed(val reason: String) : Outcome()       // permanent (404, 403, ...)
        data class Stopped(val doneBytes: Long) : Outcome()     // paused / cancelled / system stop
    }

    fun download(
        url: String,
        target: File,
        expectedBytes: Long,
        shouldStop: () -> Boolean,
        onProgress: (done: Long, total: Long) -> Unit
    ): Outcome {
        val part = File(target.path + ".part")
        target.parentFile?.mkdirs()

        if (target.exists() && expectedBytes > 0 && target.length() == expectedBytes) return Outcome.Done(target.length())

        var start = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Encoding", "identity")
            if (start > 0) setRequestProperty("Range", "bytes=$start-")
        }
        try {
            val code = conn.responseCode
            when {
                code == 416 && start > 0 -> {
                    if (expectedBytes > 0 && part.length() == expectedBytes) {
                        return save(part, target)
                    }
                    part.delete()
                    throw IOException("Range not satisfiable, starting again")
                }
                code in 500..599 || code == 429 || code == 408 -> throw IOException("Server busy ($code)")
                code !in 200..299 -> return Outcome.Failed("Server answered $code")
            }

            // A web page instead of a file: wrong link, file not shared publicly, or (Google Drive)
            // the file's daily download limit was reached.
            val wantsHtml = target.name.lowercase().let { it.endsWith(".html") || it.endsWith(".htm") }
            if (!wantsHtml && conn.contentType?.lowercase()?.startsWith("text/html") == true) {
                return Outcome.Failed(
                    if (url.contains("google.com")) "Google Drive did not send the file. Check it is shared with “Anyone with the link”, or try again tomorrow (Drive download limit)."
                    else "The link opens a web page, not a file."
                )
            }

            var append = false
            if (code == 206) {
                append = OfflineLogic.contentRangeStart(conn.getHeaderField("Content-Range")) == start
                if (!append) start = 0
            } else {
                start = 0 // the server ignored the Range header: start from the beginning
            }
            val length = conn.contentLengthLong
            val total = if (length >= 0) start + length else expectedBytes

            var done = start
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (shouldStop()) return Outcome.Stopped(done)
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }

            // Integrity: exactly as many bytes as the server announced.
            if (total > 0 && part.length() != total) {
                if (part.length() > total) part.delete()
                throw IOException("Incomplete file (${part.length()} of $total bytes)")
            }
            return save(part, target)
        } finally {
            conn.disconnect()
        }
    }

    private fun save(part: File, target: File): Outcome {
        if (target.exists()) target.delete()
        return if (part.renameTo(target)) Outcome.Done(target.length()) else Outcome.Failed("Could not save the file")
    }
}
