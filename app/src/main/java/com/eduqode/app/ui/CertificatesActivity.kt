package com.eduqode.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.eduqode.app.R
import com.eduqode.app.offline.OfflineRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Certificates of completed courses, kept on the phone: open and share them without internet. */
class CertificatesActivity : AppCompatActivity() {

    private lateinit var repo: OfflineRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_certificates)
        Ui.applyInsets(findViewById(R.id.root))
        repo = OfflineRepository(this)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = "My certificates"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        render()
        if (repo.isOnline()) lifecycleScope.launch {
            runCatching { repo.refreshEnrolled() }
            render()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { repo.certificates() }
            val list = findViewById<LinearLayout>(R.id.list)
            while (list.childCount > 1) list.removeViewAt(1)
            findViewById<View>(R.id.empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            items.forEach { (cert, file) ->
                list.addView(MaterialCardView(this@CertificatesActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        addView(TextView(context).apply { text = cert.courseTitle; textSize = 17f })
                        if (file != null) {
                            val bmp = withContext(Dispatchers.IO) {
                                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })
                            }
                            addView(ImageView(context).apply {
                                setImageBitmap(bmp); adjustViewBounds = true
                                setPadding(0, dp(8), 0, dp(8))
                                setOnClickListener { open(file, "image/jpeg", Intent.ACTION_VIEW) }
                            })
                            addView(LinearLayout(context).apply {
                                addView(MaterialButton(context).apply { text = "Open"; setOnClickListener { open(file, "image/jpeg", Intent.ACTION_VIEW) } })
                                addView(MaterialButton(context).apply {
                                    text = "Share"; layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
                                    setOnClickListener { open(file, "image/jpeg", Intent.ACTION_SEND) }
                                })
                            })
                        } else {
                            addView(TextView(context).apply {
                                text = "Open it once while online to save a copy on this phone."
                                setPadding(0, dp(6), 0, dp(6))
                            })
                            addView(MaterialButton(context).apply {
                                text = "Open certificate"
                                isEnabled = cert.viewUrl.isNotBlank()
                                setOnClickListener {
                                    if (repo.isOnline()) Ui.openWebsite(this@CertificatesActivity, cert.viewUrl)
                                    else Toast.makeText(this@CertificatesActivity, "Connect to the internet to open it the first time.", Toast.LENGTH_LONG).show()
                                }
                            })
                        }
                    })
                })
            }
        }
    }

    private fun open(file: java.io.File, mime: String, action: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(action).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (action == Intent.ACTION_SEND) intent.setType(mime).putExtra(Intent.EXTRA_STREAM, uri) else intent.setDataAndType(uri, mime)
        runCatching { startActivity(if (action == Intent.ACTION_SEND) Intent.createChooser(intent, "Share certificate") else intent) }
            .onFailure { Toast.makeText(this, "No app on this phone can open pictures.", Toast.LENGTH_LONG).show() }
    }
}
