package org.edusharing.app

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Native first-run screen to pick the edu-sharing server the WebView should open.
 * Only the server origin is chosen here; the SPA (app-login-page) handles the actual login.
 *
 * A rough rebuild of the old app's server picker: a list of publicly known servers fetched
 * from the app-registry (see [ServerRegistry]) as tappable rows (icon + name + URL + chevron,
 * connecting immediately), with "Enter your own server" as the last row revealing the manual
 * URL entry form. Neither is part of the eduBridge contract, both are a native UX convenience.
 */
class ServerUrlActivity : AppCompatActivity() {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val avatarColors by lazy {
        listOf(
            R.color.server_avatar_1,
            R.color.server_avatar_2,
            R.color.server_avatar_3,
            R.color.server_avatar_4,
            R.color.server_avatar_5,
            R.color.server_avatar_6,
        ).map { getColor(it) }
    }

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
        val container = findViewById<LinearLayout>(R.id.knownServersContainer)
        for (entry in entries) {
            val color = avatarColors[abs(entry.name.hashCode()) % avatarColors.size]
            addRow(
                container = container,
                iconText = entry.name.trim().take(1).uppercase().ifEmpty { "?" },
                iconColor = color,
                title = entry.name,
                subtitle = entry.url,
                onClick = { connect(entry.url) },
            )
        }
        addRow(
            container = container,
            iconText = "+",
            iconColor = getColor(R.color.server_picker_own_server_icon),
            title = getString(R.string.server_url_own_server),
            subtitle = null,
            onClick = { revealManualEntry() },
        )
    }

    private fun revealManualEntry() {
        val section = findViewById<View>(R.id.manualEntrySection)
        section.visibility = View.VISIBLE
        findViewById<EditText>(R.id.serverUrlInput).requestFocus()
    }

    /** Builds one "icon + title/subtitle + chevron" row, styled after the old app's list. */
    private fun addRow(
        container: LinearLayout,
        iconText: String,
        iconColor: Int,
        title: String,
        subtitle: String?,
        onClick: () -> Unit,
    ) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        // Named to avoid shadowing View.background inside the nested apply {} blocks below --
        // a bare `background = ...` there would otherwise try to reassign this val instead of
        // setting the receiver's own property (Kotlin resolves enclosing-scope locals before
        // receiver members for unqualified assignment).
        val rippleBackground = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true
            isFocusable = true
            setBackgroundResource(rippleBackground.resourceId)
            setOnClickListener { onClick() }
        }

        val icon = TextView(this).apply {
            text = iconText
            setTextColor(getColor(android.R.color.white))
            textSize = 18f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(iconColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
                marginEnd = dp(8)
            }
        }
        textColumn.addView(
            TextView(this).apply {
                text = title
                setTextColor(themeColor(android.R.attr.textColorPrimary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 16f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        if (!subtitle.isNullOrEmpty()) {
            textColumn.addView(
                TextView(this).apply {
                    text = subtitle
                    setTextColor(themeColor(android.R.attr.textColorSecondary))
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }

        val chevron = TextView(this).apply {
            text = "›"
            textSize = 22f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
        }

        row.addView(icon)
        row.addView(textColumn)
        row.addView(chevron)
        container.addView(row)
    }

    private fun themeColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) getColor(typedValue.resourceId) else typedValue.data
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SERVER_URL = "org.edusharing.app.extra.SERVER_URL"
    }
}
