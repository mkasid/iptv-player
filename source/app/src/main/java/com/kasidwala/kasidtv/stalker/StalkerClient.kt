package com.kasidwala.kasidtv.stalker

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Stalker/Ministra portal client (VOD + series only, no live TV).
 *
 * Auth: MAC-based handshake. The portal endpoint is auto-discovered from
 * portal.php, server/load.php and stalker_portal/server/load.php.
 *
 * Stream URLs are short-lived: always resolve with [createLink] immediately
 * before playback, never cache them.
 */
class StalkerClient(private val portalUrl: String, private val mac: String) {

    private var endpoint: String? = null
    private var token: String? = null

    companion object {
        /** MAG-style UA; many portals reject unknown clients. */
        const val UA =
            "Mozilla/5.0 (QtEmbedded; U; Linux; Linux; MAG250; Model: MAG250; Link: WiFi) " +
                "AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    }

    // ------------------------------------------------------------------ http

    private fun httpGet(url: String, jsToken: String? = null): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty(
                    "Cookie",
                    "mac=${URLEncoder.encode(mac.uppercase(), "UTF-8")}; stb_lang=en; timezone=Europe%2FLondon;"
                )
                // Full Stalker/Ministra portals require the token as a Bearer
                // header on every call except handshake/get_profile (auth
                // failures come back as HTTP 200 "Authorization failed.");
                // the ?token= query param is kept for older panels.
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
                // X-Js-Request-Token header (from Lumora's working implementation).
                jsToken?.let { setRequestProperty("X-Js-Request-Token", it) }
                connectTimeout = 15000
                readTimeout = 25000
            }
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** MD5 hex of the input (for device signature). */
    private fun md5(s: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it).uppercase() }
    }

    /** Generate device profile params (from Lumora's working implementation). */
    private fun deviceParams(): Map<String, String> {
        val cleanMac = mac.replace(":", "").replace("-", "").uppercase()
        val sn = "000000000000"
        val deviceId = cleanMac + sn.take(9)
        val deviceId2 = "0123456789ABCDEF0123456789ABCDEF"
        val signature = md5(cleanMac + sn + deviceId2)
        return mapOf(
            "sn" to sn,
            "device_id" to deviceId,
            "device_id2" to deviceId2,
            "signature" to signature,
            "hd" to "1",
            "ver" to "",
            "num_banks" to "1",
            "stb_type" to "MAG250",
            "image_version" to "218"
        )
    }

    private fun endpointVariants(): List<String> {
        val p = portalUrl.trim().trimEnd('/')
        return listOf("$p/portal.php", "$p/server/load.php", "$p/stalker_portal/server/load.php")
    }

    /**
     * Raw portal call. Returns the parsed JSON object, or null on
     * transport/auth failure (one transparent re-handshake is attempted).
     */
    private fun api(
        type: String,
        action: String,
        params: Map<String, String> = emptyMap(),
        retryAuth: Boolean = true,
    ): JSONObject? {
        val ep = endpoint ?: return null
        val qs = StringBuilder("?type=").append(type).append("&action=").append(action)
        // MAC as query param (critical - portals key session to this, from Lumora).
        qs.append("&mac=").append(URLEncoder.encode(mac.uppercase(), "UTF-8"))
        qs.append("&JsHttpRequest=1-xml")
        token?.let { qs.append("&token=").append(URLEncoder.encode(it, "UTF-8")) }
        for ((k, v) in params) {
            qs.append('&').append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
        }
        val body = httpGet(ep + qs.toString(), jsToken = token) ?: return null
        val js = try {
            JSONObject(body).optJSONObject("js")
        } catch (e: Exception) {
            null
        }
        if (js == null && retryAuth && handshake()) {
            return api(type, action, params, retryAuth = false)
        }
        return if (js == null) null else JSONObject(body)
    }

    private fun jsOf(type: String, action: String, params: Map<String, String> = emptyMap()): JSONObject? =
        api(type, action, params)?.optJSONObject("js")

    // ------------------------------------------------------------------ auth

    /** Handshake against each endpoint variant until one issues a token. */
    fun handshake(): Boolean {
        for (ep in endpointVariants()) {
            val body = httpGet("$ep?type=stb&action=handshake&token=&JsHttpRequest=1-xml")
                ?: continue
            val tok = try {
                JSONObject(body).optJSONObject("js")?.optString("token")
            } catch (e: Exception) {
                null
            }
            if (!tok.isNullOrEmpty()) {
                endpoint = ep
                token = tok
                // Adopt the session: call get_profile with full device profile
                // (signature, device_id, etc.) so the token is honored on
                // vod/series calls (from Lumora's working implementation).
                val devParams = deviceParams().toMutableMap()
                devParams["mac"] = mac.uppercase()
                api("stb", "get_profile", devParams, retryAuth = false)
                return true
            }
        }
        return false
    }

    fun ensureAuth(): Boolean = (endpoint != null && token != null) || handshake()

    // ------------------------------------------------------------------ vod

    fun getVodCategories(): List<VodCategory> {
        if (!ensureAuth()) return emptyList()
        val arr = jsOf("vod", "get_categories")?.optJSONArray("data") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            VodCategory(o.optString("id"), o.optString("title"))
        }
    }

    fun getVodList(categoryId: String, page: Int, search: String? = null): VodPage {
        if (!ensureAuth()) return VodPage(emptyList(), 0, 0, page)
        val p = mutableMapOf("p" to page.toString(), "sortby" to "added")
        if (categoryId.isNotEmpty()) p["category"] = categoryId
        if (!search.isNullOrBlank()) p["search"] = search
        val js = jsOf("vod", "get_ordered_list", p) ?: return VodPage(emptyList(), 0, 0, page)
        return parseVodPage(js, page)
    }

    private fun parseVodPage(js: JSONObject, page: Int): VodPage {
        val arr = js.optJSONArray("data") ?: return VodPage(emptyList(), 0, 0, page)
        val items = ArrayList<VodItem>(arr.length())
        for (i in 0 until arr.length()) {
            items.add(parseVodItem(arr.optJSONObject(i) ?: continue))
        }
        return VodPage(items, js.optInt("total_items"), js.optInt("max_page_items"), js.optInt("cur_page", page))
    }

    private fun parseVodItem(o: JSONObject): VodItem {
        val seriesField = o.opt("series")
        val isSeries = when (seriesField) {
            is JSONArray -> seriesField.length() > 0
            is String -> seriesField.isNotEmpty()
            is Number -> seriesField.toInt() != 0
            else -> o.optString("is_series") == "1"
        }
        val rating = o.optString("rating_imdb")
            .ifEmpty { o.optString("rating") }
            .ifEmpty { null }
        return VodItem(
            id = o.optString("id"),
            name = o.optString("name"),
            oName = o.optString("o_name"),
            screenshot = resolveUrl(
                o.optString("screenshot").ifEmpty { o.optString("screenshot_uri") }
            ),
            cmd = o.optString("cmd").ifEmpty { null },
            isSeries = isSeries,
            rating = rating,
        )
    }

    // ------------------------------------------------------------------ series

    /** Series categories; empty when the portal lumps series into VOD. */
    fun getSeriesCategories(): List<VodCategory> {
        if (!ensureAuth()) return emptyList()
        val arr = jsOf("series", "get_categories")?.optJSONArray("data") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            VodCategory(o.optString("id"), o.optString("title"))
        }
    }

    fun getSeriesList(categoryId: String, page: Int, search: String? = null): VodPage {
        if (!ensureAuth()) return VodPage(emptyList(), 0, 0, page)
        val p = mutableMapOf("p" to page.toString(), "sortby" to "added")
        if (categoryId.isNotEmpty()) p["category"] = categoryId
        if (!search.isNullOrBlank()) p["search"] = search
        // Prefer the dedicated series type; fall back to vod when the portal
        // does not separate them.
        val js = jsOf("series", "get_ordered_list", p)
            ?: jsOf("vod", "get_ordered_list", p)
            ?: return VodPage(emptyList(), 0, 0, page)
        return parseVodPage(js, page)
    }

    fun getSeasons(seriesId: String): List<Season> {
        if (!ensureAuth()) return emptyList()
        val js = jsOf("vod", "get_ordered_list", mapOf("movie_id" to seriesId))
            ?: jsOf("series", "get_ordered_list", mapOf("movie_id" to seriesId))
            ?: return emptyList()
        val arr = js.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Season>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("is_season") != "1" && !o.has("season_id")) continue
            val id = o.optString("id").ifEmpty { o.optString("season_id") }
            if (id.isEmpty()) continue
            val num = o.optString("season_number").ifEmpty { (out.size + 1).toString() }
            out.add(Season(id, o.optString("name").ifEmpty { "Season $num" }))
        }
        // Some portals return episodes directly when there are no seasons.
        if (out.isEmpty() && arr.length() > 0) out.add(Season("0", "Season 1"))
        return out
    }

    fun getEpisodes(seriesId: String, seasonId: String): List<Episode> {
        if (!ensureAuth()) return emptyList()
        val params = mutableMapOf("movie_id" to seriesId)
        if (seasonId != "0") params["season_id"] = seasonId
        val js = jsOf("vod", "get_ordered_list", params)
            ?: jsOf("series", "get_ordered_list", params)
            ?: return emptyList()
        val arr = js.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Episode>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("is_season") == "1") continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            var number = o.optString("series")
            if (number.isEmpty()) {
                number = Regex("""(?i)episode\s*(\d+)""").find(o.optString("name"))
                    ?.groupValues?.getOrNull(1) ?: (out.size + 1).toString()
            }
            out.add(
                Episode(
                    id = id,
                    name = o.optString("name").ifEmpty { "Episode $number" },
                    cmd = o.optString("cmd").ifEmpty { null },
                    number = number,
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------------ playback

    /**
     * Resolve a playable stream URL. Must be called right before playback;
     * the returned URL is short-lived.
     */
    fun createLink(cmd: String, series: String = ""): String? {
        if (!ensureAuth()) return null
        val js = jsOf("vod", "create_link", mapOf("cmd" to cmd, "series" to series))
            ?: return null
        var link = js.optString("cmd").ifEmpty { js.optString("url") }.ifEmpty { return null }
        link = link.replace(Regex("^ffmpeg\\s+", RegexOption.IGNORE_CASE), "").trim()
        return link.ifEmpty { null }
    }

    // ------------------------------------------------------------------ helpers

    private fun resolveUrl(u: String?): String? {
        if (u.isNullOrBlank() || u == "N/A") return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        val base = portalUrl.trim().trimEnd('/')
        return if (u.startsWith("/")) {
            base.replace(Regex("^(https?://[^/]+).*$"), "$1") + u
        } else {
            "$base/$u"
        }
    }
}
