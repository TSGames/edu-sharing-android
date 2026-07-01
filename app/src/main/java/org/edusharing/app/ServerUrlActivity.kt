package org.edusharing.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Native first-run screen to pick the edu-sharing server the WebView should open.
 * Only the server origin is chosen here; the SPA (app-login-page) handles the actual login.
 *
 * Alongside manual entry, offers a quick-pick list of publicly known servers fetched from
 * the app-registry (see [ServerRegistry]) — a native UX convenience, not part of the
 * eduBridge contract.
 */
class ServerUrlActivity : AppCompatActivity() {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_url)

        val input = findViewById<EditText>(R.id.serverUrlInput)
        input.setText(ServerConfig.getServerUrl(this).orEmpty())

        findViewById<Button>(R.id.continueButton).setOnClickListener {
            val url = input.text.toString()
            if (!ServerConfig.isUsable(url)) {
                Toast.makeText(this, R.string.server_url_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_SERVER_URL, ServerConfig.normalize(url)),
            )
            finish()
        }

        ioExecutor.execute {
            val entries = ServerRegistry.fetch()
            runOnUiThread { populateKnownServers(entries) }
        }
    }

    private fun populateKnownServers(entries: List<ServerRegistry.Entry>) {
        if (entries.isEmpty()) return
        findViewById<View>(R.id.knownServersHeading).visibility = View.VISIBLE
        val container = findViewById<LinearLayout>(R.id.knownServersContainer)
        val input = findViewById<EditText>(R.id.serverUrlInput)
        val rowPadding = (resources.displayMetrics.density * 12).toInt()
        val background = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        for (entry in entries) {
            val row = TextView(this).apply {
                text = "${entry.name}\n${entry.url}"
                setPadding(0, rowPadding, 0, rowPadding)
                isClickable = true
                isFocusable = true
                setBackgroundResource(background.resourceId)
                setOnClickListener { input.setText(entry.url) }
            }
            container.addView(row)
        }
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SERVER_URL = "org.edusharing.app.extra.SERVER_URL"
    }
}
