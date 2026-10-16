package com.virtualandroid.app

import android.Manifest
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+: indirme bildirimi göstermek için izin (reddedilirse indirme yine çalışır)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        web = WebView(this)
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true      // arayüz makine listesini localStorage'da tutar
            allowContentAccess = false
            allowFileAccess = false       // file:///android_asset/ yine de açılır
        }
        web.addJavascriptInterface(Bridge(applicationContext), "Android")
        web.webViewClient = object : WebViewClient() {
            // Sadece uygulamanın kendi sayfası yüklenir, dışarıya gidişi engelle
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.url.toString().startsWith("file:///android_asset/")
        }

        if (savedInstanceState == null) {
            web.loadUrl("file:///android_asset/index.html")
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Geri tuşunu önce arayüze sor (açık pencere varsa kapatır)")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.__onBack && window.__onBack()) ? 'true' : 'false'") { handled ->
            // handled JS tarafından geri tuşu işlendi mi bilgisini taşır
            if (handled?.contains("true") != true) finish()
        }
    }
}
