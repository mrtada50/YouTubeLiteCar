package com.youtubelite.car

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// kind: 0 فيديو، 1 قناة، 2 قائمة يوتيوب، 3 قائمة محلية
data class Vid(
    val id: String, val title: String, val channel: String, val thumb: String,
    val dur: String = "", val kind: Int = 0, val extra: String = ""
)

class ApiException(val code: Int, msg: String) : Exception(msg)

object Auth {
    const val SCOPE = "https://www.googleapis.com/auth/youtube"
    @Volatile var token: String? = null // في الذاكرة فقط، لا يُحفظ على القرص
    fun need(): String = token ?: throw ApiException(401, "سجّل الدخول أولاً")
}

object Parse {
    private fun un(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")

    private fun thumb(sn: JSONObject) =
        sn.optJSONObject("thumbnails")?.optJSONObject("default")?.optString("url").orEmpty()

    fun duration(iso: String): String {
        val m = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(iso) ?: return ""
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val mi = m.groupValues[2].toIntOrNull() ?: 0
        val s = m.groupValues[3].toIntOrNull() ?: 0
        return if (h > 0) String.format("%d:%02d:%02d", h, mi, s) else String.format("%d:%02d", mi, s)
    }

    fun search(json: String): Pair<List<Vid>, String?> {
        val o = JSONObject(json)
        val arr = o.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<Vid>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val sn = e.optJSONObject("snippet") ?: continue
            val id = e.optJSONObject("id")
            val v = id?.optString("videoId").orEmpty()
            val ch = id?.optString("channelId").orEmpty()
            if (v.isNotEmpty()) out.add(Vid(v, un(sn.optString("title")), un(sn.optString("channelTitle")), thumb(sn)))
            else if (ch.isNotEmpty()) out.add(
                Vid(ch, un(sn.optString("channelTitle").ifEmpty { sn.optString("title") }), "", thumb(sn), kind = 1)
            )
        }
        return out to o.optString("nextPageToken").ifEmpty { null }
    }

    fun durations(json: String): Map<String, String> {
        val arr = JSONObject(json).optJSONArray("items") ?: return emptyMap()
        val m = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            m[e.getString("id")] = duration(e.optJSONObject("contentDetails")?.optString("duration").orEmpty())
        }
        return m
    }

    fun playlists(json: String): List<Vid> {
        val arr = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).map {
            val e = arr.getJSONObject(it)
            val sn = e.getJSONObject("snippet")
            Vid(e.getString("id"), un(sn.optString("title")), "قائمة يوتيوب", thumb(sn), kind = 2)
        }
    }

    fun playlistItems(json: String): List<Vid> {
        val arr = JSONObject(json).optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Vid>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val sn = e.getJSONObject("snippet")
            val vid = sn.optJSONObject("resourceId")?.optString("videoId").orEmpty()
            if (vid.isEmpty()) continue
            out.add(Vid(vid, un(sn.optString("title")), un(sn.optString("videoOwnerChannelTitle")), thumb(sn), extra = e.getString("id")))
        }
        return out
    }

    fun encode(list: List<Vid>, next: String?): String {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("i", it.id).put("t", it.title).put("c", it.channel)
                .put("th", it.thumb).put("d", it.dur).put("k", it.kind))
        }
        return JSONObject().put("next", next ?: "").put("items", a).toString()
    }

    fun decode(json: String): Pair<List<Vid>, String?> {
        val o = JSONObject(json)
        val a = o.getJSONArray("items")
        val l = (0 until a.length()).map {
            val e = a.getJSONObject(it)
            Vid(e.getString("i"), e.getString("t"), e.getString("c"), e.getString("th"), e.getString("d"), e.getInt("k"))
        }
        return l to o.optString("next").ifEmpty { null }
    }

    fun errMsg(code: Int, text: String): String = try {
        val m = JSONObject(text).getJSONObject("error").getString("message")
        if (code == 403 && m.contains("quota", true)) "تم استهلاك الحصة اليومية لـ YouTube API" else m
    } catch (e: Exception) { "خطأ من الخادم ($code)" }
}

object Api {
    private const val B = "https://www.googleapis.com/youtube/v3/"

    fun http(method: String, url: String, token: String? = null, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8000
            c.readTimeout = 10000
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) throw ApiException(code, Parse.errMsg(code, text))
            return text
        } finally { c.disconnect() }
    }

    private suspend fun <T> io(b: () -> T): T = withContext(Dispatchers.IO) { b() }

    suspend fun search(dao: AppDao, q: String, type: String, page: String? = null, channelId: String? = null): Pair<List<Vid>, String?> =
        withContext(Dispatchers.IO) {
            if (BuildConfig.YT_API_KEY.isEmpty()) throw ApiException(0, "مفتاح YouTube API غير مضبوط (راجع README)")
            val key = "$type|${channelId ?: ""}|${q.trim().lowercase()}|${page ?: ""}"
            val c = dao.cache(key)
            val now = System.currentTimeMillis()
            if (c != null && now - c.ts < 3_600_000) return@withContext Parse.decode(c.json)
            try {
                val u = StringBuilder(B + "search?part=snippet&maxResults=10&type=$type&key=${BuildConfig.YT_API_KEY}")
                if (q.isNotBlank()) u.append("&q=").append(URLEncoder.encode(q, "UTF-8"))
                if (channelId != null) u.append("&channelId=$channelId&order=date")
                if (page != null) u.append("&pageToken=$page")
                val (list, next) = Parse.search(http("GET", u.toString()))
                val full = if (type == "video") withDur(list) else list
                dao.putCache(CachedSearch(key, Parse.encode(full, next), now))
                full to next
            } catch (e: IOException) {
                if (c != null) Parse.decode(c.json) else throw e
            }
        }

    private fun withDur(l: List<Vid>): List<Vid> = try {
        if (l.isEmpty()) l else {
            val d = Parse.durations(http("GET", B + "videos?part=contentDetails&id=${l.joinToString(",") { it.id }}&key=${BuildConfig.YT_API_KEY}"))
            l.map { it.copy(dur = d[it.id].orEmpty()) }
        }
    } catch (e: Exception) { l }

    suspend fun myPlaylists(t: String) = io { Parse.playlists(http("GET", B + "playlists?part=snippet&mine=true&maxResults=25", t)) }
    suspend fun plItems(t: String, id: String) = io { Parse.playlistItems(http("GET", B + "playlistItems?part=snippet&maxResults=25&playlistId=$id", t)) }
    suspend fun createPl(t: String, name: String) = io {
        http("POST", B + "playlists?part=snippet", t, JSONObject().put("snippet", JSONObject().put("title", name)).toString()); Unit
    }
    suspend fun renamePl(t: String, id: String, name: String) = io {
        http("PUT", B + "playlists?part=snippet", t,
            JSONObject().put("id", id).put("snippet", JSONObject().put("title", name)).toString()); Unit
    }
    suspend fun addToPl(t: String, pl: String, vid: String) = io {
        val rid = JSONObject().put("kind", "youtube#video").put("videoId", vid)
        val sn = JSONObject().put("playlistId", pl).put("resourceId", rid)
        http("POST", B + "playlistItems?part=snippet", t, JSONObject().put("snippet", sn).toString()); Unit
    }
    suspend fun removeFromPl(t: String, itemId: String) = io { http("DELETE", B + "playlistItems?id=$itemId", t); Unit }
}
