package com.youtubelite.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

val BG = Color.parseColor("#121212")
val FG = Color.parseColor("#EEEEEE")
val BTN = Color.parseColor("#2E4A7D")
val ROW = Color.parseColor("#1E1E1E")
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.tv(t: String, size: Float = 18f, color: Int = FG, bold: Boolean = false) = TextView(this).apply {
    text = t; textSize = size; setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

fun Context.btn(t: String, onClick: () -> Unit) = Button(this).apply {
    text = t; textSize = 17f; isAllCaps = false; setTextColor(FG); setBackgroundColor(BTN)
    minimumHeight = dp(52); setOnClickListener { onClick() }
}

fun Context.lp(w: Int = MATCH, h: Int = WRAP, wt: Float = 0f, m: Int = 4) =
    LinearLayout.LayoutParams(w, h, wt).apply { setMargins(dp(m), dp(m), dp(m), dp(m)) }

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.recycler(ad: RecyclerView.Adapter<*>) = RecyclerView(this).apply {
    layoutManager = LinearLayoutManager(context); adapter = ad; itemAnimator = null; setHasFixedSize(true)
}

// تحميل صور مصغرة بدون مكتبات: كاش صغير + خيطان فقط
object Img {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtMost(6 * 1024 * 1024)
    ) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    private val pool = Executors.newFixedThreadPool(2)
    private val h = Handler(Looper.getMainLooper())

    fun load(v: ImageView, url: String) {
        v.tag = url
        v.setImageDrawable(null)
        if (url.isEmpty()) return
        cache.get(url)?.let { v.setImageBitmap(it); return }
        pool.execute {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000
                val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    cache.put(url, bmp)
                    h.post { if (v.tag == url) v.setImageBitmap(bmp) }
                }
            } catch (e: Exception) { }
        }
    }
}

class VidAdapter(val onClick: (Vid) -> Unit, val onLong: (Vid) -> Unit) : RecyclerView.Adapter<VidAdapter.H>() {
    val items = ArrayList<Vid>()

    class H(val root: LinearLayout, val img: ImageView, val t: TextView, val s: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(p: ViewGroup, viewType: Int): H {
        val c = p.context
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8)); setBackgroundColor(ROW)
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { bottomMargin = c.dp(6) }
        }
        val img = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.DKGRAY)
            layoutParams = LinearLayout.LayoutParams(c.dp(160), c.dp(90))
        }
        val col = c.vbox().apply { setPadding(c.dp(12), 0, c.dp(12), 0) }
        val t = c.tv("", 18f, FG, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val s = c.tv("", 15f, Color.GRAY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        col.addView(t); col.addView(s)
        row.addView(img); row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return H(row, img, t, s)
    }

    override fun onBindViewHolder(h: H, i: Int) {
        val v = items[i]
        h.t.text = v.title
        val sub = if (v.kind == 1) "قناة" else listOf(v.channel, v.dur).filter { it.isNotEmpty() }.joinToString(" • ")
        h.s.text = sub
        Img.load(h.img, v.thumb)
        h.root.setOnClickListener { onClick(v) }
        h.root.setOnLongClickListener { onLong(v); true }
    }

    override fun getItemCount() = items.size
    fun set(l: List<Vid>) { items.clear(); items.addAll(l); notifyDataSetChanged() }
    fun add(l: List<Vid>) { val s = items.size; items.addAll(l); notifyItemRangeInserted(s, l.size) }
}
