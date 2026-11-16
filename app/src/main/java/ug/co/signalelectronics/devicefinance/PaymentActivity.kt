package ug.co.signalelectronics.devicefinance

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

class PaymentActivity : AppCompatActivity() {
    companion object { private const val TAG = "SignalDPC.Payment" }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        val progress = findViewById<ProgressBar>(R.id.paymentProgress)
        val webView = findViewById<WebView>(R.id.paymentWebView)

        val imei = intent.getStringExtra("imei") ?: ""

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url?.startsWith("signaldpc://payment-return") == true) {
                    startActivity(Intent(this@PaymentActivity, MainActivity::class.java).apply {
                        data = Uri.parse(url)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                    finish()
                    return true
                }
                return false
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                shouldOverrideUrlLoading(view, request?.url?.toString())

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                Log.i(TAG, "Page loaded: $url")
            }
        }

        progress.visibility = View.VISIBLE
        Thread {
            val session = ApiClient.createPaymentSession(this@PaymentActivity)
            runOnUiThread {
                if (session.isNullOrBlank()) {
                    progress.visibility = View.GONE
                    android.widget.Toast.makeText(this@PaymentActivity, "Unable to open secure checkout. Please reconnect and try again.", android.widget.Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    val url = "${SignalApp.API_BASE}/pay?session=${Uri.encode(session)}"
                    Log.i(TAG, "Opening authenticated checkout session")
                    webView.loadUrl(url)
                }
            }
        }.start()
    }
}
