package com.eduqode.app.offline

import java.security.MessageDigest
import java.util.Locale

/** Small pure functions used by the offline system. Covered by unit tests. */
object OfflineLogic {

    /** Stable file name for a URL, so the same file is never stored twice in a course. */
    fun fileNameFor(url: String, fallbackExt: String = "bin"): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(20)
        val path = url.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        val ext = last.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
            ?: fallbackExt
        return "$hash.$ext"
    }

    /**
     * Points every downloaded image in the lesson HTML at its local copy
     * (a path relative to the course folder).
     */
    fun rewriteImages(html: String, localNames: Map<String, String>): String {
        var out = html
        for ((url, local) in localNames.entries.sortedByDescending { it.key.length }) {
            val encoded = url.replace("&", "&amp;")
            out = out.replace(encoded, "media/$local")
            if (encoded != url) out = out.replace(url, "media/$local")
        }
        return out
    }

    /** Full page for a lesson or course description, readable on a phone, no scripts. */
    fun wrapHtml(title: String, body: String): String = """
<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'self' https://appassets.androidplatform.net; img-src 'self' https://appassets.androidplatform.net data:; style-src 'unsafe-inline'; script-src 'none'">
<style>
  body{margin:0;padding:16px 16px 32px;font-family:system-ui,-apple-system,Roboto,sans-serif;font-size:16px;line-height:1.6;color:#1b1b1b;background:#fff;word-wrap:break-word}
  h1{font-size:22px;line-height:1.3;margin:0 0 12px}
  img,video{max-width:100%;height:auto;border-radius:8px}
  iframe{display:none}
  pre,code{background:#f4f4f4;border-radius:6px;font-size:14px}
  pre{padding:12px;overflow-x:auto}
  table{border-collapse:collapse;max-width:100%;display:block;overflow-x:auto}
  td,th{border:1px solid #ddd;padding:6px}
  a{color:#b26b00}
  @media (prefers-color-scheme: dark){body{background:#121212;color:#e8e8e8}pre,code{background:#222}a{color:#F5A623}td,th{border-color:#444}}
</style></head>
<body><h1>${escape(title)}</h1>
$body
</body></html>
""".trimIndent()

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Local lesson state used for merging. */
    data class LocalLessonState(
        val lessonId: Long,
        val completedAt: Long,
        val positionMs: Long,
        val durationMs: Long,
        val positionAt: Long,
        val hasPendingPosition: Boolean
    )

    data class LessonUpdate(
        val lessonId: Long,
        val completedAt: Long?,                       // set when it changes
        val position: ServerPosition?                 // set when the server's is newer
    )

    /**
     * Merges server progress into local progress without ever losing newer data:
     *  - a completed lesson stays completed (completion from any device counts);
     *  - the newer video position wins, and a position not yet sent from this phone is kept.
     */
    fun mergeProgress(local: List<LocalLessonState>, server: ServerProgress): List<LessonUpdate> {
        val updates = ArrayList<LessonUpdate>()
        for (l in local) {
            var completed: Long? = null
            val serverDone = server.completed[l.lessonId]
            if (l.completedAt == 0L && serverDone != null && serverDone > 0) {
                completed = serverDone
            }
            var position: ServerPosition? = null
            val sp = server.positions[l.lessonId]
            if (sp != null && !l.hasPendingPosition && sp.updatedAt > l.positionAt) {
                position = sp
            }
            if (completed != null || position != null) updates += LessonUpdate(l.lessonId, completed, position)
        }
        return updates
    }

    /** Which "last opened lesson" to keep. */
    fun newerLastLesson(localId: Long, localAt: Long, serverId: Long, serverAt: Long): Pair<Long, Long> =
        if (serverId > 0 && serverAt > localAt) serverId to serverAt else localId to localAt

    /** Event statuses after which the event is finished and leaves the queue. */
    fun isFinal(status: String) = status in setOf("applied", "already", "duplicate", "stale", "rejected")

    /** Bytes needed to finish, plus a safety margin, compared with free space. */
    fun hasRoom(remainingBytes: Long, freeBytes: Long, marginBytes: Long = 50L * 1024 * 1024) =
        freeBytes >= remainingBytes + marginBytes

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024) else String.format(Locale.US, "%.1f MB", mb)
    }

    /** Parses "bytes 100-199/1000" (Content-Range) and returns the start offset, or -1. */
    fun contentRangeStart(header: String?): Long {
        if (header == null) return -1
        val m = Regex("""bytes\s+(\d+)-\d+/(\d+|\*)""").find(header) ?: return -1
        return m.groupValues[1].toLongOrNull() ?: -1
    }
}
