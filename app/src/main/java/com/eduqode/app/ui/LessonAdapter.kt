package com.eduqode.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.eduqode.app.R
import com.eduqode.app.offline.FileState
import com.eduqode.app.offline.Lesson
import com.eduqode.app.offline.OfflineLogic
import com.eduqode.app.offline.StoredFile

/**
 * Lesson list grouped by topic, used by the download screen and the offline course player.
 *
 * Rows are computed off the main thread ([rows]) and only rows that really changed are redrawn,
 * so periodic refreshes never rebuild the list or swallow a tap.
 */
class LessonAdapter(
    private val studyMode: Boolean,
    private val onClick: (Lesson) -> Unit
) : RecyclerView.Adapter<LessonAdapter.Holder>() {

    data class Row(val lesson: Lesson, val status: String, val done: Boolean, val files: List<StoredFile>)

    companion object {
        /** Builds rows; call from a background thread (status and done may read the database). */
        fun rows(
            lessons: List<Lesson>,
            files: List<StoredFile>,
            availability: (Lesson, List<StoredFile>) -> String,
            isDone: (Lesson) -> Boolean
        ): List<Row> {
            val byLesson = files.groupBy { it.lessonId }
            return lessons.map { l -> Row(l, availability(l, files), isDone(l), byLesson[l.id].orEmpty()) }
        }
    }

    private var rows: List<Row> = emptyList()

    fun submit(newRows: List<Row>) {
        if (newRows == rows) return
        val old = rows
        rows = newRows
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].lesson.id == newRows[n].lesson.id
            override fun areContentsTheSame(o: Int, n: Int) =
                old[o] == newRows[n] && (o == 0) == (n == 0) &&
                    (o == 0 || old[o - 1].lesson.topicId == newRows[n - 1].lesson.topicId)
        }).dispatchUpdatesTo(this)
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val topic: TextView = v.findViewById(R.id.topic)
        val row: View = v.findViewById(R.id.row)
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val percent: TextView = v.findViewById(R.id.percent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_lesson, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        val r = rows[position]
        val l = r.lesson
        val ctx = h.itemView.context
        val newTopic = position == 0 || rows[position - 1].lesson.topicId != l.topicId
        h.topic.visibility = if (newTopic) View.VISIBLE else View.GONE
        h.topic.text = l.topicTitle.uppercase()
        h.title.text = l.title

        val status = r.status
        val kind = when {
            l.type == "quiz" -> "Quiz"
            l.isAssignment -> "Assignment"
            l.hasVideo -> "Video lesson"
            else -> "Lesson"
        }
        h.subtitle.text = "$kind · $status"

        val mine = r.files
        h.icon.setImageResource(if (r.done) R.drawable.ic_check else R.drawable.ic_circle)
        if (studyMode) {
            h.percent.text = if (l.durationMs > 0 && l.positionMs > 0 && l.completedAt == 0L)
                "${(l.positionMs * 100 / l.durationMs).coerceIn(0, 100)}%" else ""
        } else {
            val total = mine.sumOf { maxOf(it.expectedBytes, it.doneBytes) }
            val done = mine.sumOf { if (it.state == FileState.DONE) maxOf(it.expectedBytes, it.doneBytes) else it.doneBytes }
            h.percent.text = when {
                !l.isReadable || !l.selected -> ""
                mine.isEmpty() -> "✓"
                mine.all { it.state == FileState.DONE } -> "✓ ${OfflineLogic.formatBytes(total)}"
                total > 0 -> "${(done * 100 / total).coerceIn(0, 100)}%"
                else -> "…"
            }
        }
        val color = when {
            status.startsWith("Download failed") -> R.color.error_red
            status.startsWith("Available") || status.startsWith("Passed") || status.startsWith("Submitted") -> R.color.ok_green
            status.startsWith("Not passed") -> R.color.error_red
            else -> R.color.text_secondary
        }
        h.subtitle.setTextColor(ContextCompat.getColor(ctx, color))
        h.row.setOnClickListener { onClick(l) }
    }
}
