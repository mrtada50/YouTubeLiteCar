package com.youtubelite.car

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

class Act(val label: String, val run: () -> Unit)

class MainActivity : ComponentActivity() {
    private lateinit var dao: AppDao
    private lateinit var content: FrameLayout
    private val stack = ArrayList<() -> View>()
    private var pendingAfter: (() -> Unit)? = null
    private var searchJob: Job? = null
    private var searchType = "video"
    private var libTab = 0
    private val FAV_REMOVE = "إزالة من المفضلة"

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        try {
            Auth.token = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(r.data).accessToken
            toast("تم تسجيل الدخول")
            runPending()
        } catch (e: Exception) { pendingAfter = null; toast("لم يكتمل تسجيل الدخول") }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        dao = AppDb.get(this).dao()
        lifecycleScope.launch { dao.pruneCache(System.currentTimeMillis() - 7L * 86_400_000) }

        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG) }
        val rail = vbox().apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
        rail.addView(tv("YT Lite Car", 15f, Color.GRAY, true), lp())
        rail.addView(btn("الرئيسية") { nav { homeView() } }, lp())
        rail.addView(btn("بحث") { nav { searchView() } }, lp())
        rail.addView(btn("المكتبة") { nav { libraryView() } }, lp())
        rail.addView(btn("السجل") { nav { historyView() } }, lp())
        rail.addView(btn("الحساب") { nav { accountView() } }, lp())
        root.addView(ScrollView(this).apply { addView(rail) }, LinearLayout.LayoutParams(dp(130), MATCH))
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(0, MATCH, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stack.size > 1) { stack.removeAt(stack.size - 1); redraw() } else finish()
            }
        })
        nav { homeView() }
    }

    // ---------- تنقل ----------
    private fun nav(r: () -> View) { stack.clear(); push(r) }
    private fun push(r: () -> View) { stack.add(r); redraw() }
    private fun redraw() { content.removeAllViews(); content.addView(stack.last().invoke()) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun hideKb(v: View) { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }

    private fun errText(e: Exception) = when (e) {
        is ApiException -> e.message ?: "خطأ"
        is IOException -> "لا يوجد اتصال بالإنترنت"
        else -> "حدث خطأ غير متوقع"
    }

    private fun handleErr(e: Exception) {
        if (e is ApiException && e.code == 401) { Auth.token = null; toast("انتهت الجلسة، سجّل الدخول مجدداً") }
        else toast(errText(e))
    }

    private fun play(v: Vid) {
        when (v.kind) {
            1 -> push { channelView(v) }
            2 -> push { accountPlaylistView(v) }
            3 -> push { localPlaylistView(v) }
            else -> startActivity(
                Intent(this, PlayerActivity::class.java).putExtra("id", v.id).putExtra("title", v.title)
                    .putExtra("channel", v.channel).putExtra("thumb", v.thumb)
            )
        }
    }

    // ---------- حوارات ----------
    private fun dialog(title: String, acts: List<Act>) {
        AlertDialog.Builder(this).setTitle(title)
            .setItems(acts.map { it.label }.toTypedArray()) { _, i -> acts[i].run() }.show()
    }

    private fun askText(title: String, init: String = "", ok: (String) -> Unit) {
        val et = EditText(this).apply { setText(init); setSingleLine() }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("موافق") { _, _ -> val s = et.text.toString().trim(); if (s.isNotEmpty()) ok(s) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("نعم") { _, _ -> ok() }.setNegativeButton("لا", null).show()
    }

    private fun actions(v: Vid, extra: List<Act>) {
        when (v.kind) {
            3 -> dialog(v.title, listOf(
                Act("إعادة تسمية") { askText("اسم جديد", v.title) { n -> lifecycleScope.launch { dao.renamePlaylist(v.id.toLong(), n); redraw() } } },
                Act("حذف القائمة") { confirm("حذف القائمة؟") { lifecycleScope.launch { dao.delPlaylistItems(v.id.toLong()); dao.delPlaylist(v.id.toLong()); redraw() } } }
            ))
            2 -> dialog(v.title, listOf(
                Act("إعادة تسمية") { withAuth { askText("اسم جديد", v.title) { n ->
                    lifecycleScope.launch { try { Api.renamePl(Auth.need(), v.id, n); redraw() } catch (e: Exception) { handleErr(e) } }
                } } }
            ))
            0 -> lifecycleScope.launch {
                val fav = dao.isFav(v.id) > 0
                val acts = ArrayList<Act>()
                if (extra.none { it.label == FAV_REMOVE }) {
                    acts.add(Act(if (fav) FAV_REMOVE else "إضافة إلى المفضلة") {
                        lifecycleScope.launch {
                            if (fav) dao.delFav(v.id) else dao.addFav(Favorite(v.id, v.title, v.channel, v.thumb, System.currentTimeMillis()))
                            toast("تم")
                        }
                    })
                }
                acts.add(Act("إضافة إلى قائمة محلية") { pickLocalPlaylist(v) })
                acts.add(Act("إضافة إلى قائمة يوتيوب") { withAuth { pickAccountPlaylist(v) } })
                acts.addAll(extra)
                dialog(v.title, acts)
            }
        }
    }

    private fun pickLocalPlaylist(v: Vid) = lifecycleScope.launch {
        val pls = dao.playlists()
        if (pls.isEmpty()) { toast("أنشئ قائمة محلية من المكتبة أولاً"); return@launch }
        dialog("اختر القائمة", pls.map { p ->
            Act(p.name) {
                lifecycleScope.launch {
                    dao.addPlaylistItem(LocalPlaylistItem(p.id, v.id, v.title, v.channel, v.thumb, System.currentTimeMillis()))
                    toast("تمت الإضافة")
                }
            }
        })
    }

    private fun pickAccountPlaylist(v: Vid) = lifecycleScope.launch {
        try {
            val pls = Api.myPlaylists(Auth.need())
            if (pls.isEmpty()) { toast("لا توجد قوائم في حسابك"); return@launch }
            dialog("اختر قائمة يوتيوب", pls.map { p ->
                Act(p.title) {
                    lifecycleScope.launch {
                        try { Api.addToPl(Auth.need(), p.id, v.id); toast("تمت الإضافة") } catch (e: Exception) { handleErr(e) }
                    }
                }
            })
        } catch (e: Exception) { handleErr(e) }
    }

    // ---------- تسجيل الدخول (اختياري) ----------
    private fun withAuth(f: () -> Unit) { if (Auth.token != null) f() else { pendingAfter = f; signIn() } }
    private fun runPending() { val p = pendingAfter; pendingAfter = null; p?.invoke() }

    private fun signIn() {
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(Auth.SCOPE))).build()
        Identity.getAuthorizationClient(this).authorize(req)
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    authLauncher.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                } else { Auth.token = r.accessToken; runPending() }
            }
            .addOnFailureListener { pendingAfter = null; toast("تعذر تسجيل الدخول (تأكد من خدمات Google وإعداد OAuth)") }
    }

    // ---------- قوائم عامة ----------
    private fun listBody(header: View?, load: suspend () -> List<Vid>, extra: (Vid) -> List<Act> = { emptyList() }): View {
        val col = vbox()
        header?.let { col.addView(it, lp()) }
        val status = tv("جاري التحميل...", 16f, Color.GRAY)
        col.addView(status, lp())
        val ad = VidAdapter({ play(it) }, { actions(it, extra(it)) })
        col.addView(recycler(ad), lp(MATCH, 0, 1f, 0))
        lifecycleScope.launch {
            try {
                val l = load()
                ad.set(l)
                if (l.isEmpty()) status.text = "لا توجد عناصر" else status.visibility = View.GONE
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                status.text = errText(e)
                if (e is ApiException && e.code == 401) Auth.token = null
            }
        }
        return col
    }

    private fun screen(title: String, body: View): View {
        val col = vbox().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        col.addView(tv(title, 22f, FG, true), lp())
        col.addView(body, lp(MATCH, 0, 1f, 0))
        return col
    }

    // ---------- الشاشات ----------
    private fun homeView(): View {
        val et = EditText(this).apply {
            hint = "ابحث في يوتيوب"; setSingleLine(); setTextColor(FG); setHintTextColor(Color.GRAY)
            textSize = 18f; imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        fun go() { val q = et.text.toString().trim(); if (q.isNotEmpty()) { hideKb(et); push { searchView(q) } } }
        et.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { go(); true } else false }
        val head = LinearLayout(this).apply {
            addView(et, lp(0, WRAP, 1f)); addView(btn("بحث") { go() }, lp(WRAP))
        }
        val col = vbox().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        col.addView(tv("YouTube Lite Car", 26f, FG, true), lp())
        col.addView(head, lp())
        col.addView(tv("آخر ما شاهدته", 16f, Color.GRAY), lp())
        col.addView(listBody(null, { dao.history(10).map { it.toVid() } }), lp(MATCH, 0, 1f, 0))
        return col
    }

    private fun searchView(initial: String = ""): View {
        val ad = VidAdapter({ play(it) }, { actions(it, emptyList()) })
        val et = EditText(this).apply {
            hint = "ابحث عن فيديو أو قناة"; setSingleLine(); setTextColor(FG); setHintTextColor(Color.GRAY)
            textSize = 18f; imeOptions = EditorInfo.IME_ACTION_SEARCH; setText(initial)
        }
        val status = tv("", 16f, Color.GRAY)
        var next: String? = null
        val more = btn("المزيد") { }
        more.visibility = View.GONE

        fun doSearch(page: String?) {
            val q = et.text.toString().trim()
            if (q.length < 2) return
            status.visibility = View.VISIBLE; status.text = "جاري البحث..."
            lifecycleScope.launch {
                try {
                    val (l, n) = Api.search(dao, q, searchType, page)
                    if (page == null) ad.set(l) else ad.add(l)
                    next = n
                    more.visibility = if (n != null) View.VISIBLE else View.GONE
                    if (ad.itemCount == 0) status.text = "لا توجد نتائج" else status.visibility = View.GONE
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { status.text = errText(e) }
            }
        }
        more.setOnClickListener { doSearch(next) }
        val toggle = btn(if (searchType == "video") "فيديو" else "قناة") { }
        toggle.setOnClickListener {
            searchType = if (searchType == "video") "channel" else "video"
            toggle.text = if (searchType == "video") "فيديو" else "قناة"
            ad.set(emptyList()); doSearch(null)
        }
        et.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_SEARCH) { searchJob?.cancel(); hideKb(et); doSearch(null); true } else false
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch { delay(700); doSearch(null) }
            }
        })
        val head = LinearLayout(this).apply { addView(et, lp(0, WRAP, 1f)); addView(toggle, lp(WRAP)) }
        val col = vbox().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        col.addView(head, lp()); col.addView(status, lp())
        col.addView(recycler(ad), lp(MATCH, 0, 1f, 0)); col.addView(more, lp())
        if (initial.isNotEmpty()) doSearch(null)
        return col
    }

    private fun channelView(v: Vid) = screen(v.title, listBody(null, { Api.search(dao, "", "video", null, v.id).first }))

    private fun libraryView(): View {
        val body = FrameLayout(this)
        val tabs = LinearLayout(this)
        fun show(i: Int) {
            libTab = i
            body.removeAllViews()
            body.addView(when (i) {
                0 -> listBody(null, { dao.favorites().map { it.toVid() } }) { v ->
                    listOf(Act(FAV_REMOVE) { lifecycleScope.launch { dao.delFav(v.id); redraw() } })
                }
                1 -> listBody(btn("قائمة جديدة") {
                    askText("اسم القائمة") { n -> lifecycleScope.launch { dao.addPlaylist(LocalPlaylist(name = n)); redraw() } }
                }, { dao.playlists().map { Vid(it.id.toString(), it.name, "قائمة محلية", "", kind = 3) } })
                else -> if (Auth.token == null) vbox().apply {
                    addView(tv("قوائم حسابك تحتاج تسجيل الدخول (اختياري). بقية المزايا تعمل بدون حساب.", 17f), lp())
                    addView(btn("تسجيل الدخول بحساب Google") { withAuth { redraw() } }, lp())
                } else listBody(btn("قائمة جديدة") {
                    askText("اسم القائمة") { n ->
                        lifecycleScope.launch { try { Api.createPl(Auth.need(), n); redraw() } catch (e: Exception) { handleErr(e) } }
                    }
                }, { Api.myPlaylists(Auth.need()) })
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        listOf("المفضلة", "قوائم محلية", "قوائم يوتيوب (سحابية)").forEachIndexed { i, t ->
            tabs.addView(btn(t) { show(i) }, lp(0, WRAP, 1f))
        }
        val col = vbox().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        col.addView(tv("المكتبة", 22f, FG, true), lp()); col.addView(tabs, lp())
        col.addView(body, lp(MATCH, 0, 1f, 0))
        show(libTab)
        return col
    }

    private fun localPlaylistView(p: Vid) = screen(p.title, listBody(null, {
        dao.playlistItems(p.id.toLong()).map { it.toVid() }
    }) { v -> listOf(Act("إزالة من القائمة") { lifecycleScope.launch { dao.delPlaylistItem(p.id.toLong(), v.id); redraw() } }) })

    private fun accountPlaylistView(p: Vid) = screen(p.title, listBody(null, { Api.plItems(Auth.need(), p.id) }) { v ->
        listOf(Act("حذف من قائمة يوتيوب") {
            lifecycleScope.launch { try { Api.removeFromPl(Auth.need(), v.extra); redraw() } catch (e: Exception) { handleErr(e) } }
        })
    })

    private fun historyView(): View {
        val clear = btn("مسح السجل بالكامل") { confirm("مسح كل السجل؟") { lifecycleScope.launch { dao.clearHistory(); redraw() } } }
        return screen("سجل المشاهدة", listBody(clear, { dao.history(100).map { it.toVid() } }) { v ->
            listOf(Act("حذف من السجل") { lifecycleScope.launch { dao.delHistory(v.extra.toLong()); redraw() } })
        })
    }

    private fun accountView(): View {
        val col = vbox().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        col.addView(tv("الحساب", 24f, FG, true), lp())
        col.addView(tv(if (Auth.token != null) "الحالة: مسجّل الدخول" else "الحالة: وضع الضيف (البحث والمشاهدة والمفضلة والسجل والقوائم المحلية تعمل بدون حساب)", 17f), lp())
        if (BuildConfig.YT_API_KEY.isEmpty())
            col.addView(tv("تنبيه: مفتاح YouTube API غير مضبوط، البحث لن يعمل.", 16f, Color.parseColor("#FFB74D")), lp())
        if (Auth.token == null) {
            col.addView(btn("تسجيل الدخول بحساب Google") { withAuth { redraw() } }, lp())
        } else {
            col.addView(btn("تسجيل الخروج") { Auth.token = null; redraw() }, lp())
            col.addView(btn("إلغاء ربط الحساب (صفحة صلاحيات Google)") {
                Auth.token = null
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/permissions")))
                redraw()
            }, lp())
        }
        col.addView(btn("متابعة كضيف") { nav { homeView() } }, lp())
        return ScrollView(this).apply { addView(col) }
    }
}
