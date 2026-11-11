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
}

object EventType {
    const val LESSON_COMPLETE = "lesson_complete"
    const val VIDEO_POSITION = "video_position"
    const val LESSON_ACCESS = "lesson_access"
    const val QUIZ_ATTEMPT = "quiz_attempt"
}

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
    val error: String
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
    val onlineReason: String = ""
) {
    val isLesson get() = type == "lesson"
    val isOfflineQuiz get() = type == "quiz" && quizJson.isNotEmpty()
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
    val lastLessonAt: Long
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
    val onlineReason: String = ""
)

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
