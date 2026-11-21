package com.youtubelite.car

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {
    private var web: WebView? = null
    private lateinit var bar: LinearLayout
    private lateinit var msg: TextView
    private lateinit var exitFs: Button
    private var fs = false
    private var vid = ""

    inner class Bridge {
        @JavascriptInterface fun error(code: Int) { runOnUiThread { showErr(code) } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val id = intent.getStringExtra("id")
        if (id == null || !Regex("[A-Za-z0-9_-]{6,20}").matches(id)) { finish(); return }
        vid = id
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val title = intent.getStringExtra("title") ?: ""
        lifecycleScope.launch {
            AppDb.get(applicationContext).dao().addHistory(
                HistoryItem(videoId = id, title = title, channel = intent.getStringExtra("channel") ?: "",
                    thumb = intent.getStringExtra("thumb") ?: "", watchedAt = System.currentTimeMillis())
            )
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val frame = FrameLayout(this)
        val w = WebView(this)
        web = w
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.setBackgroundColor(Color.BLACK)
        w.addJavascriptInterface(Bridge(), "A")
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
        }
        frame.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
        msg = tv("", 20f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)); visibility = View.GONE }
        frame.addView(msg, FrameLayout.LayoutParams(MATCH, MATCH))
        exitFs = btn("خروج من ملء الشاشة") { setFs(false) }.apply { visibility = View.GONE }
        frame.addView(exitFs, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))

        bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(BG) }
        bar.addView(tv(title, 16f, FG).apply { maxLines = 2 }, lp(0, WRAP, 1f))
        bar.addView(btn("تشغيل/إيقاف") { js("if(pl){pl.getPlayerState()==1?pl.pauseVideo():pl.playVideo()}") }, lp(WRAP))
        bar.addView(btn("صوت -") { js("if(pl){pl.setVolume(Math.max(0,pl.getVolume()-10))}") }, lp(WRAP))
        bar.addView(btn("صوت +") { js("if(pl){pl.setVolume(Math.min(100,pl.getVolume()+10))}") }, lp(WRAP))
        bar.addView(btn("ملء الشاشة") { setFs(true) }, lp(WRAP))
        bar.addView(btn("رجوع") { finish() }, lp(WRAP))
        root.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (fs) setFs(false) else finish() }
        })
        load()
    }

    private fun load() {
        web?.loadDataWithBaseURL("https://www.youtube.com", html(), "text/html", "utf-8", null)
    }

    private fun html() = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;height:100%;background:#000;overflow:hidden}</style></head><body><div id="p"></div><script>var pl;function onYouTubeIframeAPIReady(){pl=new YT.Player('p',{width:'100%',height:window.innerHeight,videoId:'$vid',playerVars:{playsinline:1,rel:0,fs:0,autoplay:1},events:{onReady:function(e){try{e.target.setPlaybackQuality('large')}catch(x){}e.target.playVideo()},onError:function(e){A.error(e.data)}}})}window.onresize=function(){if(pl&&pl.setSize)pl.setSize(window.innerWidth,window.innerHeight)};</script><script src="https://www.youtube.com/iframe_api" onerror="A.error(-1)"></script></body></html>"""

    private fun js(s: String) { web?.evaluateJavascript(s, null) }

    private fun showErr(code: Int) {
        msg.text = when (code) {
            2 -> "رابط الفيديو غير صالح"
            5 -> "تعذر تشغيل الفيديو على هذا الجهاز"
            100 -> "الفيديو غير متاح (محذوف أو خاص)"
            101, 150 -> "صاحب الفيديو لا يسمح بتشغيله داخل التطبيقات. اضغط لفتحه في يوتيوب"
            -1 -> "تعذر تحميل المشغل. تحقق من الإنترنت ثم اضغط لإعادة المحاولة"
            else -> "خطأ في التشغيل ($code). اضغط لإعادة المحاولة"
        }
        msg.visibility = View.VISIBLE
        msg.setOnClickListener {
            if (code == 101 || code == 150) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$vid")))
            else { msg.visibility = View.GONE; load() }
        }
    }

    private fun setFs(on: Boolean) {
        fs = on
        bar.visibility = if (on) View.GONE else View.VISIBLE
        exitFs.visibility = if (on) View.VISIBLE else View.GONE
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onPause() { js("if(pl){pl.pauseVideo()}"); web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }

    override fun onDestroy() {
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading(); it.loadUrl("about:blank"); it.removeJavascriptInterface("A"); it.destroy()
        }
        web = null
        super.onDestroy()
    }
}
