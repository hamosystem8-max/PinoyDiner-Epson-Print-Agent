/** Pinoy Diner Epson Print Agent | v1.9.3 | 2026-09-16 */
package com.pinoydiner.printagent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val DASHBOARD_URL =
            "https://pinoy-diner-kitchen-print-manager-wkh2pi.v2.appdeploy.ai/"
        private const val PREFS = "pinoy_diner_printer"
        private const val KEY_IP = "printer_ip"
        private const val KEY_PORT = "printer_port"
        private const val KEY_RINGTONE_URI = "alarm_ringtone_uri"
        private const val KEY_RINGTONE_NAME = "alarm_ringtone_name"
        private const val REQUEST_RINGTONE = 401
        private const val REQUEST_AUDIO_FILE = 402
    }

    private lateinit var webView: WebView
    private val worker = Executors.newSingleThreadExecutor()
    private val alarmHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var nativeAlarmPlayer: MediaPlayer? = null
    private var previousAlarmVolume: Int? = null
    private var alertRunning = false
    private var currentFingerprint = ""
    private var expiredFingerprint = ""
    private var currentSoundId = "boss"
    private var currentAutoStopMinutes = 10
    private var autoStopRunnable: Runnable? = null

    private val voiceCycle = object : Runnable {
        override fun run() {
            if (!alertRunning) return
            speakVoice(currentSoundId)
            alarmHandler.postDelayed(this, 12000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initSpeech()

        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.settings.userAgentString =
            webView.settings.userAgentString + " PinoyDinerPrintAgent/1.9.3"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(PrinterBridge(), "AndroidPrinter")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ) = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                callback("window.dispatchEvent(new Event('androidprinterready')); true;")
            }
        }

        webView.clearCache(true)
        webView.loadUrl("${DASHBOARD_URL}?androidAgent=1&build=193")
    }

    private fun prefs() =
        getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun initSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val result =
                    tts?.setLanguage(Locale("en", "PH"))
                        ?: TextToSpeech.LANG_NOT_SUPPORTED

                if (
                    result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.language = Locale.US
                }

                tts?.setSpeechRate(1.05f)
                tts?.setPitch(1.0f)
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
        }
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
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopOrderAlert()
        tts?.stop()
        tts?.shutdown()
        worker.shutdownNow()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    private fun savePrinter(host: String, port: Int): String {
        val clean = host.trim()
        require(clean.isNotBlank()) { "Enter the Epson printer IP address" }
        require(port in 1..65535) { "Invalid printer port" }

        prefs()
            .edit()
            .putString(KEY_IP, clean)
            .putInt(KEY_PORT, port)
            .apply()

        return "$clean:$port"
    }

    private fun printerIp() =
        prefs().getString(KEY_IP, "") ?: ""

    private fun printerPort() =
        prefs().getInt(KEY_PORT, 9100)

    private fun phraseFor(id: String) =
        when (id) {
            "attention" ->
                "Attention! New order incoming. Please check and acknowledge."
            "madlang" ->
                "Hey mga madlang people! May bagong order. Please check now!"
            "kusina" ->
                "Kusina, gising! New Pinoy Diner order incoming. Please acknowledge!"
            "palampasin" ->
                "Order alert! Huwag palampasin. New customer order waiting. Check now!"
            else ->
                "Boss, may order! Please check the order now!"
        }

    private fun maximiseAlarmVolume() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (previousAlarmVolume == null) {
            previousAlarmVolume =
                audio.getStreamVolume(AudioManager.STREAM_ALARM)
        }

        audio.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0
        )
    }

    private fun restoreAlarmVolume() {
        val previous = previousAlarmVolume ?: return

        try {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                previous,
                0
            )
        } catch (_: Exception) {
        }

        previousAlarmVolume = null
    }

    private fun defaultAlarmUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun selectedAlarmUri(): Uri? {
        val saved = prefs().getString(KEY_RINGTONE_URI, "") ?: ""

        return if (saved.isBlank()) {
            defaultAlarmUri()
        } else {
            Uri.parse(saved)
        }
    }

    private fun resolveRingtoneTitle(uri: Uri?): String {
        if (uri == null) return "System default alarm"

        return try {
            RingtoneManager
                .getRingtone(this, uri)
                ?.getTitle(this)
                ?.takeIf { it.isNotBlank() }
                ?: "Selected ringtone"
        } catch (_: Exception) {
            "Selected ringtone"
        }
    }

    private fun audioFileName(uri: Uri): String {
        var name = ""

        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index =
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                    if (index >= 0) {
                        name = cursor.getString(index) ?: ""
                    }
                }
            }
        } catch (_: Exception) {
        }

        return name.ifBlank {
            uri.lastPathSegment ?: "Selected audio file"
        }
    }

    private fun alarmSelectionName(): String {
        val savedName =
            prefs().getString(KEY_RINGTONE_NAME, "") ?: ""

        if (savedName.isNotBlank()) {
            return savedName
        }

        return resolveRingtoneTitle(defaultAlarmUri())
    }

    private fun alarmSelectionJson(): String {
        val savedUri =
            prefs().getString(KEY_RINGTONE_URI, "") ?: ""

        return JSONObject()
            .put("name", alarmSelectionName())
            .put(
                "uri",
                if (savedUri.isBlank()) {
                    defaultAlarmUri()?.toString() ?: ""
                } else {
                    savedUri
                }
            )
            .put("local", true)
            .toString()
    }

    private fun saveAlarmSelection(uri: Uri, name: String) {
        prefs()
            .edit()
            .putString(KEY_RINGTONE_URI, uri.toString())
            .putString(KEY_RINGTONE_NAME, name)
            .apply()
    }

    private fun resetAlarmSelection(): String {
        prefs()
            .edit()
            .remove(KEY_RINGTONE_URI)
            .remove(KEY_RINGTONE_NAME)
            .apply()

        return alarmSelectionJson()
    }

    private fun chooseDeviceRingtone() {
        runOnUiThread {
            val intent =
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(
                        RingtoneManager.EXTRA_RINGTONE_TYPE,
                        RingtoneManager.TYPE_ALARM or
                            RingtoneManager.TYPE_RINGTONE or
                            RingtoneManager.TYPE_NOTIFICATION
                    )
                    putExtra(
                        RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT,
                        true
                    )
                    putExtra(
                        RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,
                        false
                    )
                    putExtra(
                        RingtoneManager.EXTRA_RINGTONE_TITLE,
                        "Choose Pinoy Diner Order Alarm"
                    )
                    putExtra(
                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        selectedAlarmUri()
                    )
                }

            startActivityForResult(intent, REQUEST_RINGTONE)
        }
    }

    private fun chooseAudioFile() {
        runOnUiThread {
            val intent =
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }

            startActivityForResult(intent, REQUEST_AUDIO_FILE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK) return

        when (requestCode) {
            REQUEST_RINGTONE -> {
                @Suppress("DEPRECATION")
                val uri =
                    data?.getParcelableExtra<Uri>(
                        RingtoneManager.EXTRA_RINGTONE_PICKED_URI
                    )

                if (uri != null) {
                    val name = resolveRingtoneTitle(uri)
                    saveAlarmSelection(uri, name)
                    notifyAlarmSelection(name, uri)
                }
            }

            REQUEST_AUDIO_FILE -> {
                val uri = data?.data ?: return

                try {
                    val takeFlags =
                        data.flags and
                            Intent.FLAG_GRANT_READ_URI_PERMISSION

                    contentResolver.takePersistableUriPermission(
                        uri,
                        takeFlags
                    )
                } catch (_: Exception) {
                }

                val name = audioFileName(uri)
                saveAlarmSelection(uri, name)
                notifyAlarmSelection(name, uri)
            }
        }
    }

    private fun notifyAlarmSelection(name: String, uri: Uri) {
        callback(
            "window.onAndroidRingtoneSelected && " +
                "window.onAndroidRingtoneSelected(" +
                "${JSONObject.quote(name)}, " +
                "${JSONObject.quote(uri.toString())});"
        )
    }

    private fun startNativeAlarm() {
        stopNativeAlarm()

        val uri = selectedAlarmUri() ?: return

        try {
            nativeAlarmPlayer =
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_SONIFICATION
                            )
                            .build()
                    )
                    setDataSource(this@MainActivity, uri)
                    isLooping = true
                    setVolume(1f, 1f)
                    prepare()
                    start()
                }
        } catch (_: Exception) {
            stopNativeAlarm()

            val fallback = defaultAlarmUri()

            if (
                fallback != null &&
                fallback != uri
            ) {
                try {
                    nativeAlarmPlayer =
                        MediaPlayer().apply {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ALARM)
                                    .setContentType(
                                        AudioAttributes.CONTENT_TYPE_SONIFICATION
                                    )
                                    .build()
                            )
                            setDataSource(this@MainActivity, fallback)
                            isLooping = true
                            setVolume(1f, 1f)
                            prepare()
                            start()
                        }
                } catch (_: Exception) {
                    stopNativeAlarm()
                }
            }
        }
    }

    private fun stopNativeAlarm() {
        try {
            nativeAlarmPlayer?.stop()
        } catch (_: Exception) {
        }

        try {
            nativeAlarmPlayer?.release()
        } catch (_: Exception) {
        }

        nativeAlarmPlayer = null
    }

    private fun speakVoice(soundId: String) {
        if (!ttsReady) return

        tts?.speak(
            phraseFor(soundId),
            TextToSpeech.QUEUE_FLUSH,
            null,
            "pinoy-order-alert"
        )
    }

    private fun stopAlertInternal(clearState: Boolean) {
        alertRunning = false
        alarmHandler.removeCallbacks(voiceCycle)

        autoStopRunnable?.let {
            alarmHandler.removeCallbacks(it)
        }

        autoStopRunnable = null
        stopNativeAlarm()
        tts?.stop()
        restoreAlarmVolume()

        if (clearState) {
            currentFingerprint = ""
            expiredFingerprint = ""
        }
    }

    private fun startOrderAlert(
        soundId: String,
        fingerprint: String,
        autoStopMinutes: Int
    ) {
        runOnUiThread {
            if (fingerprint.isBlank()) {
                stopAlertInternal(true)
                return@runOnUiThread
            }

            if (fingerprint == expiredFingerprint) {
                return@runOnUiThread
            }

            val same = fingerprint == currentFingerprint

            if (
                alertRunning &&
                same &&
                soundId == currentSoundId &&
                autoStopMinutes == currentAutoStopMinutes
            ) {
                return@runOnUiThread
            }

            stopAlertInternal(false)

            if (!same) {
                expiredFingerprint = ""
            }

            currentFingerprint = fingerprint
            currentSoundId = soundId
            currentAutoStopMinutes = autoStopMinutes

            maximiseAlarmVolume()
            startNativeAlarm()
            alertRunning = true

            alarmHandler.postDelayed(
                {
                    if (alertRunning) {
                        speakVoice(currentSoundId)
                        alarmHandler.postDelayed(
                            voiceCycle,
                            12000
                        )
                    }
                },
                1200
            )

            if (autoStopMinutes > 0) {
                val expected = fingerprint

                val stopper =
                    Runnable {
                        if (
                            alertRunning &&
                            currentFingerprint == expected
                        ) {
                            expiredFingerprint = expected
                            stopAlertInternal(false)
                        }
                    }

                autoStopRunnable = stopper

                alarmHandler.postDelayed(
                    stopper,
                    autoStopMinutes * 60_000L
                )
            }
        }
    }

    private fun stopOrderAlert() {
        runOnUiThread {
            stopAlertInternal(true)
        }
    }

    private fun previewOrderAlert(soundId: String) {
        runOnUiThread {
            if (alertRunning) {
                speakVoice(soundId)
                return@runOnUiThread
            }

            maximiseAlarmVolume()
            startNativeAlarm()

            alarmHandler.postDelayed(
                { speakVoice(soundId) },
                1000
            )

            alarmHandler.postDelayed(
                {
                    if (!alertRunning) {
                        stopNativeAlarm()
                        tts?.stop()
                        restoreAlarmVolume()
                    }
                },
                8000
            )
        }
    }

    private fun checkPrinter() {
        val host = printerIp()
        val port = printerPort()

        if (host.isBlank()) {
            callback(
                "window.onAndroidPrinterCheckResult && " +
                    "window.onAndroidPrinterCheckResult(" +
                    "false, 'Printer IP not configured');"
            )
            return
        }

        worker.execute {
            var success = false
            var message = ""

            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(host, port),
                        1500
                    )
                    success = socket.isConnected
                }

                message =
                    if (success) {
                        "$host:$port is reachable"
                    } else {
                        "$host:$port did not respond"
                    }
            } catch (e: Exception) {
                message =
                    e.message ?: "Could not reach $host:$port"
            }

            callback(
                "window.onAndroidPrinterCheckResult && " +
                    "window.onAndroidPrinterCheckResult(" +
                    "$success, " +
                    "${JSONObject.quote(message)});"
            )
        }
    }

    private fun testPrinter() {
        worker.execute {
            val result =
                EscPosPrinter.printTest(
                    printerIp(),
                    printerPort()
                )

            callback(
                "window.onAndroidPrinterTestResult && " +
                    "window.onAndroidPrinterTestResult(" +
                    "${result.success}, " +
                    "${JSONObject.quote(result.message)});"
            )
        }
    }

    private fun callback(js: String) =
        runOnUiThread {
            webView.evaluateJavascript(js, null)
        }

    inner class PrinterBridge {
        @JavascriptInterface
        fun getPrinterInfo() =
            if (printerIp().isBlank()) {
                "Printer IP not configured on this tablet"
            } else {
                "${printerIp()}:${printerPort()} (local)"
            }

        @JavascriptInterface
        fun getPrinterConfig() =
            JSONObject()
                .put("ip", printerIp())
                .put("port", printerPort())
                .put("local", true)
                .toString()

        @JavascriptInterface
        fun setPrinterConfig(host: String, port: Int) =
            try {
                savePrinter(host, port)
            } catch (e: Exception) {
                "ERROR: ${e.message ?: "Invalid printer settings"}"
            }

        @JavascriptInterface
        fun checkPrinter() {
            this@MainActivity.checkPrinter()
        }

        @JavascriptInterface
        fun getAlarmSelection() =
            alarmSelectionJson()

        @JavascriptInterface
        fun chooseDeviceRingtone() {
            this@MainActivity.chooseDeviceRingtone()
        }

        @JavascriptInterface
        fun chooseAudioFile() {
            this@MainActivity.chooseAudioFile()
        }

        @JavascriptInterface
        fun resetAlarmSelection() =
            this@MainActivity.resetAlarmSelection()

        @JavascriptInterface
        fun startOrderAlert(
            soundId: String,
            fingerprint: String,
            autoStopMinutes: Int
        ) {
            this@MainActivity.startOrderAlert(
                soundId,
                fingerprint,
                autoStopMinutes
            )
        }

        @JavascriptInterface
        fun stopOrderAlert() {
            this@MainActivity.stopOrderAlert()
        }

        @JavascriptInterface
        fun previewOrderAlert(soundId: String) {
            this@MainActivity.previewOrderAlert(soundId)
        }

        @JavascriptInterface
        fun testPrinter() {
            this@MainActivity.testPrinter()
        }

        @JavascriptInterface
        fun printOrder(json: String) {
            val orderId =
                try {
                    JSONObject(json).optString("id")
                } catch (_: Exception) {
                    ""
                }

            if (orderId.isBlank()) {
                callback(
                    "window.onAndroidPrintResult && " +
                        "window.onAndroidPrintResult('', false, " +
                        "'Missing order ID');"
                )
                return
            }

            worker.execute {
                val result =
                    EscPosPrinter.printOrder(
                        printerIp(),
                        printerPort(),
                        json
                    )

                callback(
                    "window.onAndroidPrintResult && " +
                        "window.onAndroidPrintResult(" +
                        "${JSONObject.quote(orderId)}, " +
                        "${result.success}, " +
                        "${JSONObject.quote(result.message)});"
                )
            }
        }
    }
}
