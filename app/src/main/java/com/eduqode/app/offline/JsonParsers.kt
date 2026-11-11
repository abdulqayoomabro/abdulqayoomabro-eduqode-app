package com.eduqode.app.offline

import org.json.JSONArray
import org.json.JSONObject

/** Turns the plugin's JSON into app models. Pure Kotlin, covered by unit tests. */
object JsonParsers {

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it, null) }.filter { it.isNotBlank() }
    }

    fun enrolledCourses(json: JSONObject): List<EnrolledCourse> {
        val arr = json.optJSONArray("courses") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            EnrolledCourse(
                id = c.optLong("id"),
                title = c.str("title"),
                excerpt = c.str("excerpt"),
                thumbnailUrl = c.str("thumbnail"),
                permalink = c.str("permalink"),
                categories = c.optJSONArray("categories").strings(),
                progress = c.optInt("progress"),
                lessonCount = c.optInt("lesson_count"),
                offlineAllowed = c.optBoolean("offline", true)
            )
        }
    }

    fun coursePackage(json: JSONObject): CoursePackage {
        val c = json.getJSONObject("course")
        val course = PackageCourse(
            id = c.getLong("id"),
            title = c.str("title"),
            excerpt = c.str("excerpt"),
            descriptionHtml = c.str("description"),
            thumbnailUrl = c.str("thumbnail"),
            permalink = c.str("permalink"),
            categories = c.optJSONArray("categories").strings(),
            progress = c.optInt("progress"),
            lessonCount = c.optInt("lesson_count")
        )
        val lessons = ArrayList<PackageLesson>()
        val topics = json.optJSONArray("topics") ?: JSONArray()
        for (t in 0 until topics.length()) {
            val topic = topics.getJSONObject(t)
            val items = topic.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val video = item.optJSONObject("video")
                val online = video?.optJSONObject("online")
                val offline = video?.optJSONObject("offline")
                val pos = item.optJSONObject("position")
                val attachments = item.optJSONArray("attachments")?.let { arr ->
                    (0 until arr.length()).map { a ->
                        val o = arr.getJSONObject(a)
                        Attachment(o.str("url"), o.str("name"), o.str("mime"), o.optLong("size_bytes"))
                    }.filter { it.url.isNotBlank() }
                } ?: emptyList()
                lessons += PackageLesson(
                    id = item.getLong("id"),
                    topicId = topic.optLong("id"),
                    topicTitle = topic.str("title"),
                    topicOrder = topic.optInt("order", t),
                    order = item.optInt("order", i),
                    type = item.str("type"),
                    title = item.str("title"),
                    permalink = item.str("permalink"),
                    onlineOnly = item.optBoolean("online_only", false),
                    contentHtml = item.str("content_html"),
                    images = item.optJSONArray("images").strings(),
                    attachments = attachments,
                    videoOnlineProvider = online?.str("provider") ?: "",
                    videoOnlineUrl = online?.str("url") ?: "",
                    videoOffline = offline?.let {
                        val url = it.str("url")
                        if (url.isBlank()) null
                        else OfflineVideoSource(it.str("provider").ifBlank { "direct" }, url, it.optLong("size_bytes"), it.str("mime"))
                    },
                    completedAt = item.optLong("completed_at"),
                    positionMs = pos?.optLong("position_ms") ?: 0,
                    durationMs = pos?.optLong("duration_ms") ?: 0,
                    positionAt = pos?.optLong("updated_at") ?: 0
                )
            }
        }
        val last = json.optJSONObject("last_lesson")
        return CoursePackage(course, lessons, last?.optLong("lesson_id") ?: 0, last?.optLong("updated_at") ?: 0)
    }

    fun progress(p: JSONObject): ServerProgress {
        val completed = HashMap<Long, Long>()
        p.optJSONObject("completed")?.let { obj ->
            obj.keys().forEach { k -> k.toLongOrNull()?.let { completed[it] = obj.optLong(k, 1) } }
        }
        val positions = HashMap<Long, ServerPosition>()
        p.optJSONObject("positions")?.let { obj ->
            obj.keys().forEach { k ->
                val v = obj.optJSONObject(k) ?: return@forEach
                k.toLongOrNull()?.let {
                    positions[it] = ServerPosition(v.optLong("position_ms"), v.optLong("duration_ms"), v.optLong("updated_at"))
                }
            }
        }
        val last = p.optJSONObject("last_lesson")
        return ServerProgress(
            courseId = p.getLong("course_id"),
            percent = p.optInt("percent"),
            completed = completed,
            positions = positions,
            lastLessonId = last?.optLong("lesson_id") ?: 0,
            lastLessonAt = last?.optLong("updated_at") ?: 0
        )
    }

    data class SyncResponse(
        val results: Map<String, Pair<String, String>>,   // event id -> (status, reason)
        val progress: List<ServerProgress>,
        val noAccess: List<Long>
    )

    fun syncResponse(json: JSONObject): SyncResponse {
        val results = HashMap<String, Pair<String, String>>()
        json.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                results[r.str("id")] = r.str("status") to r.str("reason")
            }
        }
        val progress = json.optJSONArray("progress")?.let { arr ->
            (0 until arr.length()).map { progress(arr.getJSONObject(it)) }
        } ?: emptyList()
        val noAccess = json.optJSONArray("no_access")?.let { arr ->
            (0 until arr.length()).map { arr.optLong(it) }
        } ?: emptyList()
        return SyncResponse(results, progress, noAccess)
    }

    fun eventsBody(events: List<PendingEvent>, deviceId: String, courseIds: Collection<Long>): JSONObject {
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("type", e.type)
                put("course_id", e.courseId)
                put("lesson_id", e.lessonId)
                put("client_time", e.clientTime)
                if (e.type == EventType.VIDEO_POSITION) {
                    put("position_ms", e.positionMs)
                    put("duration_ms", e.durationMs)
                }
            })
        }
        return JSONObject().apply {
            put("device_id", deviceId)
            put("events", arr)
            put("course_ids", JSONArray(courseIds.toList()))
        }
    }
}
