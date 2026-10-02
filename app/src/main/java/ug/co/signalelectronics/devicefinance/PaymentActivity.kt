package ug.co.signalelectronics.devicefinance

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
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
        val amount = intent.getStringExtra("amount") ?: "0"
        val intent_id = intent.getStringExtra("intent_id") ?: ""
        val paymentUrl = intent.getStringExtra("payment_url") ?: "${SignalApp.API_BASE}/pay"

        val url = "$paymentUrl?imei=$imei&amount=$amount&intent=$intent_id"

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                Log.i(TAG, "Page loaded: $url")
                // Check if payment succeeded — the page redirects to /success
                if (url?.contains("payment-success") == true) {
                    // Trigger app update check
                    UpdateChecker.checkAndUpdate(this@PaymentActivity)
                    finish()
                }
            }
        }

        Log.i(TAG, "Loading: $url")
        webView.loadUrl(url)
    }
}
