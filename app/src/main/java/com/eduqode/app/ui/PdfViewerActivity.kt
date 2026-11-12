package com.eduqode.app.ui

import com.eduqode.app.tr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eduqode.app.R
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Reads a downloaded PDF inside the app, without internet (Android's built-in PdfRenderer). */
class PdfViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
    }

    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val lock = Mutex()
    private val cache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf)
        Ui.applyInsets(findViewById(R.id.root))
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = intent.getStringExtra(EXTRA_TITLE) ?: "PDF"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        val file = File(intent.getStringExtra(EXTRA_PATH) ?: "")
        try {
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fd!!)
        } catch (e: Exception) {
            Toast.makeText(this, tr("This PDF could not be opened."), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        findViewById<RecyclerView>(R.id.pages).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = PageAdapter()
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {
        inner class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun getItemCount() = renderer?.pageCount ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val iv = ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = 8 }
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
                minimumHeight = 400
            }
            return Holder(iv)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            h.image.tag = position
            val cached = cache.get(position)
            if (cached != null) { h.image.setImageBitmap(cached); return }
            h.image.setImageDrawable(null)
            val width = resources.displayMetrics.widthPixels.coerceAtMost(1600)
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { render(position, width) } ?: return@launch
                cache.put(position, bmp)
                if (h.image.tag == position) h.image.setImageBitmap(bmp)
            }
        }
    }

    private suspend fun render(index: Int, width: Int): Bitmap? = lock.withLock {
        val r = renderer ?: return null
        runCatching {
            r.openPage(index).use { page ->
                val height = (width.toFloat() / page.width * page.height).toInt()
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }.getOrNull()
    }

    override fun onDestroy() {
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        super.onDestroy()
    }
}
