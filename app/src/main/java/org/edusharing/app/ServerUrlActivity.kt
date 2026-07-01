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
 * Primary path: a quick-pick list of publicly known servers fetched from the app-registry
 * (see [ServerRegistry]) -- tapping a row connects immediately. Manual URL entry is offered
 * as the secondary/fallback path below it. Neither is part of the eduBridge contract, both
 * are a native UX convenience.
 */
class ServerUrlActivity : AppCompatActivity() {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_url)

        val input = findViewById<EditText>(R.id.serverUrlInput)
        input.setText(ServerConfig.getServerUrl(this).orEmpty())

        findViewById<Button>(R.id.continueButton).setOnClickListener {
            connect(input.text.toString())
        }

        ioExecutor.execute {
            val entries = ServerRegistry.fetch()
            runOnUiThread { populateKnownServers(entries) }
        }
    }

    /** Validates and returns the chosen server URL to the caller (MainActivity), or toasts. */
    private fun connect(url: String) {
        if (!ServerConfig.isUsable(url)) {
            Toast.makeText(this, R.string.server_url_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_SERVER_URL, ServerConfig.normalize(url)),
        )
        finish()
    }

    private fun populateKnownServers(entries: List<ServerRegistry.Entry>) {
        findViewById<View>(R.id.knownServersProgress).visibility = View.GONE
        if (entries.isEmpty()) return
        findViewById<View>(R.id.knownServersHeading).visibility = View.VISIBLE
        val container = findViewById<LinearLayout>(R.id.knownServersContainer)
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
                setOnClickListener { connect(entry.url) }
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
