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
        toolbar.inflateMenu(R.menu.library_menu)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_sync -> { syncNow(); true }
                R.id.action_certificates -> { startActivity(android.content.Intent(this, CertificatesActivity::class.java)); true }
                else -> false
            }
        }

        findViewById<RecyclerView>(R.id.downloaded_list).apply { layoutManager = LinearLayoutManager(context); adapter = downloaded }
        findViewById<RecyclerView>(R.id.available_list).apply { layoutManager = LinearLayoutManager(context); adapter = available }

        val wifi = findViewById<MaterialSwitch>(R.id.wifi_switch)
        wifi.isChecked = repo.wifiOnly
        wifi.setOnCheckedChangeListener { _, checked -> repo.wifiOnly = checked }

        findViewById<SwipeRefreshLayout>(R.id.swipe).setOnRefreshListener { refreshFromServer(force = true) }
        findViewById<MaterialButton>(R.id.login_button).setOnClickListener {
            Ui.openWebsite(this, "${ApiClient.SITE}/dashboard/")
            finish()
        }

        // Keep the list fresh while visible (download progress, sync status).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val active = render()
                    delay(if (active) 1500 else 5000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFromServer()
    }

    private fun refreshFromServer(force: Boolean = false) {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe)
        lifecycleScope.launch {
            val state = Session.refresh(this@OfflineLibraryActivity)
            loggedIn = state is Session.State.LoggedIn
            showLogin(state)
            if (loggedIn && repo.isOnline()) {
                runCatching { repo.refreshEnrolled() }
                repo.syncNow()
                repo.updateAllDownloaded(force)
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

    /** Disk sizes are slow to measure, so they are re-measured at most every 30 seconds. */
    private var sizesAt = 0L
    private var usedCache = 0L
    private val diskCache = HashMap<Long, Long>()

    /** Refreshes the screen; returns true while a download is running (so it refreshes faster). */
    private suspend fun render(): Boolean {
        val uid = repo.userId
        val data = withContext(Dispatchers.IO) {
            val courses = if (uid > 0 && repo.auth.isLoggedIn) repo.db.courses(uid) else emptyList()
            val thumbs = courses.associate { c ->
                c.id to repo.db.files(uid, c.id).firstOrNull { it.kind == FileKind.THUMBNAIL && it.state == "done" }?.let { File(it.localPath) }
            }
            val now = System.currentTimeMillis()
            if (now - sizesAt > 30_000) {
                sizesAt = now
                usedCache = repo.usedBytes()
                diskCache.clear()
                courses.filter { it.state != CourseState.NONE }.forEach { diskCache[it.id] = repo.courseBytesOnDisk(it.id) }
            }
            val extras = courses.filter { it.state != CourseState.NONE }.associate { c ->
                val lessons = repo.db.lessons(uid, c.id)
                val active = c.state == CourseState.DOWNLOADING || c.state == CourseState.QUEUED || c.state == CourseState.PAUSED
                val s = if (active) repo.summary(c.id) else null
                c.id to Extra(lessons.count { it.isLesson && it.selected }, lessons.count { it.isLesson },
                    maxOf(c.doneBytes, diskCache[c.id] ?: 0L), s?.doneBytes ?: 0L, s?.totalBytes ?: 0L)
            }
            Triple(Triple(courses, thumbs, extras), Pair(usedCache, repo.freeBytes()), Unit)
        }
        val (main, storage) = data
        val (courses, thumbs, extras) = main
        val down = courses.filter { it.state != CourseState.NONE }
        val avail = courses.filter { it.state == CourseState.NONE && !it.accessExpired }

        downloaded.submit(down, thumbs, extras)
        available.submit(avail, thumbs, emptyMap())
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
        return down.any { it.state == CourseState.DOWNLOADING || it.state == CourseState.QUEUED }
    }

    private data class Extra(val selected: Int, val total: Int, val bytes: Long, val doneBytes: Long, val totalBytes: Long)

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
                lifecycleScope.launch { repo.removeDownload(c.id); sizesAt = 0; render() }
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
        private var extras: Map<Long, Extra> = emptyMap()
        private var online = false

        fun submit(list: List<Course>, t: Map<Long, File?>, e: Map<Long, Extra>) {
            val nowOnline = repo.isOnline()
            if (list == items && t == thumbs && e == extras && nowOnline == online) return
            items = list; thumbs = t; extras = e; online = nowOnline
            notifyDataSetChanged()
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
                h.primary.isEnabled = online
                h.primary.setOnClickListener { openDownload(c) }
                return
            }

            val x = extras[c.id] ?: Extra(0, 0, c.doneBytes, 0, 0)
            h.line1.text = "${x.selected} of ${x.total} lessons downloaded · ${OfflineLogic.formatBytes(x.bytes)}" +
                (if (c.downloadedAt > 0) " · ${Ui.date(this@OfflineLibraryActivity, c.downloadedAt)}" else "")
            h.line2.text = "${c.progress}% complete · ${Ui.syncLabel(c)}"
            h.status.text = Ui.stateLabel(c)
            h.status.setTextColor(Ui.stateColor(this@OfflineLibraryActivity, c))

            val active = c.state == CourseState.DOWNLOADING || c.state == CourseState.QUEUED
            if (active || c.state == CourseState.PAUSED) {
                h.progress.visibility = View.VISIBLE
                h.progress.isIndeterminate = x.totalBytes <= 0
                if (x.totalBytes > 0) h.progress.setProgressCompat((x.doneBytes * 100 / x.totalBytes).toInt(), true)
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
