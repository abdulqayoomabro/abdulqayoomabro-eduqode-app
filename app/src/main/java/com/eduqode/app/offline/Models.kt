package com.eduqode.app.offline

/** Course states shown in the app. */
object CourseState {
    const val NONE = "none"                // not downloaded
    const val QUEUED = "queued"            // waiting for network / Wi-Fi
    const val DOWNLOADING = "downloading"
    const val PAUSED = "paused"
    const val FAILED = "failed"            // some files failed, can retry
    const val DOWNLOADED = "downloaded"
}

object SyncState {
    const val SYNCED = "synced"
    const val PENDING = "pending"
    const val SYNCING = "syncing"
    const val FAILED = "failed"
}

object FileState {
    const val PENDING = "pending"
    const val DOWNLOADING = "downloading"
    const val DONE = "done"
    const val FAILED = "failed"
    const val SKIPPED = "skipped"          // lesson not selected for download
}

object FileKind {
    const val VIDEO = "video"
    const val ATTACHMENT = "attachment"
    const val IMAGE = "image"
    const val THUMBNAIL = "thumbnail"
    const val H5P = "h5p"                  // H5P library and exercise files
}

object EventType {
    const val LESSON_COMPLETE = "lesson_complete"
    const val VIDEO_POSITION = "video_position"
    const val LESSON_ACCESS = "lesson_access"
    const val QUIZ_ATTEMPT = "quiz_attempt"
    const val H5P_STATEMENT = "h5p_statement"            // one xAPI statement from an H5P exercise in a lesson
    const val H5P_LESSON_FINISHED = "h5p_lesson_finished" // every H5P exercise of a lesson answered
}

/** An H5P exercise of a lesson or quiz question (files are in <course>/h5p/). */
data class H5PItem(val id: Long, val title: String, val library: String, val offline: Boolean = true)

data class Course(
    val id: Long,
    val userId: Long,
    val title: String,
    val excerpt: String,
    val descriptionHtml: String,
    val thumbnailUrl: String,
    val permalink: String,
    val categories: List<String>,
    val lessonCount: Int,
    val progress: Int,
    val state: String,
    val totalBytes: Long,
    val doneBytes: Long,
    val downloadedAt: Long,
    val lastSyncAt: Long,
    val syncState: String,
    val syncError: String,
    val accessExpired: Boolean,
    val lastLessonId: Long,
    val error: String,
    /** Tutor setting: a lesson with H5P can be completed only after its exercises are done. */
    val h5pGate: Boolean = false
)

data class Attachment(val url: String, val name: String, val mime: String, val sizeBytes: Long)

data class Lesson(
    val id: Long,
    val courseId: Long,
    val topicId: Long,
    val topicTitle: String,
    val topicOrder: Int,
    val order: Int,
    val type: String,               // lesson, quiz, ...
    val title: String,
    val permalink: String,
    val onlineOnly: Boolean,        // quizzes and other online-only content
    val hasContent: Boolean,
    val videoOnlineProvider: String,
    val videoOnlineUrl: String,
    val videoOfflineProvider: String,
    val videoOfflineUrl: String,
    val attachments: List<Attachment>,
    val selected: Boolean,
    val completedAt: Long,
    val positionMs: Long,
    val durationMs: Long,
    val positionAt: Long,
    val quizJson: String = "",
    val quizAttemptsUsed: Int = 0,
    val quizLastJson: String = "",
    val onlineReason: String = "",
    val h5pJson: String = "",
    val h5pFinished: Boolean = false
) {
    /** H5P exercises in this lesson. */
    val h5p: List<H5PItem> by lazy { OfflineLogic.parseH5PItems(h5pJson) }
    val isLesson get() = type == "lesson"
    val isOfflineQuiz get() = type == "quiz" && quizJson.isNotEmpty()
    val isAssignment get() = type == "assignment"
    /** Lessons and assignments: content the student reads in the lesson screen. */
    val isReadable get() = isLesson || isAssignment
    val quiz: Quiz? by lazy { if (quizJson.isEmpty()) null else runCatching { QuizEngine.parse(org.json.JSONObject(quizJson)) }.getOrNull() }
    val hasVideo get() = videoOnlineProvider.isNotEmpty() || videoOfflineUrl.isNotEmpty()
    val videoOfflineCapable get() = videoOfflineUrl.isNotEmpty()
}

data class StoredFile(
    val id: Long,
    val courseId: Long,
    val lessonId: Long,
    val kind: String,
    val url: String,
    val name: String,
    val mime: String,
    val localPath: String,
    val expectedBytes: Long,
    val doneBytes: Long,
    val state: String,
    val error: String
)

data class PendingEvent(
    val id: String,
    val courseId: Long,
    val lessonId: Long,
    val type: String,
    val clientTime: Long,
    val positionMs: Long,
    val durationMs: Long,
    val payload: String = ""
)

/** An offline quiz attempt stored on the phone. */
data class LocalQuizAttempt(
    val id: String,
    val courseId: Long,
    val quizId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val responsesJson: String,
    val questionIds: List<Long>,
    val earned: Double,
    val total: Double,
    val result: String,
    val state: String,            // pending, synced, rejected
    val serverEarned: Double?,
    val serverTotal: Double?,
    val serverResult: String,
    val reason: String
)

/** Parsed offline pack from GET /courses/{id}/package. */
data class CoursePackage(
    val course: PackageCourse,
    val lessons: List<PackageLesson>,
    val lastLessonId: Long,
    val lastLessonAt: Long,
    val h5pLibraries: Map<String, List<H5PFile>> = emptyMap(),
    val h5pGate: Boolean = false
)

data class PackageCourse(
    val id: Long,
    val title: String,
    val excerpt: String,
    val descriptionHtml: String,
    val thumbnailUrl: String,
    val permalink: String,
    val categories: List<String>,
    val progress: Int,
    val lessonCount: Int
)

data class PackageLesson(
    val id: Long,
    val topicId: Long,
    val topicTitle: String,
    val topicOrder: Int,
    val order: Int,
    val type: String,
    val title: String,
    val permalink: String,
    val onlineOnly: Boolean,
    val contentHtml: String,
    val images: List<String>,
    val attachments: List<Attachment>,
    val videoOnlineProvider: String,
    val videoOnlineUrl: String,
    val videoOffline: OfflineVideoSource?,
    val completedAt: Long,
    val positionMs: Long,
    val durationMs: Long,
    val positionAt: Long,
    val quizJson: String = "",
    val onlineReason: String = "",
    val h5p: List<H5PContent> = emptyList(),
    val h5pOnline: List<Long> = emptyList(),
    val h5pFinished: Boolean = false
) {
    val quiz: Quiz? by lazy { if (quizJson.isEmpty()) null else runCatching { QuizEngine.parse(org.json.JSONObject(quizJson)) }.getOrNull() }
}

/** One H5P exercise as sent by the server: h5p.json, content.json and its own files. */
data class H5PContent(
    val id: Long,
    val title: String,
    val library: String,
    val libraries: List<String>,
    val h5pJson: String,
    val contentJson: String,
    val files: List<H5PFile>
)

data class H5PFile(val path: String, val url: String, val size: Long)

/** A downloadable video, as described by the server (see the plugin's video()). */
data class OfflineVideoSource(val provider: String, val url: String, val sizeBytes: Long, val mime: String)

/** Server progress for one course from /sync or /courses/{id}/progress. */
data class ServerProgress(
    val courseId: Long,
    val percent: Int,
    val completed: Map<Long, Long>,
    val positions: Map<Long, ServerPosition>,
    val lastLessonId: Long,
    val lastLessonAt: Long,
    val quizzes: Map<Long, Pair<Int, String>> = emptyMap()   // quiz id -> (attempts used, last attempt JSON)
)

data class ServerPosition(val positionMs: Long, val durationMs: Long, val updatedAt: Long)

data class EnrolledCourse(
    val id: Long,
    val title: String,
    val excerpt: String,
    val thumbnailUrl: String,
    val permalink: String,
    val categories: List<String>,
    val progress: Int,
    val lessonCount: Int,
    val offlineAllowed: Boolean
)
