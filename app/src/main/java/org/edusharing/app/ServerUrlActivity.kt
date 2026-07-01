package org.edusharing.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Native first-run screen to pick the edu-sharing server the WebView should open.
 * Only the server origin is chosen here; the SPA (app-login-page) handles the actual login.
 */
class ServerUrlActivity : AppCompatActivity() {

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
    }

    companion object {
        const val EXTRA_SERVER_URL = "org.edusharing.app.extra.SERVER_URL"
    }
}
