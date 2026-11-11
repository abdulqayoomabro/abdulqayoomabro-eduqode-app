package com.eduqode.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.eduqode.app.R
import com.eduqode.app.offline.ApiClient
import com.eduqode.app.offline.Course
import com.eduqode.app.offline.CourseState
import com.eduqode.app.offline.FileKind
import com.eduqode.app.offline.OfflineLogic
import com.eduqode.app.offline.OfflineRepository
import com.eduqode.app.offline.Session
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** "My offline courses": downloaded courses, enrolled courses to download, storage and sync. */
class OfflineLibraryActivity : AppCompatActivity() {

    private lateinit var repo: OfflineRepository
    private val downloaded = CourseAdapter(true)
    private val available = CourseAdapter(false)
    private var loggedIn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_library)
        Ui.applyInsets(findViewById(R.id.root))
        repo = OfflineRepository(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.sync_menu)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_sync) { syncNow(); true } else false
        }

        findViewById<RecyclerView>(R.id.downloaded_list).apply { layoutManager = LinearLayoutManager(context); adapter = downloaded }
        findViewById<RecyclerView>(R.id.available_list).apply { layoutManager = LinearLayoutManager(context); adapter = available }

        val wifi = findViewById<MaterialSwitch>(R.id.wifi_switch)
        wifi.isChecked = repo.wifiOnly
        wifi.setOnCheckedChangeListener { _, checked -> repo.wifiOnly = checked }

        findViewById<SwipeRefreshLayout>(R.id.swipe).setOnRefreshListener { refreshFromServer() }
        findViewById<MaterialButton>(R.id.login_button).setOnClickListener {
            Ui.openWebsite(this, "${ApiClient.SITE}/dashboard/")
            finish()
        }

        // Keep the list fresh while visible (download progress, sync status).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    render()
                    delay(1500)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFromServer()
    }

    private fun refreshFromServer() {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe)
        lifecycleScope.launch {
            val state = Session.refresh(this@OfflineLibraryActivity)
            loggedIn = state is Session.State.LoggedIn
            showLogin(state)
            if (loggedIn && repo.isOnline()) {
                runCatching { repo.refreshEnrolled() }
                repo.syncNow()
            }
            swipe.isRefreshing = false
            render()
        }
    }

    private fun showLogin(state: Session.State) {
        val card = findViewById<MaterialCardView>(R.id.login_card)
        val text = findViewById<TextView>(R.id.login_text)
        val button = findViewById<MaterialButton>(R.id.login_button)
        when (state) {
            is Session.State.LoggedIn -> card.visibility = View.GONE
            is Session.State.NotLoggedIn -> {
                card.visibility = View.VISIBLE; button.visibility = View.VISIBLE
                text.text = "Log in to your EduQode account to download courses and use them offline."
            }
            is Session.State.Offline -> {
                card.visibility = View.VISIBLE; button.visibility = View.GONE
                text.text = "You are offline. Connect to the internet once and log in to download courses."
            }
            is Session.State.Error -> {
                card.visibility = View.VISIBLE; button.visibility = View.GONE
                text.text = state.message
            }
        }
    }

    private suspend fun render() {
        val uid = repo.userId
        val data = withContext(Dispatchers.IO) {
            val courses = if (uid > 0 && repo.auth.isLoggedIn) repo.db.courses(uid) else emptyList()
            val thumbs = courses.associate { c ->
                c.id to repo.db.files(uid, c.id).firstOrNull { it.kind == FileKind.THUMBNAIL && it.state == "done" }?.let { File(it.localPath) }
            }
            Triple(courses, thumbs, Pair(repo.usedBytes(), repo.freeBytes()))
        }
        val (courses, thumbs, storage) = data
        val down = courses.filter { it.state != CourseState.NONE }
        val avail = courses.filter { it.state == CourseState.NONE && !it.accessExpired }

        downloaded.submit(down, thumbs)
        available.submit(avail, thumbs)
        findViewById<View>(R.id.downloaded_empty).visibility = if (down.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.available_empty).apply {
            visibility = if (avail.isEmpty()) View.VISIBLE else View.GONE
            text = when {
                !loggedIn -> ""
                !repo.isOnline() -> "Connect to the internet to see your other courses."
                courses.isEmpty() -> "No enrolled courses found."
                else -> "All your courses are downloaded."
            }
        }
        findViewById<TextView>(R.id.storage_text).text =
            "Offline files: ${OfflineLogic.formatBytes(storage.first)} · Free on phone: ${OfflineLogic.formatBytes(storage.second)} · ${down.size} course(s)"
        val pending = if (uid > 0) withContext(Dispatchers.IO) { repo.db.pendingEvents(uid).size } else 0
        findViewById<TextView>(R.id.sync_text).text = when {
            !loggedIn && repo.userName.isBlank() -> ""
            pending > 0 -> "$pending progress update(s) waiting to sync" + if (!repo.isOnline()) " (will sync when you are online)" else ""
            else -> "All progress synced" + (repo.auth.userName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        }
    }

    private val OfflineRepository.userName get() = auth.userName

    private fun syncNow() {
        if (!repo.isOnline()) {
            MaterialAlertDialogBuilder(this).setMessage("You are offline. Progress will sync automatically when you are back online.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        repo.syncNow()
        lifecycleScope.launch { render() }
    }

    private fun confirmRemove(c: Course) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove download?")
            .setMessage("This deletes the downloaded files of “${c.title}” from this phone only. Your enrollment and your progress on your account stay as they are.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch { repo.removeDownload(c.id); render() }
            }.show()
    }

    private fun openCourse(c: Course) {
        if (c.accessExpired) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Access ended")
                .setMessage("Your access to this course has ended, so its offline copy is locked. The files stay on your phone until you remove them. If you renew your access, it unlocks automatically after the next sync.")
                .setPositiveButton("Remove download") { _, _ -> confirmRemove(c) }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        startActivity(Intent(this, OfflineCourseActivity::class.java).putExtra(OfflineCourseActivity.EXTRA_COURSE, c.id))
    }

    private fun openDownload(c: Course) =
        startActivity(Intent(this, CourseDownloadActivity::class.java).putExtra(CourseDownloadActivity.EXTRA_COURSE, c.id))

    private inner class CourseAdapter(private val downloadedList: Boolean) : RecyclerView.Adapter<CourseAdapter.Holder>() {
        private var items: List<Course> = emptyList()
        private var thumbs: Map<Long, File?> = emptyMap()

        fun submit(list: List<Course>, t: Map<Long, File?>) {
            items = list; thumbs = t; notifyDataSetChanged()
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val letter: TextView = v.findViewById(R.id.thumb_letter)
            val title: TextView = v.findViewById(R.id.title)
            val line1: TextView = v.findViewById(R.id.line1)
            val line2: TextView = v.findViewById(R.id.line2)
            val status: TextView = v.findViewById(R.id.status)
            val progress: LinearProgressIndicator = v.findViewById(R.id.progress)
            val primary: MaterialButton = v.findViewById(R.id.btn_primary)
            val secondary: MaterialButton = v.findViewById(R.id.btn_secondary)
            val secondary2: MaterialButton = v.findViewById(R.id.btn_secondary2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_course, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val c = items[position]
            Ui.showThumb(h.thumb, h.letter, thumbs[c.id], c.title)
            h.title.text = c.title
            h.itemView.setOnClickListener(null)
            if (!downloadedList) {
                h.line1.text = "${c.lessonCount} lessons · ${c.progress}% complete"
                h.line2.text = c.categories.joinToString(", ")
                h.status.text = ""
                h.progress.visibility = View.GONE
                h.secondary.visibility = View.GONE; h.secondary2.visibility = View.GONE
                h.primary.text = getString(R.string.download_offline_pack)
                h.primary.isEnabled = repo.isOnline()
                h.primary.setOnClickListener { openDownload(c) }
                return
            }

            val lessons = repo.db.lessons(c.userId, c.id)
            val selected = lessons.count { it.isLesson && it.selected }
            val total = lessons.count { it.isLesson }
            h.line1.text = "$selected of $total lessons downloaded · ${OfflineLogic.formatBytes(maxOf(c.doneBytes, repo.courseBytesOnDisk(c.id)))}" +
                (if (c.downloadedAt > 0) " · ${Ui.date(this@OfflineLibraryActivity, c.downloadedAt)}" else "")
            h.line2.text = "${c.progress}% complete · ${Ui.syncLabel(c)}"
            h.status.text = Ui.stateLabel(c)
            h.status.setTextColor(Ui.stateColor(this@OfflineLibraryActivity, c))

            val active = c.state == CourseState.DOWNLOADING || c.state == CourseState.QUEUED
            if (active || c.state == CourseState.PAUSED) {
                val s = repo.summary(c.id)
                h.progress.visibility = View.VISIBLE
                h.progress.isIndeterminate = s.totalBytes <= 0
                if (s.totalBytes > 0) h.progress.setProgressCompat((s.doneBytes * 100 / s.totalBytes).toInt(), true)
            } else {
                h.progress.visibility = View.GONE
            }

            h.secondary2.visibility = View.VISIBLE
            h.secondary2.text = "Remove"
            h.secondary2.setOnClickListener { confirmRemove(c) }
            h.secondary.visibility = View.VISIBLE
            h.secondary.text = getString(R.string.sync_now)
            h.secondary.setOnClickListener { syncNow() }
            if (c.state == CourseState.DOWNLOADED || c.state == CourseState.FAILED) {
                h.primary.text = "Open"
                h.primary.setOnClickListener { openCourse(c) }
                h.itemView.setOnClickListener { openCourse(c) }
            } else {
                h.primary.text = "Details"
                h.primary.setOnClickListener { openDownload(c) }
                h.itemView.setOnClickListener { openDownload(c) }
            }
            h.primary.isEnabled = true
        }
    }
}
