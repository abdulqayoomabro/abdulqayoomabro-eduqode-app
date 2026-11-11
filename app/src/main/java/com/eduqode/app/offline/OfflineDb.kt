package com.eduqode.app.offline

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local database (SQLite). Survives app restarts and phone reboots.
 * Every row belongs to one WordPress user, so two students on one phone never see each other's data.
 */
class OfflineDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "offline.db", null, 1) {

    companion object {
        @Volatile private var instance: OfflineDb? = null
        fun get(context: Context): OfflineDb =
            instance ?: synchronized(this) { instance ?: OfflineDb(context).also { instance = it } }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE courses (
                user_id INTEGER NOT NULL, id INTEGER NOT NULL,
                title TEXT NOT NULL DEFAULT '', excerpt TEXT NOT NULL DEFAULT '', description_html TEXT NOT NULL DEFAULT '',
                thumbnail_url TEXT NOT NULL DEFAULT '', permalink TEXT NOT NULL DEFAULT '', categories TEXT NOT NULL DEFAULT '[]',
                lesson_count INTEGER NOT NULL DEFAULT 0, progress INTEGER NOT NULL DEFAULT 0,
                enrolled INTEGER NOT NULL DEFAULT 1, offline_allowed INTEGER NOT NULL DEFAULT 1,
                state TEXT NOT NULL DEFAULT 'none', error TEXT NOT NULL DEFAULT '',
                total_bytes INTEGER NOT NULL DEFAULT 0, done_bytes INTEGER NOT NULL DEFAULT 0,
                downloaded_at INTEGER NOT NULL DEFAULT 0, package_at INTEGER NOT NULL DEFAULT 0,
                last_sync_at INTEGER NOT NULL DEFAULT 0, sync_state TEXT NOT NULL DEFAULT 'synced', sync_error TEXT NOT NULL DEFAULT '',
                access_expired INTEGER NOT NULL DEFAULT 0,
                last_lesson_id INTEGER NOT NULL DEFAULT 0, last_lesson_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (user_id, id))"""
        )
        db.execSQL(
            """CREATE TABLE lessons (
                user_id INTEGER NOT NULL, id INTEGER NOT NULL, course_id INTEGER NOT NULL,
                topic_id INTEGER NOT NULL DEFAULT 0, topic_title TEXT NOT NULL DEFAULT '', topic_order INTEGER NOT NULL DEFAULT 0,
                item_order INTEGER NOT NULL DEFAULT 0, type TEXT NOT NULL DEFAULT 'lesson', title TEXT NOT NULL DEFAULT '',
                permalink TEXT NOT NULL DEFAULT '', online_only INTEGER NOT NULL DEFAULT 0, has_content INTEGER NOT NULL DEFAULT 0,
                video_online_provider TEXT NOT NULL DEFAULT '', video_online_url TEXT NOT NULL DEFAULT '',
                video_offline_provider TEXT NOT NULL DEFAULT '', video_offline_url TEXT NOT NULL DEFAULT '',
                attachments TEXT NOT NULL DEFAULT '[]', selected INTEGER NOT NULL DEFAULT 1,
                completed_at INTEGER NOT NULL DEFAULT 0,
                position_ms INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0, position_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (user_id, id))"""
        )
        db.execSQL("CREATE INDEX lessons_course ON lessons(user_id, course_id)")
        db.execSQL(
            """CREATE TABLE files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL, course_id INTEGER NOT NULL, lesson_id INTEGER NOT NULL DEFAULT 0,
                kind TEXT NOT NULL, url TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', mime TEXT NOT NULL DEFAULT '',
                local_path TEXT NOT NULL, expected_bytes INTEGER NOT NULL DEFAULT 0, done_bytes INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'pending', error TEXT NOT NULL DEFAULT '',
                UNIQUE (user_id, course_id, url))"""
        )
        db.execSQL("CREATE INDEX files_course ON files(user_id, course_id)")
        db.execSQL(
            """CREATE TABLE events (
                id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, course_id INTEGER NOT NULL, lesson_id INTEGER NOT NULL,
                type TEXT NOT NULL, client_time INTEGER NOT NULL,
                position_ms INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0,
                sending INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL("CREATE INDEX events_user ON events(user_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema. Future versions add ALTER TABLE steps here; never drop student data.
    }

    // ---------- helpers ----------

    private fun Cursor.str(c: String) = getString(getColumnIndexOrThrow(c)) ?: ""
    private fun Cursor.long(c: String) = getLong(getColumnIndexOrThrow(c))
    private fun Cursor.int(c: String) = getInt(getColumnIndexOrThrow(c))

    private inline fun <T> query(sql: String, args: Array<String>, map: (Cursor) -> T): List<T> {
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out += map(c)
            return out
        }
    }

    private fun jsonList(s: String): List<String> = runCatching {
        val a = JSONArray(s); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun courseFrom(c: Cursor) = Course(
        id = c.long("id"), userId = c.long("user_id"), title = c.str("title"), excerpt = c.str("excerpt"),
        descriptionHtml = c.str("description_html"), thumbnailUrl = c.str("thumbnail_url"), permalink = c.str("permalink"),
        categories = jsonList(c.str("categories")), lessonCount = c.int("lesson_count"), progress = c.int("progress"),
        state = c.str("state"), totalBytes = c.long("total_bytes"), doneBytes = c.long("done_bytes"),
        downloadedAt = c.long("downloaded_at"), lastSyncAt = c.long("last_sync_at"), syncState = c.str("sync_state"),
        syncError = c.str("sync_error"), accessExpired = c.int("access_expired") == 1, lastLessonId = c.long("last_lesson_id"),
        error = c.str("error")
    )

    private fun lessonFrom(c: Cursor) = Lesson(
        id = c.long("id"), courseId = c.long("course_id"), topicId = c.long("topic_id"), topicTitle = c.str("topic_title"),
        topicOrder = c.int("topic_order"), order = c.int("item_order"), type = c.str("type"), title = c.str("title"),
        permalink = c.str("permalink"), onlineOnly = c.int("online_only") == 1, hasContent = c.int("has_content") == 1,
        videoOnlineProvider = c.str("video_online_provider"), videoOnlineUrl = c.str("video_online_url"),
        videoOfflineProvider = c.str("video_offline_provider"), videoOfflineUrl = c.str("video_offline_url"),
        attachments = runCatching {
            val a = JSONArray(c.str("attachments"))
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Attachment(o.optString("url"), o.optString("name"), o.optString("mime"), o.optLong("size"))
            }
        }.getOrDefault(emptyList()),
        selected = c.int("selected") == 1, completedAt = c.long("completed_at"),
        positionMs = c.long("position_ms"), durationMs = c.long("duration_ms"), positionAt = c.long("position_at")
    )

    private fun fileFrom(c: Cursor) = StoredFile(
        id = c.long("id"), courseId = c.long("course_id"), lessonId = c.long("lesson_id"), kind = c.str("kind"),
        url = c.str("url"), name = c.str("name"), mime = c.str("mime"), localPath = c.str("local_path"),
        expectedBytes = c.long("expected_bytes"), doneBytes = c.long("done_bytes"), state = c.str("state"), error = c.str("error")
    )

    // ---------- courses ----------

    fun courses(userId: Long): List<Course> =
        query("SELECT * FROM courses WHERE user_id=? ORDER BY title COLLATE NOCASE", arrayOf("$userId"), ::courseFrom)

    fun course(userId: Long, courseId: Long): Course? =
        query("SELECT * FROM courses WHERE user_id=? AND id=?", arrayOf("$userId", "$courseId"), ::courseFrom).firstOrNull()

    fun courseByPermalink(userId: Long, permalink: String): Course? {
        val norm = permalink.trimEnd('/')
        return courses(userId).firstOrNull { it.permalink.trimEnd('/').equals(norm, ignoreCase = true) && !it.accessExpired }
    }

    /** Saves the enrolled-course list; courses no longer listed are marked "access ended". */
    fun saveEnrolled(userId: Long, list: List<EnrolledCourse>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ids = list.map { it.id }.toSet()
            list.forEach { e ->
                val v = ContentValues().apply {
                    put("title", e.title); put("excerpt", e.excerpt); put("thumbnail_url", e.thumbnailUrl)
                    put("permalink", e.permalink); put("categories", JSONArray(e.categories).toString())
                    put("lesson_count", e.lessonCount); put("progress", e.progress)
                    put("enrolled", 1); put("offline_allowed", if (e.offlineAllowed) 1 else 0); put("access_expired", 0)
                }
                if (db.update("courses", v, "user_id=? AND id=?", arrayOf("$userId", "${e.id}")) == 0) {
                    v.put("user_id", userId); v.put("id", e.id)
                    db.insert("courses", null, v)
                }
            }
            courses(userId).filter { it.id !in ids }.forEach { c ->
                if (c.state == CourseState.NONE) {
                    db.delete("courses", "user_id=? AND id=?", arrayOf("$userId", "${c.id}"))
                } else {
                    db.update("courses", ContentValues().apply { put("enrolled", 0); put("access_expired", 1) },
                        "user_id=? AND id=?", arrayOf("$userId", "${c.id}"))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setAccessExpired(userId: Long, courseId: Long, expired: Boolean) {
        updateCourse(userId, courseId, ContentValues().apply { put("access_expired", if (expired) 1 else 0) })
    }

    fun updateCourse(userId: Long, courseId: Long, values: ContentValues) {
        writableDatabase.update("courses", values, "user_id=? AND id=?", arrayOf("$userId", "$courseId"))
    }

    fun setCourseState(userId: Long, courseId: Long, state: String, error: String = "") {
        updateCourse(userId, courseId, ContentValues().apply { put("state", state); put("error", error) })
    }

    fun setSyncState(userId: Long, courseId: Long?, state: String, error: String = "", syncedAt: Long? = null) {
        val v = ContentValues().apply {
            put("sync_state", state); put("sync_error", error)
            if (syncedAt != null) put("last_sync_at", syncedAt)
        }
        if (courseId == null) writableDatabase.update("courses", v, "user_id=? AND state!='none'", arrayOf("$userId"))
        else updateCourse(userId, courseId, v)
    }

    /** Stores a freshly downloaded pack. Keeps local progress, files and lesson selection. */
    fun savePackage(userId: Long, pkg: CoursePackage, lessonHtmlPresent: Set<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val c = pkg.course
            val cv = ContentValues().apply {
                put("title", c.title); put("excerpt", c.excerpt); put("description_html", c.descriptionHtml)
                put("thumbnail_url", c.thumbnailUrl); put("permalink", c.permalink)
                put("categories", JSONArray(c.categories).toString()); put("lesson_count", c.lessonCount)
                put("progress", c.progress); put("package_at", System.currentTimeMillis())
                put("enrolled", 1); put("access_expired", 0)
            }
            if (db.update("courses", cv, "user_id=? AND id=?", arrayOf("$userId", "${c.id}")) == 0) {
                cv.put("user_id", userId); cv.put("id", c.id); db.insert("courses", null, cv)
            }
            val course = course(userId, c.id)
            val (lastId, lastAt) = OfflineLogic.newerLastLesson(
                course?.lastLessonId ?: 0, lastLessonAt(userId, c.id), pkg.lastLessonId, pkg.lastLessonAt
            )
            db.update("courses", ContentValues().apply { put("last_lesson_id", lastId); put("last_lesson_at", lastAt) },
                "user_id=? AND id=?", arrayOf("$userId", "${c.id}"))

            val existing = lessons(userId, c.id).associateBy { it.id }
            val pendingPos = pendingPositionLessons(userId)
            val keep = HashSet<Long>()
            pkg.lessons.forEach { l ->
                keep += l.id
                val old = existing[l.id]
                val attachments = JSONArray().apply {
                    l.attachments.forEach { a ->
                        put(JSONObject().put("url", a.url).put("name", a.name).put("mime", a.mime).put("size", a.sizeBytes))
                    }
                }
                val v = ContentValues().apply {
                    put("user_id", userId); put("id", l.id); put("course_id", c.id)
                    put("topic_id", l.topicId); put("topic_title", l.topicTitle); put("topic_order", l.topicOrder)
                    put("item_order", l.order); put("type", l.type); put("title", l.title); put("permalink", l.permalink)
                    put("online_only", if (l.onlineOnly) 1 else 0)
                    put("has_content", if (l.id in lessonHtmlPresent) 1 else 0)
                    put("video_online_provider", l.videoOnlineProvider); put("video_online_url", l.videoOnlineUrl)
                    put("video_offline_provider", l.videoOffline?.provider ?: ""); put("video_offline_url", l.videoOffline?.url ?: "")
                    put("attachments", attachments.toString())
                    put("selected", if (old?.selected == false) 0 else 1)
                    // Completion never goes back; positions keep whichever is newer.
                    put("completed_at", maxOf(old?.completedAt ?: 0, l.completedAt))
                    val serverNewer = l.positionAt > (old?.positionAt ?: 0) && l.id !in pendingPos
                    put("position_ms", if (serverNewer) l.positionMs else old?.positionMs ?: 0)
                    put("duration_ms", if (serverNewer) l.durationMs else old?.durationMs ?: 0)
                    put("position_at", if (serverNewer) l.positionAt else old?.positionAt ?: 0)
                }
                db.insertWithOnConflict("lessons", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            existing.keys.filter { it !in keep }.forEach { id ->
                db.delete("lessons", "user_id=? AND id=?", arrayOf("$userId", "$id"))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun lastLessonAt(userId: Long, courseId: Long): Long =
        query("SELECT last_lesson_at FROM courses WHERE user_id=? AND id=?", arrayOf("$userId", "$courseId")) { it.getLong(0) }
            .firstOrNull() ?: 0

    fun setLastLesson(userId: Long, courseId: Long, lessonId: Long, at: Long) {
        updateCourse(userId, courseId, ContentValues().apply { put("last_lesson_id", lessonId); put("last_lesson_at", at) })
    }

    fun applyServerLastLesson(userId: Long, courseId: Long, serverId: Long, serverAt: Long) {
        val c = course(userId, courseId) ?: return
        val (id, at) = OfflineLogic.newerLastLesson(c.lastLessonId, lastLessonAt(userId, courseId), serverId, serverAt)
        setLastLesson(userId, courseId, id, at)
    }

    /** Removes the downloaded copy only. Enrollment and progress on the website are not touched. */
    fun removeDownload(userId: Long, courseId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("files", "user_id=? AND course_id=?", arrayOf("$userId", "$courseId"))
            db.delete("lessons", "user_id=? AND course_id=?", arrayOf("$userId", "$courseId"))
            db.update("courses", ContentValues().apply {
                put("state", CourseState.NONE); put("error", ""); put("total_bytes", 0); put("done_bytes", 0)
                put("downloaded_at", 0); put("description_html", "")
            }, "user_id=? AND id=?", arrayOf("$userId", "$courseId"))
            // A course whose access has ended and is no longer downloaded is not shown any more.
            db.delete("courses", "user_id=? AND id=? AND access_expired=1", arrayOf("$userId", "$courseId"))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- lessons ----------

    fun lessons(userId: Long, courseId: Long): List<Lesson> = query(
        "SELECT * FROM lessons WHERE user_id=? AND course_id=? ORDER BY topic_order, item_order",
        arrayOf("$userId", "$courseId"), ::lessonFrom
    )

    fun lesson(userId: Long, lessonId: Long): Lesson? =
        query("SELECT * FROM lessons WHERE user_id=? AND id=?", arrayOf("$userId", "$lessonId"), ::lessonFrom).firstOrNull()

    fun setSelected(userId: Long, courseId: Long, selectedIds: Set<Long>?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            lessons(userId, courseId).forEach { l ->
                val sel = selectedIds == null || l.id in selectedIds
                db.update("lessons", ContentValues().apply { put("selected", if (sel) 1 else 0) },
                    "user_id=? AND id=?", arrayOf("$userId", "${l.id}"))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun markCompleted(userId: Long, lessonId: Long, at: Long) {
        writableDatabase.update("lessons", ContentValues().apply { put("completed_at", at) },
            "user_id=? AND id=? AND completed_at=0", arrayOf("$userId", "$lessonId"))
    }

    /** Used only when the website refuses an offline completion (for example a Tutor LMS Pro rule). */
    fun unmarkCompleted(userId: Long, lessonId: Long) {
        writableDatabase.update("lessons", ContentValues().apply { put("completed_at", 0) },
            "user_id=? AND id=?", arrayOf("$userId", "$lessonId"))
    }

    fun savePosition(userId: Long, lessonId: Long, positionMs: Long, durationMs: Long, at: Long) {
        writableDatabase.update("lessons", ContentValues().apply {
            put("position_ms", positionMs); put("duration_ms", durationMs); put("position_at", at)
        }, "user_id=? AND id=?", arrayOf("$userId", "$lessonId"))
    }

    fun setCourseProgress(userId: Long, courseId: Long, percent: Int) {
        updateCourse(userId, courseId, ContentValues().apply { put("progress", percent) })
    }

    // ---------- files ----------

    fun files(userId: Long, courseId: Long): List<StoredFile> =
        query("SELECT * FROM files WHERE user_id=? AND course_id=? ORDER BY id", arrayOf("$userId", "$courseId"), ::fileFrom)

    fun fileByUrl(userId: Long, courseId: Long, url: String): StoredFile? =
        query("SELECT * FROM files WHERE user_id=? AND course_id=? AND url=?", arrayOf("$userId", "$courseId", url), ::fileFrom).firstOrNull()

    /** Adds a file to download unless the course already has it (no duplicates). */
    fun ensureFile(userId: Long, courseId: Long, lessonId: Long, kind: String, url: String, name: String, mime: String,
                   localPath: String, expectedBytes: Long) {
        val v = ContentValues().apply {
            put("user_id", userId); put("course_id", courseId); put("lesson_id", lessonId); put("kind", kind)
            put("url", url); put("name", name); put("mime", mime); put("local_path", localPath)
            put("expected_bytes", expectedBytes)
        }
        if (writableDatabase.insertWithOnConflict("files", null, v, SQLiteDatabase.CONFLICT_IGNORE) == -1L && expectedBytes > 0) {
            writableDatabase.update("files", ContentValues().apply { put("expected_bytes", expectedBytes) },
                "user_id=? AND course_id=? AND url=? AND expected_bytes=0", arrayOf("$userId", "$courseId", url))
        }
    }

    fun deleteFilesNotIn(userId: Long, courseId: Long, urls: Set<String>): List<StoredFile> {
        val gone = files(userId, courseId).filter { it.url !in urls }
        gone.forEach { writableDatabase.delete("files", "id=?", arrayOf("${it.id}")) }
        return gone
    }

    fun updateFile(id: Long, values: ContentValues) {
        writableDatabase.update("files", values, "id=?", arrayOf("$id"))
    }

    fun setFileState(id: Long, state: String, error: String = "") =
        updateFile(id, ContentValues().apply { put("state", state); put("error", error) })

    fun resetFailedFiles(userId: Long, courseId: Long) {
        writableDatabase.update("files", ContentValues().apply { put("state", FileState.PENDING); put("error", "") },
            "user_id=? AND course_id=? AND state IN ('failed','downloading')", arrayOf("$userId", "$courseId"))
    }

    // ---------- events (sync queue) ----------

    fun addEvent(userId: Long, e: PendingEvent) {
        writableDatabase.insertWithOnConflict("events", null, ContentValues().apply {
            put("id", e.id); put("user_id", userId); put("course_id", e.courseId); put("lesson_id", e.lessonId)
            put("type", e.type); put("client_time", e.clientTime); put("position_ms", e.positionMs); put("duration_ms", e.durationMs)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * Keeps one unsent position / "last opened" event per lesson or course: a newer one replaces
     * the older unsent one instead of growing the queue.
     */
    fun upsertCoalescedEvent(userId: Long, e: PendingEvent, sameCourseOnly: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (sameCourseOnly) {
                db.delete("events", "user_id=? AND type=? AND course_id=? AND sending=0", arrayOf("$userId", e.type, "${e.courseId}"))
            } else {
                db.delete("events", "user_id=? AND type=? AND lesson_id=? AND sending=0", arrayOf("$userId", e.type, "${e.lessonId}"))
            }
            addEvent(userId, e)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun pendingEvents(userId: Long, limit: Int = 200): List<PendingEvent> = query(
        "SELECT * FROM events WHERE user_id=? ORDER BY client_time LIMIT $limit", arrayOf("$userId")
    ) { c -> PendingEvent(c.str("id"), c.long("course_id"), c.long("lesson_id"), c.str("type"), c.long("client_time"), c.long("position_ms"), c.long("duration_ms")) }

    fun pendingCount(userId: Long, courseId: Long): Int =
        query("SELECT COUNT(*) FROM events WHERE user_id=? AND course_id=?", arrayOf("$userId", "$courseId")) { it.getInt(0) }.first()

    fun pendingPositionLessons(userId: Long): Set<Long> =
        query("SELECT lesson_id FROM events WHERE user_id=? AND type='video_position'", arrayOf("$userId")) { it.getLong(0) }.toSet()

    fun markSending(ids: List<String>, sending: Boolean) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.update("events", ContentValues().apply { put("sending", if (sending) 1 else 0) }, "id=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteEvents(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("events", "id=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun eventFailed(ids: Collection<String>, error: String) {
        ids.forEach {
            writableDatabase.execSQL("UPDATE events SET sending=0, attempts=attempts+1, last_error=? WHERE id=?", arrayOf(error, it))
        }
    }
}
