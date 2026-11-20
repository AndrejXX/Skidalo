package hr.jutarnji.pdfkreator

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    }
                    return false
                }
            }

            addJavascriptInterface(AndroidBridge(this@MainActivity), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    class AndroidBridge(private val activity: Activity) {

        @JavascriptInterface
        fun readClipboard(): String {
            return try {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(activity)?.toString() ?: ""
                } else {
                    ""
                }
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun fetchImageBase64(urlStr: String): String {
            var conn: HttpURLConnection? = null
            return try {
                val url = URL(urlStr)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"
                    )
                }
                val code = conn.responseCode
                if (code == 404) return "ERR_404"
                if (code !in 200..299) return "ERR_HTTP_$code"

                val input = conn.inputStream
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(16384)
                var nRead: Int
                while (input.read(data, 0, data.size).also { nRead = it } != -1) {
                    buffer.write(data, 0, nRead)
                }
                input.close()
                Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                "ERR_NET:${e.message}"
            } finally {
                conn?.disconnect()
            }
        }

        @JavascriptInterface
        fun savePdfBase64(base64Data: String, fileName: String): String {
            return try {
                val pdfBytes = Base64.decode(base64Data, Base64.DEFAULT)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = activity.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return "ERR_URI"

                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(pdfBytes)
                    }

                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, fileName)
                    FileOutputStream(file).use { out ->
                        out.write(pdfBytes)
                    }
                }

                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "Spremljeno u Preuzimanja: $fileName",
                        Toast.LENGTH_LONG
                    ).show()
                }
                "OK"
            } catch (e: Exception) {
                "ERR_SAVE:${e.message}"
            }
        }

        @JavascriptInterface
        fun openDownloads() {
            try {
                val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Otvori mapu Preuzimanja u upravitelju datoteka.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
