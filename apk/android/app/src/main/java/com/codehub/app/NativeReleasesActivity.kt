package com.codehub.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Releases e issues públicos del repo, sin token, sin WebView y sin sesión. */
class NativeReleasesActivity : Activity() {

    private data class Entry(val title: String, val meta: String, val body: String, val url: String)

    private val bg = Color.BLACK
    private val panel = 0xE6171310.toInt()
    private val panelSoft = 0x266F8F72.toInt()
    private val textPrimary = 0xFFF4ECE1.toInt()
    private val secondary = 0xFFC2B09B.toInt()
    private val muted = 0xFF94836F.toInt()
    private val accent = 0xFFB85C3C.toInt()
    private val border = 0x3DB85C3C.toInt()

    private val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private val loading = ProgressBar(this)
    private val message = TextView(this)
    private val retry = Button(this)
    private val releasesTab = TextView(this)
    private val issuesTab = TextView(this)

    private var releases = emptyList<Entry>()
    private var issues = emptyList<Entry>()
    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(bg)
        buildUi()
        load()
    }

    private fun buildUi() {
        container.setBackgroundColor(bg)
        container.setPadding(dp(18), dp(18), dp(18), dp(24))

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 30f; setTextColor(accent); setPadding(0, 0, dp(12), 0)
            isClickable = true; isFocusable = true; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, -2))
        header.addView(TextView(this).apply {
            text = "Novedades"; textSize = 20f; setTextColor(textPrimary); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(header)

        container.addView(TextView(this).apply {
            text = "Releases e issues públicos de CodeHub"
            textSize = 12f; setTextColor(muted)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(tabView(releasesTab, "Releases") { selectTab(0) }, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tabView(issuesTab, "Issues") { selectTab(1) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        container.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        message.textSize = 12f
        message.setTextColor(muted)
        message.visibility = View.GONE
        container.addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        retry.text = "Reintentar"
        retry.textSize = 12f
        retry.isAllCaps = false
        retry.visibility = View.GONE
        retry.setOnClickListener { load() }
        container.addView(retry, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        container.addView(loading, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
            topMargin = dp(20); gravity = Gravity.CENTER_HORIZONTAL
        })
        container.addView(listHost, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isVerticalScrollBarEnabled = false
            addView(container)
        }
        setContentView(scroll)
    }

    private fun tabView(view: TextView, label: String, onClick: () -> Unit): TextView = view.apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(9), dp(10), dp(9))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun selectTab(value: Int) {
        tab = value
        render()
    }

    private fun load() {
        loading.visibility = View.VISIBLE
        message.visibility = View.GONE
        retry.visibility = View.GONE
        listHost.removeAllViews()
        Thread {
            val releasesJson = fetch(RELEASES_URL)
            val issuesJson = fetch(ISSUES_URL)
            val loadedReleases = if (releasesJson != null) parseReleases(releasesJson) else emptyList()
            val loadedIssues = if (issuesJson != null) parseIssues(issuesJson) else emptyList()
            runOnUiThread {
                releases = loadedReleases
                issues = loadedIssues
                loading.visibility = View.GONE
                if (releasesJson == null && issuesJson == null) {
                    message.text = "No se pudo consultar GitHub. Revisa la conexión e inténtalo de nuevo."
                    message.visibility = View.VISIBLE
                    retry.visibility = View.VISIBLE
                } else {
                    render()
                }
            }
        }.start()
    }

    private fun render() {
        releasesTab.background = tabBackground(tab == 0)
        issuesTab.background = tabBackground(tab == 1)
        releasesTab.setTextColor(if (tab == 0) accent else muted)
        issuesTab.setTextColor(if (tab == 1) accent else muted)
        releasesTab.text = "Releases (${releases.size})"
        issuesTab.text = "Issues (${issues.size})"

        val entries = if (tab == 0) releases else issues
        listHost.removeAllViews()
        if (entries.isEmpty()) {
            message.text = if (tab == 0) "Sin releases publicadas." else "No hay issues abiertas."
            message.visibility = View.VISIBLE
            return
        }
        message.visibility = View.GONE
        for (entry in entries) listHost.addView(entryCard(entry), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
    }

    private fun entryCard(entry: Entry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(panel, dp(14))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(TextView(this@NativeReleasesActivity).apply {
            text = entry.title
            textSize = 14f
            setTextColor(textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        })
        addView(TextView(this@NativeReleasesActivity).apply {
            text = entry.meta
            textSize = 10f
            setTextColor(muted)
            maxLines = 1
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        if (entry.body.isNotBlank()) {
            addView(TextView(this@NativeReleasesActivity).apply {
                text = entry.body
                textSize = 11f
                setTextColor(secondary)
                maxLines = 3
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        addView(TextView(this@NativeReleasesActivity).apply {
            text = "Abrir en GitHub ›"
            textSize = 11f
            setTextColor(accent)
            isClickable = true
            isFocusable = true
            setOnClickListener { openRepoUrl(entry.url) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
    }

    private fun parseReleases(array: JSONArray): List<Entry> {
        val result = mutableListOf<Entry>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val tag = item.optString("tag_name")
            val name = item.optString("name").ifBlank { tag }
            val date = item.optString("published_at").substringBefore('T')
            result.add(Entry(
                title = name.ifBlank { "Release" },
                meta = listOf(tag, date).filter { it.isNotBlank() }.joinToString(" · "),
                body = item.optString("body").replace('\r', ' ').replace('\n', ' ').take(220),
                url = item.optString("html_url")
            ))
        }
        return result
    }

    private fun parseIssues(array: JSONArray): List<Entry> {
        val result = mutableListOf<Entry>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optBoolean("pull_request")) continue
            val number = item.optInt("number")
            val date = item.optString("updated_at").substringBefore('T')
            result.add(Entry(
                title = "#$number ${item.optString("title")}",
                meta = "Actualizada $date",
                body = item.optString("body").replace('\r', ' ').replace('\n', ' ').take(220),
                url = item.optString("html_url")
            ))
        }
        return result
    }

    private fun fetch(url: String): JSONArray? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "CodeHub-Android")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line)
                sb.toString()
            }
            return JSONArray(body)
        } catch (e: Exception) {
            Log.e("CodeHub", "No se pudo consultar $url", e)
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private fun openRepoUrl(url: String) {
        try {
            val uri = Uri.parse(url)
            val path = uri.path.orEmpty()
            val allowed = "https".equals(uri.scheme, true)
                && "github.com".equals(uri.host, true)
                && path.startsWith("/wilson360-labs/CodeHub/")
            if (allowed) startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Log.e("CodeHub", "No se pudo abrir el enlace de GitHub", e)
        }
    }

    private fun tabBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(if (active) panelSoft else panel)
        setStroke(dp(1), border)
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
        setStroke(dp(1), border)
    }

    private fun dp(value: Int): Int = Math.round(value * resources.displayMetrics.density)

    private companion object {
        const val RELEASES_URL = "https://api.github.com/repos/wilson360-labs/CodeHub/releases?per_page=10"
        const val ISSUES_URL = "https://api.github.com/repos/wilson360-labs/CodeHub/issues?state=open&per_page=10&sort=updated"
    }
}
