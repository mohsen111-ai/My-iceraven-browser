package org.mozilla.fenix.perf

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.BuildConfig
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/** An opt-in synthetic test. It never opens a normal browser tab or reads browser storage. */
class PerformanceTestActivity : ComponentActivity() {
    private lateinit var runButton: Button
    private lateinit var cancelButton: Button
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var engineView: GeckoView
    private lateinit var animationTarget: TextView
    private var running: Job? = null
    private var session: GeckoSession? = null
    private val connection = AtomicReference<HttpsURLConnection?>()
    private val preferences by lazy { getSharedPreferences("performance-tests-v1", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.performance_test_title)
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (16 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val padding = (16 * resources.displayMetrics.density).toInt()
            view.setPadding(
                padding + bars.left,
                padding + bars.top,
                padding + bars.right,
                padding + bars.bottom,
            )
            insets
        }
        fun label(value: String) =
            TextView(this).apply {
                text = value
                layout.addView(this)
            }
        fun button(title: Int, action: () -> Unit) =
            Button(this).apply {
                setText(title)
                setOnClickListener { action() }
                layout.addView(this)
            }
        label(getString(R.string.performance_test_title))
        label(getString(R.string.performance_test_explanation))
        runButton =
            button(R.string.performance_test_run) {
                AlertDialog.Builder(this)
                    .setMessage(R.string.performance_test_consent)
                    .setPositiveButton(R.string.performance_test_run) { _, _ -> startTest() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        cancelButton = button(android.R.string.cancel) { cancelTest() }.apply { isEnabled = false }
        button(R.string.performance_test_copy) {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Iceraven performance", export()))
            status.text = getString(R.string.performance_test_copied)
        }
        button(R.string.performance_test_share) {
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, export())
                }
            startActivity(Intent.createChooser(intent, getString(R.string.performance_test_share)))
        }
        button(R.string.performance_test_clear) {
            preferences.edit().remove("recent").apply()
            results.text = ""
        }
        status = label(getString(R.string.performance_test_idle))
        animationTarget = label("●")
        engineView = GeckoView(this)
        layout.addView(
            engineView,
            LinearLayout.LayoutParams(-1, (220 * resources.displayMetrics.density).toInt()),
        )
        results = label(summary()).apply { setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun startTest() {
        if (running?.isActive == true) return
        runButton.isEnabled = false
        cancelButton.isEnabled = true
        running =
            lifecycleScope.launch {
                val report =
                    JSONObject().apply {
                        put("schema", 1)
                        put("timestamp_utc_ms", System.currentTimeMillis())
                        put("browser_version", BuildConfig.VERSION_NAME)
                        put("build_type", BuildConfig.BUILD_TYPE)
                        put("debug_build", BuildConfig.DEBUG)
                        put("device_model", Build.MODEL)
                        put("android_version", Build.VERSION.RELEASE)
                        put("android_api", Build.VERSION.SDK_INT)
                        put("startup", "Unavailable: an in-app run cannot measure cold startup")
                        put(
                            "scope",
                            "Synthetic private Gecko fixture; not website speed or a uBlock Origin benchmark",
                        )
                        put(
                            "rendering_limit",
                            "Two animation frames indicate a render opportunity, not proof of pixels on screen",
                        )
                    }
                try {
                    status.text = "1/3: Gecko page, images and JavaScript (up to 60 seconds)…"
                    stage(report, "gecko") { measureGecko() }
                    status.text = "2/3: Native UI animation frame timing…"
                    stage(report, "native_ui") { measureFrames() }
                    status.text = "3/3: Downloading 10 MiB HTTPS test file…"
                    stage(report, "network") { measureNetwork() }
                    report.put(
                        "status",
                        "Finished; check individual stages for errors or unavailable metrics",
                    )
                    status.text = "Finished. Results are saved locally."
                } catch (error: CancellationException) {
                    report.put("status", "Cancelled; unfinished stages have no result")
                    status.text = "Cancelled. Partial results saved."
                    throw error
                } finally {
                    closeSession()
                    save(report)
                    results.text = summary()
                    runButton.isEnabled = true
                    cancelButton.isEnabled = false
                }
            }
    }

    private suspend fun stage(report: JSONObject, name: String, measure: suspend () -> JSONObject) {
        try {
            report.put(name, measure())
        } catch (_: TimeoutCancellationException) {
            report.put(name, JSONObject().put("error", "Test timed out; no measurement available"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // No request URLs, browser data, stack traces or identifiers enter exported errors.
            report.put(
                name,
                JSONObject().put("error", error.message?.take(180) ?: error.javaClass.simpleName),
            )
        }
    }

    private suspend fun measureGecko(): JSONObject {
        val token = UUID.randomUUID().toString()
        val completed = CompletableDeferred<JSONObject>()
        val fixture =
            withContext(Dispatchers.IO) {
                val script =
                    assets
                        .open("performance/runner.js")
                        .bufferedReader()
                        .use { it.readText() }
                        .replace("__TOKEN__", token)
                assets
                    .open("performance/page.html")
                    .bufferedReader()
                    .use { it.readText() }
                    .replace("/*PERFORMANCE_SCRIPT*/", script)
            }
        val started = SystemClock.elapsedRealtimeNanos()
        var loadRequestedAt = 0L
        var pageStopNanos: Long? = null
        val testSession = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).build())
        session = testSession
        testSession.navigationDelegate =
            object : GeckoSession.NavigationDelegate {
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest,
                ): GeckoResult<AllowOrDeny> {
                    val uri = android.net.Uri.parse(request.uri)
                    if (
                        uri.scheme == "https" &&
                            uri.host == "performance.invalid" &&
                            uri.path == "/report" &&
                            uri.getQueryParameter("token") == token
                    ) {
                        try {
                            val data =
                                uri.getQueryParameter("data") ?: error("Missing fixture report")
                            require(data.length < 16384) { "Fixture report too large" }
                            completed.complete(JSONObject(data))
                        } catch (error: Exception) {
                            completed.completeExceptionally(error)
                        }
                    }
                    // No links, redirects, popups or report requests leave the fixture session.
                    return GeckoResult.fromValue(
                        if (uri.scheme == "data" || request.uri == "about:blank") AllowOrDeny.ALLOW
                        else AllowOrDeny.DENY
                    )
                }
            }
        testSession.progressDelegate =
            object : GeckoSession.ProgressDelegate {
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    if (success && loadRequestedAt > 0 && pageStopNanos == null) {
                        pageStopNanos = SystemClock.elapsedRealtimeNanos() - loadRequestedAt
                    }
                    if (!success && !completed.isCompleted)
                        completed.completeExceptionally(
                            IllegalStateException("Fixture navigation failed")
                        )
                }
            }
        try {
            engineView.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, engineView.width, engineView.height),
                true,
            )
            testSession.open(components.core.geckoRuntime)
            engineView.setSession(testSession)
            testSession.setActive(true)
            loadRequestedAt = SystemClock.elapsedRealtimeNanos()
            testSession.load(GeckoSession.Loader().data(fixture, "text/html"))
            return withTimeout(60_000) { completed.await() }
                .apply {
                    pageStopNanos?.let {
                        put("native_load_request_to_page_stop_ms", it / 1_000_000.0)
                    }
                    put(
                        "whole_fixture_elapsed_ms",
                        (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                    )
                    put(
                        "page_source",
                        "Bundled document; navigation timings exclude network transfer",
                    )
                    put(
                        "image_source",
                        "Pinned GitHub PNG; 38625 bytes each; three uncached requests",
                    )
                }
        } finally {
            closeSession()
        }
    }

    private fun closeSession() {
        session?.let {
            engineView.releaseSession()
            it.close()
        }
        session = null
    }

    @Suppress("DEPRECATION")
    private suspend fun measureFrames(): JSONObject {
        val refreshRate = windowManager.defaultDisplay.refreshRate.toDouble()
        check(refreshRate > 0) { "Display refresh rate unavailable" }
        val durations = mutableListOf<Long>()
        var dropped = 0
        var overBudget = 0
        val listener =
            Window.OnFrameMetricsAvailableListener { _, metrics, missed ->
                dropped += missed
                val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (duration >= 0) {
                    durations.add(duration)
                    val budget =
                        if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE)
                        else (1_000_000_000 / refreshRate).toLong()
                    if (budget > 0 && duration > budget) overBudget++
                }
            }
        val animation =
            ValueAnimator.ofFloat(0f, 80 * resources.displayMetrics.density).apply {
                duration = 500
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { animationTarget.translationX = it.animatedValue as Float }
            }
        animationTarget.requestRectangleOnScreen(
            android.graphics.Rect(0, 0, animationTarget.width, animationTarget.height),
            true,
        )
        window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        try {
            animation.start()
            delay(3_000)
        } finally {
            animation.cancel()
            window.removeOnFrameMetricsAvailableListener(listener)
            animationTarget.translationX = 0f
        }
        check(durations.isNotEmpty()) { "No frame timing samples delivered" }
        return JSONObject().apply {
            put(
                "scope",
                "3-second native marker animation; excludes Gecko compositor and touch latency",
            )
            put("frames", durations.size)
            put("callback_samples_dropped", dropped)
            put("total_duration_p95_ms", PerformanceMath.percentile95(durations) / 1_000_000.0)
            put("over_budget_frames", overBudget)
            put(
                "budget_method",
                if (Build.VERSION.SDK_INT >= 31) "FrameMetrics.DEADLINE"
                else "Estimate from initial refresh rate; not exact jank",
            )
            put("initial_refresh_hz", refreshRate)
        }
    }

    private suspend fun measureNetwork(): JSONObject =
        withContext(Dispatchers.IO) {
            val expected = 10 * 1024 * 1024L
            val url = URL("https://speed.cloudflare.com/__down?bytes=$expected")
            val request =
                (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Cache-Control", "no-cache")
                }
            connection.set(request)
            val started = SystemClock.elapsedRealtimeNanos()
            try {
                ensureActive()
                check(request.responseCode == 200) { "Download HTTP ${request.responseCode}" }
                val declared = request.contentLengthLong
                check(declared == -1L || declared == expected) {
                    "Unexpected declared file size: $declared"
                }
                var bytes = 0L
                var firstByte = 0L
                var lastProgress = started
                val buffer = ByteArray(64 * 1024)
                request.inputStream.use { input ->
                    while (true) {
                        ensureActive()
                        check(SystemClock.elapsedRealtimeNanos() - started < 60_000_000_000L) {
                            "Download exceeded 60 seconds"
                        }
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (firstByte == 0L) firstByte = SystemClock.elapsedRealtimeNanos()
                        bytes += count
                        check(bytes <= expected) { "Test endpoint returned more than 10 MiB" }
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (now - lastProgress > 250_000_000) {
                            lastProgress = now
                            withContext(Dispatchers.Main) {
                                status.text = "3/3: Downloaded $bytes / $expected bytes…"
                            }
                        }
                    }
                }
                check(bytes == expected) { "Incomplete test file: $bytes of $expected bytes" }
                val ended = SystemClock.elapsedRealtimeNanos()
                JSONObject().apply {
                    put("source", "speed.cloudflare.com; 10 MiB; discarded in memory")
                    put(
                        "scope",
                        "Android HTTPS network probe, not Gecko downloads or disk write speed",
                    )
                    put("bytes", bytes)
                    put("request_to_first_byte_ms", (firstByte - started) / 1_000_000.0)
                    put("total_ms", (ended - started) / 1_000_000.0)
                    put(
                        "end_to_end_mbps",
                        PerformanceMath.megabitsPerSecond(bytes, ended - started),
                    )
                }
            } finally {
                connection.compareAndSet(request, null)
                request.disconnect()
            }
        }

    private fun cancelTest() {
        if (running?.isActive == true)
            status.text = "Cancelling… network reads may take up to 15 seconds to stop."
        running?.cancel()
        closeSession()
        // Reads have a 15-second timeout; never block the Android UI while disconnecting.
        connection.getAndSet(null)?.let { request ->
            lifecycleScope.launch(Dispatchers.IO) { request.disconnect() }
        }
    }

    override fun onStop() {
        cancelTest()
        super.onStop()
    }

    private fun save(report: JSONObject) {
        val previous = recent()
        val next = JSONArray().put(report)
        for (index in 0 until minOf(previous.length(), 9)) next.put(previous.getJSONObject(index))
        preferences.edit().putString("recent", next.toString()).apply()
    }

    private fun recent(): JSONArray =
        try {
            JSONArray(preferences.getString("recent", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

    private fun export(): String = recent().toString(2)

    private fun summary(): String {
        val reports = recent()
        val latest = reports.optJSONObject(0) ?: return "No saved results."
        return buildString {
            appendLine(
                "${reports.length()} recent report(s) saved. Copy or share exports all reports."
            )
            appendLine("${latest.optString("browser_version")} (${latest.optString("build_type")})")
            appendLine(
                "${latest.optString("device_model")} • Android ${latest.optString("android_version")}"
            )
            appendLine(latest.optString("status"))
            fun metric(label: String, data: JSONObject?, key: String, unit: String = "ms") {
                val value = data?.opt(key)
                val display =
                    if (value is Number)
                        "${String.format(Locale.US, "%.2f", value.toDouble())} $unit"
                    else value?.toString() ?: "Unavailable"
                appendLine("$label: $display")
            }
            val gecko = latest.optJSONObject("gecko")
            appendLine("\nControlled local page (no network navigation):")
            metric("DOM content loaded", gecko, "dom_content_loaded_ms")
            metric(
                "Gecko load request to page-stop callback",
                gecko,
                "native_load_request_to_page_stop_ms",
            )
            metric("Document load", gecko, "document_load_ms")
            metric("First contentful paint", gecko, "first_contentful_paint_ms")
            metric("DOM work to render opportunity", gecko, "fixture_dom_to_render_opportunity_ms")
            for (key in listOf("error", "page_error", "image_error", "javascript_error")) {
                if (gecko?.has(key) == true) appendLine(gecko.getString(key))
            }
            val images = gecko?.optJSONArray("images")
            for (index in 0 until (images?.length() ?: 0)) {
                val image = images?.optJSONObject(index)
                appendLine("\nImage ${index + 1} (38,625 bytes):")
                metric("HTTPS fetch", image, "fetch_ms")
                metric("Decode", image, "decode_ms")
                metric("Fetch to render opportunity", image, "fetch_to_render_opportunity_ms")
            }
            metric("\nJavaScript workload median", gecko?.optJSONObject("javascript"), "median_ms")
            appendLine("\nNative UI only (not webpage scrolling):")
            val ui = latest.optJSONObject("native_ui")
            metric("Frame duration p95", ui, "total_duration_p95_ms")
            metric("Over-budget frames", ui, "over_budget_frames", "frames")
            metric("Measured frames", ui, "frames", "frames")
            metric("Dropped callback samples", ui, "callback_samples_dropped", "samples")
            appendLine(ui?.optString("budget_method", "Unavailable") ?: "Unavailable")
            if (ui?.has("error") == true) appendLine(ui.getString("error"))
            appendLine("\nNetwork only (not Gecko download/disk speed):")
            val network = latest.optJSONObject("network")
            metric("End-to-end throughput", network, "end_to_end_mbps", "Mbps")
            metric("Request to first byte", network, "request_to_first_byte_ms")
            if (network?.has("error") == true) appendLine(network.getString("error"))
            appendLine("\nCold startup: unavailable from an in-app test.")
            appendLine(
                "Render opportunity is not proof of pixels being displayed. Keep conditions consistent between runs."
            )
        }
    }
}
