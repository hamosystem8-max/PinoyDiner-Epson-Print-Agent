/** Pinoy Diner Epson Print Agent | v1.6.0 | 2026-08-30 */
package com.pinoydiner.printagent

import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
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
    private var alertPlayer: MediaPlayer? = null
    private var previousAlarmVolume: Int? = null

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.settings.userAgentString = webView.settings.userAgentString + " PinoyDinerPrintAgent/1.6.0"
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
        webView.clearCache(true)
        webView.loadUrl("${DASHBOARD_URL}?androidAgent=1&build=160")
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
        stopOrderAlert()
        worker.shutdownNow()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private fun savePrinter(host: String, port: Int): String {
        val cleanHost = host.trim()
        require(cleanHost.isNotBlank()) { "Enter the Epson printer IP address" }
        require(port in 1..65535) { "Invalid printer port" }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_IP, cleanHost).putInt(KEY_PORT, port).apply()
        return "$cleanHost:$port"
    }

    private fun printerIp(): String = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_IP, "") ?: ""
    private fun printerPort(): Int = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_PORT, 9100)

    private fun startOrderAlert() {
        runOnUiThread {
            if (alertPlayer?.isPlaying == true) return@runOnUiThread
            try {
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                if (previousAlarmVolume == null) previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: return@runOnUiThread
                val player = MediaPlayer()
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                player.setDataSource(this@MainActivity, uri)
                player.isLooping = true
                player.setVolume(1f, 1f)
                player.prepare()
                player.start()
                alertPlayer = player
            } catch (_: Exception) {
                alertPlayer?.release()
                alertPlayer = null
            }
        }
    }

    private fun stopOrderAlert() {
        runOnUiThread {
            try { alertPlayer?.stop() } catch (_: Exception) {}
            alertPlayer?.release()
            alertPlayer = null
            previousAlarmVolume?.let { oldVolume ->
                try {
                    val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0)
                } catch (_: Exception) {}
            }
            previousAlarmVolume = null
        }
    }

    private fun testPrinter() {
        worker.execute {
            val result = EscPosPrinter.printTest(printerIp(), printerPort())
            callback("window.onAndroidPrinterTestResult && window.onAndroidPrinterTestResult(${result.success}, ${JSONObject.quote(result.message)});")
        }
    }

    private fun callback(js: String) = runOnUiThread { webView.evaluateJavascript(js, null) }

    inner class PrinterBridge {
        @JavascriptInterface
        fun getPrinterInfo(): String = if (printerIp().isBlank()) "Printer IP not configured" else "${printerIp()}:${printerPort()}"

        @JavascriptInterface
        fun getPrinterConfig(): String = JSONObject().put("ip", printerIp()).put("port", printerPort()).toString()

        @JavascriptInterface
        fun setPrinterConfig(host: String, port: Int): String = try { savePrinter(host, port) } catch (e: Exception) { "ERROR: ${e.message ?: "Invalid printer settings"}" }

        @JavascriptInterface
        fun startOrderAlert() { this@MainActivity.startOrderAlert() }

        @JavascriptInterface
        fun stopOrderAlert() { this@MainActivity.stopOrderAlert() }

        @JavascriptInterface
        fun testPrinter() { this@MainActivity.testPrinter() }

        @JavascriptInterface
        fun printOrder(json: String) {
            val orderId = try { JSONObject(json).optString("id") } catch (_: Exception) { "" }
            if (orderId.isBlank()) {
                callback("window.onAndroidPrintResult && window.onAndroidPrintResult('', false, 'Missing order ID');")
                return
            }
            worker.execute {
                val result = EscPosPrinter.printOrder(printerIp(), printerPort(), json)
                callback("window.onAndroidPrintResult && window.onAndroidPrintResult(${JSONObject.quote(orderId)}, ${result.success}, ${JSONObject.quote(result.message)});")
            }
        }
    }
}
