package hr.jutarnji.pdfkreator

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

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

            addJavascriptInterface(AndroidBridge(this@MainActivity, this), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray) {
            out.write(b)
            count += b.size
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    private data class JpegMeta(val width: Int, val height: Int, val colorSpace: String)

    class AndroidBridge(
        private val activity: Activity,
        private val webView: WebView
    ) {
        @Volatile
        private var isRunning = false

        @Volatile
        private var stopRequested = false

        private fun callJs(script: String) {
            webView.post {
                webView.evaluateJavascript(script, null)
            }
        }

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
        fun stopDownload() {
            stopRequested = true
        }

        @JavascriptInterface
        fun startDownload(issueId: String) {
            if (isRunning) return
            isRunning = true
            stopRequested = false

            Thread {
                val fileName = "Jutarnji_$issueId.pdf"
                var pendingUri: Uri? = null
                var legacyFile: File? = null
                var rawOut: OutputStream? = null
                var countingOut: CountingOutputStream? = null
                var pageCount = 0
                var totalImageBytes = 0L

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val resolver = activity.contentResolver
                        pendingUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw Exception("Nije moguce stvoriti datoteku u Preuzimanjima.")
                        rawOut = resolver.openOutputStream(pendingUri)
                            ?: throw Exception("Nije moguce otvoriti izlazni tok.")
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!dir.exists()) dir.mkdirs()
                        legacyFile = File(dir, fileName)
                        rawOut = FileOutputStream(legacyFile)
                    }

                    countingOut = CountingOutputStream(BufferedOutputStream(rawOut, 65536))
                    val offsets = LinkedHashMap<Int, Long>()

                    fun writeAscii(s: String) {
                        countingOut!!.write(s.toByteArray(Charsets.US_ASCII))
                    }

                    writeAscii("%PDF-1.4\n")

                    var page = 1
                    while (!stopRequested && page <= 250) {
                        callJs("window.onNativeProgress($page, $totalImageBytes)")

                        val urlStr =
                            "https://online.jutarnji.hr/storageNotCache/pdf/issues/$issueId/page-$page.jpg"
                        val jpgBytes = downloadBytes(urlStr) ?: break

                        val meta = parseJpegMeta(jpgBytes)
                        val imgObjId = 3 * page
                        val contentObjId = 3 * page + 1
                        val pageObjId = 3 * page + 2

                        val decodePart =
                            if (meta.colorSpace == "/DeviceCMYK") " /Decode [1 0 1 0 1 0 1 0]" else ""

                        offsets[imgObjId] = countingOut.count
                        writeAscii(
                            "$imgObjId 0 obj\n" +
                                "<< /Type /XObject /Subtype /Image /Width ${meta.width} /Height ${meta.height} " +
                                "/ColorSpace ${meta.colorSpace}$decodePart /BitsPerComponent 8 " +
                                "/Filter /DCTDecode /Length ${jpgBytes.size} >>\n" +
                                "stream\n"
                        )
                        countingOut.write(jpgBytes)
                        writeAscii("\nendstream\nendobj\n")

                        val streamData = "q ${meta.width} 0 0 ${meta.height} 0 0 cm /Im1 Do Q\n"
                            .toByteArray(Charsets.US_ASCII)
                        offsets[contentObjId] = countingOut.count
                        writeAscii("$contentObjId 0 obj\n<< /Length ${streamData.size} >>\nstream\n")
                        countingOut.write(streamData)
                        writeAscii("endstream\nendobj\n")

                        offsets[pageObjId] = countingOut.count
                        writeAscii(
                            "$pageObjId 0 obj\n" +
                                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${meta.width} ${meta.height}] " +
                                "/Resources << /XObject << /Im1 $imgObjId 0 R >> >> /Contents $contentObjId 0 R >>\n" +
                                "endobj\n"
                        )

                        pageCount++
                        totalImageBytes += jpgBytes.size

                        val thumbB64 = makeThumbBase64(jpgBytes)
                        callJs(
                            "window.onNativePageAdded($pageCount, $totalImageBytes, ${JSONObject.quote(thumbB64)})"
                        )

                        page++
                    }

                    if (pageCount == 0) {
                        countingOut.close()
                        cleanupEmpty(pendingUri, legacyFile)
                        callJs(
                            "window.onNativeError(${JSONObject.quote("Nije pronadjena nijedna stranica za izdanje $issueId.")})"
                        )
                        return@Thread
                    }

                    offsets[1] = countingOut.count
                    writeAscii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

                    val kids = (1..pageCount).joinToString(" ") { "${3 * it + 2} 0 R" }
                    offsets[2] = countingOut.count
                    writeAscii("2 0 obj\n<< /Type /Pages /Kids [ $kids ] /Count $pageCount >>\nendobj\n")

                    val maxObj = 3 * pageCount + 2
                    val xrefOffset = countingOut.count
                    writeAscii("xref\n0 ${maxObj + 1}\n")
                    writeAscii("0000000000 65535 f \n")
                    for (id in 1..maxObj) {
                        val off = offsets[id] ?: 0L
                        writeAscii(String.format(Locale.US, "%010d 00000 n \n", off))
                    }

                    writeAscii(
                        "trailer\n" +
                            "<< /Size ${maxObj + 1} /Root 1 0 R >>\n" +
                            "startxref\n" +
                            "$xrefOffset\n" +
                            "%%EOF\n"
                    )

                    countingOut.flush()
                    countingOut.close()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pendingUri != null) {
                        val doneValues = ContentValues().apply {
                            put(MediaStore.Downloads.IS_PENDING, 0)
                        }
                        activity.contentResolver.update(pendingUri, doneValues, null, null)
                    }

                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            "Spremljeno u Preuzimanja: $fileName",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    callJs("window.onNativeFinished($pageCount, ${JSONObject.quote(fileName)})")
                } catch (e: Throwable) {
                    try {
                        countingOut?.close()
                    } catch (_: Throwable) {
                    }
                    cleanupEmpty(pendingUri, legacyFile)
                    val msg = e.message ?: e.javaClass.simpleName
                    callJs("window.onNativeError(${JSONObject.quote("Greska: $msg")})")
                } finally {
                    isRunning = false
                }
            }.start()
        }

        private fun cleanupEmpty(pendingUri: Uri?, legacyFile: File?) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pendingUri != null) {
                    activity.contentResolver.delete(pendingUri, null, null)
                } else {
                    legacyFile?.delete()
                }
            } catch (_: Throwable) {
            }
        }

        private fun downloadBytes(urlStr: String): ByteArray? {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(urlStr)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 25000
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"
                    )
                }
                val code = conn.responseCode
                if (code == 404) return null
                if (code !in 200..299) {
                    throw Exception("HTTP greska $code")
                }
                val input = conn.inputStream
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16384)
                var n: Int
                while (input.read(chunk).also { n = it } != -1) {
                    buffer.write(chunk, 0, n)
                }
                input.close()
                return buffer.toByteArray()
            } finally {
                conn?.disconnect()
            }
        }

        private fun parseJpegMeta(bytes: ByteArray): JpegMeta {
            var i = 0
            while (i + 1 < bytes.size) {
                if (bytes[i] != 0xFF.toByte()) {
                    i++
                    continue
                }
                while (i < bytes.size && bytes[i] == 0xFF.toByte()) {
                    i++
                }
                if (i >= bytes.size) break
                val marker = bytes[i].toInt() and 0xFF
                i++
                if (marker == 0xD8 || marker == 0xD9 || marker == 0x01 || marker in 0xD0..0xD7) {
                    continue
                }
                if (i + 1 >= bytes.size) break
                val segLen = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                    if (i + 7 < bytes.size) {
                        val h = ((bytes[i + 3].toInt() and 0xFF) shl 8) or (bytes[i + 4].toInt() and 0xFF)
                        val w = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                        val comps = bytes[i + 7].toInt() and 0xFF
                        val cs = when (comps) {
                            1 -> "/DeviceGray"
                            4 -> "/DeviceCMYK"
                            else -> "/DeviceRGB"
                        }
                        if (w > 0 && h > 0) return JpegMeta(w, h, cs)
                    }
                }
                if (segLen < 2) break
                i += segLen
            }

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            return JpegMeta(
                if (opts.outWidth > 0) opts.outWidth else 1240,
                if (opts.outHeight > 0) opts.outHeight else 1754,
                "/DeviceRGB"
            )
        }

        private fun makeThumbBase64(bytes: ByteArray): String {
            return try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return ""
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
                bmp.recycle()
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } catch (_: Throwable) {
                ""
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
                    Toast.makeText(
                        activity,
                        "Otvori mapu Preuzimanja u upravitelju datoteka.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
