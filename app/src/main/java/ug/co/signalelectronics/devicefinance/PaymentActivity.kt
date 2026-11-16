package ug.co.signalelectronics.devicefinance

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class PaymentActivity : AppCompatActivity() {
    companion object { private const val TAG = "SignalDPC.Payment" }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        val progress = findViewById<ProgressBar>(R.id.paymentProgress)
        val webView = findViewById<WebView>(R.id.paymentWebView)
        val imei = intent.getStringExtra("imei")?.trim().orEmpty()
        if (imei.isBlank()) {
            Toast.makeText(this, "Device identity is not ready. Reopen the app.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onConfirmed() {
                runOnUiThread { UpdateChecker.checkAndUpdate(applicationContext) }
            }
        }, "SignalDpcPayment")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return uri.scheme != "https" || uri.host != "device-finance-api.whiteghostpheonix.workers.dev"
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                Log.i(TAG, "Payment page loaded on approved Finance API host")
            }
        }

        progress.visibility = View.VISIBLE
        ApiClient.createPaymentSession(this, imei) { paymentUrl ->
            runOnUiThread {
                progress.visibility = View.GONE
                if (paymentUrl == null) {
                    Toast.makeText(this, "Could not securely open payment. Check internet and try again.", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    webView.loadUrl(paymentUrl)
                }
            }
        }
    }
}
