/** Pinoy Diner Epson Print Agent | v1.8.0 | 2026-08-30 */
package com.pinoydiner.printagent
import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
class MainActivity : Activity() {
 companion object { private const val DASHBOARD_URL="https://pinoy-diner-kitchen-print-manager-wkh2pi.v2.appdeploy.ai/";private const val PREFS="pinoy_diner_printer";private const val KEY_IP="printer_ip";private const val KEY_PORT="printer_port" }
 private lateinit var webView:WebView;private val worker=Executors.newSingleThreadExecutor();private val alarmHandler=Handler(Looper.getMainLooper());private var tts:TextToSpeech?=null;private var ttsReady=false;private var previousAlarmVolume:Int?=null;private var alertRunning=false;private var currentFingerprint="";private var expiredFingerprint="";private var currentSoundId="boss";private var currentAutoStopMinutes=10;private var autoStopRunnable:Runnable?=null
 private val alertCycle=object:Runnable{override fun run(){if(!alertRunning)return;playAlertCycle(currentSoundId);alarmHandler.postDelayed(this,9000)}}
 @SuppressLint("SetJavaScriptEnabled","AddJavascriptInterface") override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);initSpeech();webView=WebView(this);setContentView(webView);webView.settings.javaScriptEnabled=true;webView.settings.domStorageEnabled=true;webView.settings.databaseEnabled=true;webView.settings.cacheMode=WebSettings.LOAD_NO_CACHE;webView.settings.userAgentString=webView.settings.userAgentString+" PinoyDinerPrintAgent/1.8.0";CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(webView,true);webView.addJavascriptInterface(PrinterBridge(),"AndroidPrinter");webView.webViewClient=object:WebViewClient(){override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=false;override fun onPageFinished(view:WebView?,url:String?){super.onPageFinished(view,url);callback("window.dispatchEvent(new Event('androidprinterready')); true;")}};webView.clearCache(true);webView.loadUrl("${DASHBOARD_URL}?androidAgent=1&build=180")}
 private fun initSpeech(){tts=TextToSpeech(this){status->if(status==TextToSpeech.SUCCESS){ttsReady=true;val result=tts?.setLanguage(Locale("en","PH"))?:TextToSpeech.LANG_NOT_SUPPORTED;if(result==TextToSpeech.LANG_MISSING_DATA||result==TextToSpeech.LANG_NOT_SUPPORTED)tts?.language=Locale.US;tts?.setSpeechRate(1.05f);tts?.setPitch(1.0f);tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())}}}
 override fun onResume(){super.onResume();if(::webView.isInitialized){webView.onResume();callback("window.dispatchEvent(new Event('androidprinterready')); true;")}}
 @Deprecated("Deprecated in Java") override fun onBackPressed(){if(::webView.isInitialized&&webView.canGoBack())webView.goBack()else super.onBackPressed()}
 override fun onDestroy(){stopOrderAlert();tts?.stop();tts?.shutdown();worker.shutdownNow();if(::webView.isInitialized)webView.destroy();super.onDestroy()}
 private fun savePrinter(host:String,port:Int):String{val clean=host.trim();require(clean.isNotBlank()){"Enter the Epson printer IP address"};require(port in 1..65535){"Invalid printer port"};getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(KEY_IP,clean).putInt(KEY_PORT,port).apply();return "$clean:$port"}
 private fun printerIp()=getSharedPreferences(PREFS,MODE_PRIVATE).getString(KEY_IP,"")?:"";private fun printerPort()=getSharedPreferences(PREFS,MODE_PRIVATE).getInt(KEY_PORT,9100)
 private fun phraseFor(id:String)=when(id){"attention"->"Attention! New order incoming. Please check and acknowledge.";"madlang"->"Hey mga madlang people! May bagong order. Please check now!";"kusina"->"Kusina, gising! New Pinoy Diner order incoming. Please acknowledge!";"palampasin"->"Order alert! Huwag palampasin. New customer order waiting. Check now!";else->"Boss, may order! Please check the order now!"}
 private fun maximiseAlarmVolume(){val am=getSystemService(AUDIO_SERVICE)as AudioManager;if(previousAlarmVolume==null)previousAlarmVolume=am.getStreamVolume(AudioManager.STREAM_ALARM);am.setStreamVolume(AudioManager.STREAM_ALARM,am.getStreamMaxVolume(AudioManager.STREAM_ALARM),0)}
 private fun restoreAlarmVolume(){val old=previousAlarmVolume?:return;try{val am=getSystemService(AUDIO_SERVICE)as AudioManager;am.setStreamVolume(AudioManager.STREAM_ALARM,old,0)}catch(_:Exception){};previousAlarmVolume=null}
 private fun playAlertCycle(soundId:String){try{val tone=ToneGenerator(AudioManager.STREAM_ALARM,100);tone.startTone(ToneGenerator.TONE_PROP_BEEP2,450);alarmHandler.postDelayed({try{tone.startTone(ToneGenerator.TONE_PROP_BEEP2,450)}catch(_:Exception){}},600);alarmHandler.postDelayed({try{tone.release()}catch(_:Exception){}},1200);alarmHandler.postDelayed({if(ttsReady)tts?.speak(phraseFor(soundId),TextToSpeech.QUEUE_FLUSH,null,"pinoy-order-alert")},1300)}catch(_:Exception){if(ttsReady)tts?.speak(phraseFor(soundId),TextToSpeech.QUEUE_FLUSH,null,"pinoy-order-alert")}}
 private fun stopAlertInternal(clearState:Boolean){alertRunning=false;alarmHandler.removeCallbacks(alertCycle);autoStopRunnable?.let{alarmHandler.removeCallbacks(it)};autoStopRunnable=null;tts?.stop();restoreAlarmVolume();if(clearState){currentFingerprint="";expiredFingerprint=""}}
 private fun startOrderAlert(soundId:String,fingerprint:String,autoStopMinutes:Int){runOnUiThread{if(fingerprint.isBlank()){stopAlertInternal(true);return@runOnUiThread};if(fingerprint==expiredFingerprint)return@runOnUiThread;val same=fingerprint==currentFingerprint;if(alertRunning&&same&&soundId==currentSoundId&&autoStopMinutes==currentAutoStopMinutes)return@runOnUiThread;stopAlertInternal(false);if(!same)expiredFingerprint="";currentFingerprint=fingerprint;currentSoundId=soundId;currentAutoStopMinutes=autoStopMinutes;maximiseAlarmVolume();alertRunning=true;alertCycle.run();if(autoStopMinutes>0){val expected=fingerprint;val stopper=Runnable{if(alertRunning&&currentFingerprint==expected){expiredFingerprint=expected;stopAlertInternal(false)}};autoStopRunnable=stopper;alarmHandler.postDelayed(stopper,autoStopMinutes*60_000L)}}}
 private fun stopOrderAlert(){runOnUiThread{stopAlertInternal(true)}}
 private fun previewOrderAlert(soundId:String){runOnUiThread{val was=alertRunning;if(!was)maximiseAlarmVolume();playAlertCycle(soundId);if(!was)alarmHandler.postDelayed({if(!alertRunning){tts?.stop();restoreAlarmVolume()}},7000)}}
 private fun testPrinter(){worker.execute{val result=EscPosPrinter.printTest(printerIp(),printerPort());callback("window.onAndroidPrinterTestResult && window.onAndroidPrinterTestResult(${result.success}, ${JSONObject.quote(result.message)});")}}
 private fun callback(js:String)=runOnUiThread{webView.evaluateJavascript(js,null)}
 inner class PrinterBridge{
  @JavascriptInterface fun getPrinterInfo()=if(printerIp().isBlank())"Printer IP not configured" else "${printerIp()}:${printerPort()}"
  @JavascriptInterface fun getPrinterConfig()=JSONObject().put("ip",printerIp()).put("port",printerPort()).toString()
  @JavascriptInterface fun setPrinterConfig(host:String,port:Int)=try{savePrinter(host,port)}catch(e:Exception){"ERROR: ${e.message?:"Invalid printer settings"}"}
  @JavascriptInterface fun startOrderAlert(soundId:String,fingerprint:String,autoStopMinutes:Int){this@MainActivity.startOrderAlert(soundId,fingerprint,autoStopMinutes)}
  @JavascriptInterface fun stopOrderAlert(){this@MainActivity.stopOrderAlert()}
  @JavascriptInterface fun previewOrderAlert(soundId:String){this@MainActivity.previewOrderAlert(soundId)}
  @JavascriptInterface fun testPrinter(){this@MainActivity.testPrinter()}
  @JavascriptInterface fun printOrder(json:String){val orderId=try{JSONObject(json).optString("id")}catch(_:Exception){""};if(orderId.isBlank()){callback("window.onAndroidPrintResult && window.onAndroidPrintResult('', false, 'Missing order ID');");return};worker.execute{val result=EscPosPrinter.printOrder(printerIp(),printerPort(),json);callback("window.onAndroidPrintResult && window.onAndroidPrintResult(${JSONObject.quote(orderId)}, ${result.success}, ${JSONObject.quote(result.message)});")}}
 }
}
