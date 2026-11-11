package com.eduqode.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.eduqode.app.R
import com.eduqode.app.offline.FileState
import com.eduqode.app.offline.Lesson
import com.eduqode.app.offline.OfflineLogic
import com.eduqode.app.offline.StoredFile

/** Lesson list grouped by topic, used by the download screen and the offline course player. */
class LessonAdapter(
    private val studyMode: Boolean,
    private val availability: (Lesson, List<StoredFile>) -> String,
    private val isDone: (Lesson) -> Boolean = { it.completedAt > 0 },
    private val onClick: (Lesson) -> Unit
) : RecyclerView.Adapter<LessonAdapter.Holder>() {

    private var lessons: List<Lesson> = emptyList()
    private var files: List<StoredFile> = emptyList()

    fun submit(l: List<Lesson>, f: List<StoredFile>) {
        lessons = l; files = f; notifyDataSetChanged()
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

    override fun getItemCount() = lessons.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        val l = lessons[position]
        val ctx = h.itemView.context
        val newTopic = position == 0 || lessons[position - 1].topicId != l.topicId
        h.topic.visibility = if (newTopic) View.VISIBLE else View.GONE
        h.topic.text = l.topicTitle.uppercase()
        h.title.text = l.title

        val status = availability(l, files)
        val kind = when {
            l.type == "quiz" -> "Quiz"
            l.isAssignment -> "Assignment"
            l.hasVideo -> "Video lesson"
            else -> "Lesson"
        }
        h.subtitle.text = "$kind · $status"

        val mine = files.filter { it.lessonId == l.id }
        if (studyMode) {
            h.icon.setImageResource(if (isDone(l)) R.drawable.ic_check else R.drawable.ic_circle)
            h.percent.text = if (l.durationMs > 0 && l.positionMs > 0 && l.completedAt == 0L)
                "${(l.positionMs * 100 / l.durationMs).coerceIn(0, 100)}%" else ""
        } else {
            h.icon.setImageResource(if (isDone(l)) R.drawable.ic_check else R.drawable.ic_circle)
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
