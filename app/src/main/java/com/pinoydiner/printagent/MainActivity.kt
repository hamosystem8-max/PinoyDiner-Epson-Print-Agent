/** Pinoy Diner Epson Print Agent | v1.2.0 | 2026-08-24 */
package com.pinoydiner.printagent

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var statusText: TextView
    private val worker = Executors.newSingleThreadExecutor()

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "Pinoy Diner · Epson Print Agent"
            textSize = 18f
            setTextColor(Color.rgb(32, 40, 55))
            setPadding(20, 18, 20, 8)
        }
        root.addView(title)

        val settingsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 12, 8)
        }
        ipInput = EditText(this).apply {
            hint = "Printer IP"
            setText(prefs.getString(KEY_IP, "192.168.1.100"))
            setSingleLine(true)
        }
        portInput = EditText(this).apply {
            hint = "Port"
            setText(prefs.getInt(KEY_PORT, 9100).toString())
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val testButton = Button(this).apply { text = "SAVE + TEST" }
        settingsRow.addView(ipInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        settingsRow.addView(portInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        settingsRow.addView(testButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(settingsRow)

        statusText = TextView(this).apply {
            text = "Enter the Epson IP, then tap SAVE + TEST."
            setTextColor(Color.DKGRAY)
            setPadding(20, 0, 20, 10)
        }
        root.addView(statusText)

        webView = WebView(this)
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.addJavascriptInterface(PrinterBridge(), "AndroidPrinter")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                val dialog = Dialog(this@MainActivity)
                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url?.contains("appdeploy.ai") == true && url.contains("callback", ignoreCase = true)) dialog.dismiss()
                    }
                }
                popup.webChromeClient = WebChromeClient()
                dialog.setContentView(popup)
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                dialog.show()
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        testButton.setOnClickListener {
            savePrinterSettings()
            testPrinter()
        }

        webView.loadUrl(DASHBOARD_URL)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        webView.destroy()
        super.onDestroy()
    }

    private fun savePrinterSettings() {
        val port = portInput.text.toString().toIntOrNull() ?: 9100
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_IP, ipInput.text.toString().trim())
            .putInt(KEY_PORT, port)
            .apply()
    }

    private fun printerIp(): String = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_IP, "") ?: ""
    private fun printerPort(): Int = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_PORT, 9100)

    private fun playOrderTone() {
        runOnUiThread {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).apply {
                startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
                android.os.Handler(mainLooper).postDelayed({ release() }, 500)
            }
        }
    }

    private fun testPrinter() {
        status("Testing ${printerIp()}:${printerPort()}…")
        worker.execute {
            val result = EscPosPrinter.printTest(printerIp(), printerPort())
            status(if (result.success) "✓ ${result.message}" else "✕ ${result.message}")
            callback("window.onAndroidPrinterTestResult && window.onAndroidPrinterTestResult(${result.success}, ${JSONObject.quote(result.message)});")
        }
    }

    private fun status(message: String) = runOnUiThread { statusText.text = message }

    private fun callback(js: String) = runOnUiThread { webView.evaluateJavascript(js, null) }

    inner class PrinterBridge {
        @JavascriptInterface
        fun getPrinterInfo(): String = "${printerIp()}:${printerPort()}"

        @JavascriptInterface
        fun testPrinter() {
            this@MainActivity.testPrinter()
        }

        @JavascriptInterface
        fun printOrder(json: String) {
            val orderId = try { JSONObject(json).optString("id") } catch (_: Exception) { "" }
            if (orderId.isBlank()) {
                callback("window.onAndroidPrintResult && window.onAndroidPrintResult('', false, 'Missing order ID');")
                return
            }
            playOrderTone()
            status("Printing order $orderId…")
            worker.execute {
                val result = EscPosPrinter.printOrder(printerIp(), printerPort(), json)
                status(if (result.success) "✓ ${result.message}" else "✕ ${result.message}")
                callback("window.onAndroidPrintResult && window.onAndroidPrintResult(${JSONObject.quote(orderId)}, ${result.success}, ${JSONObject.quote(result.message)});")
            }
        }
    }
}
