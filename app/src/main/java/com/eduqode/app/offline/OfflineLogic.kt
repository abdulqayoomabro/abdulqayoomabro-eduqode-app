package com.eduqode.app.offline

import java.security.MessageDigest
import java.util.Locale

/** Small pure functions used by the offline system. Covered by unit tests. */
object OfflineLogic {

    /** Download links like "stream.php?f=x.mp4" are not saved with a script extension. */
    private val scriptExtensions = setOf("php", "asp", "aspx", "jsp", "cgi", "html", "htm")

    /** Stable file name for a URL, so the same file is never stored twice in a course. */
    fun fileNameFor(url: String, fallbackExt: String = "bin"): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(20)
        val path = url.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        val ext = last.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } && it !in scriptExtensions }
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

    private val driveId = listOf(
        Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{10,})"""),
        Regex("""(?:drive|docs)\.google\.com/(?:open|uc)\?(?:.*&)?id=([A-Za-z0-9_-]{10,})"""),
        Regex("""drive\.usercontent\.google\.com/download\?(?:.*&)?id=([A-Za-z0-9_-]{10,})""")
    )

    /** Google Drive file ID from a share link, or null if the link is not a Drive file link. */
    fun googleDriveId(url: String): String? = driveId.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }

    /**
     * The link the app actually downloads from. A Google Drive share link ("Anyone with the link")
     * opens a web page, so it is turned into Drive's direct download link (confirm=t skips the
     * "can't scan for viruses" page shown for large files). Other links are used as they are.
     */
    fun directDownloadUrl(url: String): String {
        val id = googleDriveId(url) ?: return url
        return "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
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

    // ---------- H5P ----------

    /** A path from the server that stays inside its folder (no "..", no absolute paths). */
    fun safeRelativePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith("/") && !path.contains('\\') && !path.contains('\u0000') &&
            path.split('/').none { it == ".." || it == "." || it.isEmpty() }

    fun h5pItemsJson(items: List<H5PItem>): String = org.json.JSONArray().apply {
        items.forEach { put(org.json.JSONObject().put("id", it.id).put("title", it.title).put("library", it.library).put("offline", it.offline)) }
    }.toString()

    fun parseH5PItems(json: String): List<H5PItem> = if (json.isBlank()) emptyList() else runCatching {
        val a = org.json.JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            H5PItem(o.getLong("id"), o.optString("title"), o.optString("library"), o.optBoolean("offline", true))
        }
    }.getOrDefault(emptyList())

    /**
     * Score of a statement that finishes an exercise: a top-level "answered" or "completed" with a
     * score (what the H5P plugin and Tutor LMS Pro count as done). Null for any other statement.
     */
    fun h5pFinalScore(statement: org.json.JSONObject): Pair<Double, Double>? {
        val verb = statement.optJSONObject("verb")?.optString("id").orEmpty().lowercase(Locale.ROOT)
        if (!verb.endsWith("/answered") && !verb.endsWith("/completed")) return null
        val parent = statement.optJSONObject("context")?.optJSONObject("contextActivities")?.opt("parent")
        if (parent != null && parent != org.json.JSONObject.NULL && !(parent is org.json.JSONArray && parent.length() == 0)) return null
        val score = statement.optJSONObject("result")?.optJSONObject("score") ?: return null
        if (!score.has("max")) return null
        return score.optDouble("raw", 0.0) to score.optDouble("max", 0.0)
    }
}
