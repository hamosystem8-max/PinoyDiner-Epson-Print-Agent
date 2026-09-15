/** Pinoy Diner Epson Print Agent | v1.5.0 | 2026-08-30 */
package com.pinoydiner.printagent

import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val DASHBOARD_URL = "https://pinoy-diner-kitchen-print-manager-wkh2pi.v2.appdeploy.ai/"
        private const val PREFS = "pinoy_diner_printer"
        private const val KEY_IP = "printer_ip"
        private const val KEY_PORT = "printer_port"
    }

    private lateinit var webView: WebView
    private val worker = Executors.newSingleThreadExecutor()

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // The Android agent deliberately has no separate native dashboard.
        // The live Pinoy Diner web dashboard is the entire UI so Android and Web always match.
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.settings.userAgentString = webView.settings.userAgentString + " PinoyDinerPrintAgent/1.5.0"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(PrinterBridge(), "AndroidPrinter")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                callback("window.dispatchEvent(new Event('androidprinterready')); true;")
            }
        }

        // Avoid the old dashboard being held in WebView cache after AppDeploy updates.
        webView.clearCache(true)
        webView.loadUrl("${DASHBOARD_URL}?androidAgent=1&build=150")
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            callback("window.dispatchEvent(new Event('androidprinterready')); true;")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private fun savePrinter(host: String, port: Int): String {
        val cleanHost = host.trim()
        require(cleanHost.isNotBlank()) { "Enter the Epson printer IP address" }
        require(port in 1..65535) { "Invalid printer port" }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_IP, cleanHost)
            .putInt(KEY_PORT, port)
            .apply()
        return "$cleanHost:$port"
    }

    private fun printerIp(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_IP, "") ?: ""

    private fun printerPort(): Int =
        getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_PORT, 9100)

    private fun playOrderTone() {
        runOnUiThread {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).apply {
                startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
                android.os.Handler(mainLooper).postDelayed({ release() }, 500)
            }
        }
    }

    private fun testPrinter() {
        worker.execute {
            val result = EscPosPrinter.printTest(printerIp(), printerPort())
            callback(
                "window.onAndroidPrinterTestResult && window.onAndroidPrinterTestResult(" +
                    "${result.success}, ${JSONObject.quote(result.message)});"
            )
        }
    }

    private fun callback(js: String) =
        runOnUiThread { webView.evaluateJavascript(js, null) }

    inner class PrinterBridge {
        @JavascriptInterface
        fun getPrinterInfo(): String =
            if (printerIp().isBlank()) "Printer IP not configured"
            else "${printerIp()}:${printerPort()}"

        @JavascriptInterface
        fun getPrinterConfig(): String =
            JSONObject()
                .put("ip", printerIp())
                .put("port", printerPort())
                .toString()

        @JavascriptInterface
        fun setPrinterConfig(host: String, port: Int): String =
            try {
                savePrinter(host, port)
            } catch (e: Exception) {
                "ERROR: ${e.message ?: "Invalid printer settings"}"
            }

        @JavascriptInterface
        fun testPrinter() {
            this@MainActivity.testPrinter()
        }

        @JavascriptInterface
        fun printOrder(json: String) {
            val orderId = try {
                JSONObject(json).optString("id")
            } catch (_: Exception) {
                ""
            }

            if (orderId.isBlank()) {
                callback(
                    "window.onAndroidPrintResult && " +
                        "window.onAndroidPrintResult('', false, 'Missing order ID');"
                )
                return
            }

            playOrderTone()
            worker.execute {
                val result = EscPosPrinter.printOrder(printerIp(), printerPort(), json)
                callback(
                    "window.onAndroidPrintResult && window.onAndroidPrintResult(" +
                        "${JSONObject.quote(orderId)}, ${result.success}, ${JSONObject.quote(result.message)});"
                )
            }
        }
    }
}
