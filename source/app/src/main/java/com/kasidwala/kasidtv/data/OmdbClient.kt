package com.kasidwala.kasidtv.data

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Enriched metadata from OMDb (one free key covers IMDb + RT + age rating). */
data class OmdbMeta(
    val title: String,
    val year: String?,
    val rated: String?,      // age rating, e.g. "PG-13"
    val plot: String?,
    val poster: String?,
    val imdb: String?,       // e.g. "8.8"
    val rt: String?,         // e.g. "87%"
)

object OmdbClient {
    /**
     * Daily budget for *background* (listing) enrichment. Explicit details
     * lookups always go through. The free tier is 1,000 req/day; every title
     * is cached after its first lookup, so the quota only ever pays for new
     * titles the user actually views.
     */
    private const val DAILY_CARD_BUDGET = 800

    /**
     * Look up a title. [background] = true for list rows (budget-capped);
     * false for the details screen (always allowed). Quota-exceeded errors
     * are never cached; "not found" is cached so we don't pay for it twice.
     */
    fun lookup(ctx: Context, title: String, year: String? = null, background: Boolean = false): OmdbMeta? {
        val key = "t:" + title.trim().lowercase()
        DbHelper.get(ctx).getOmdb(key)?.let { return parse(it) }

        val apiKey = Prefs.getOmdbKey(ctx)
        if (apiKey.isBlank()) return null
        if (background && !checkBudget(ctx)) return null

        val qs = StringBuilder("?apikey=").append(URLEncoder.encode(apiKey, "UTF-8"))
            .append("&t=").append(URLEncoder.encode(title.trim(), "UTF-8"))
            .append("&plot=short")
        if (!year.isNullOrBlank()) qs.append("&y=").append(URLEncoder.encode(year, "UTF-8"))

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://www.omdbapi.com/$qs").openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 12000
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            countCall(ctx)
            if (isQuotaError(body)) return null // never cache this
            DbHelper.get(ctx).putOmdb(key, body)
            parse(body)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun isQuotaError(body: String): Boolean {
        return try {
            val o = JSONObject(body)
            o.optString("Response") == "False" &&
                o.optString("Error").contains("limit", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** True when a background lookup may spend from today's budget. */
    private fun checkBudget(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("kasidtv", Context.MODE_PRIVATE)
        val day = today()
        if (p.getString("omdb_day", "") != day) {
            p.edit().putString("omdb_day", day).putInt("omdb_count", 0).apply()
            return true
        }
        return p.getInt("omdb_count", 0) < DAILY_CARD_BUDGET
    }

    private fun countCall(ctx: Context) {
        val p = ctx.getSharedPreferences("kasidtv", Context.MODE_PRIVATE)
        val day = today()
        val count = if (p.getString("omdb_day", "") == day) p.getInt("omdb_count", 0) else 0
        p.edit().putString("omdb_day", day).putInt("omdb_count", count + 1).apply()
    }

    private fun parse(body: String): OmdbMeta? {
        return try {
            val o = JSONObject(body)
            if (o.optString("Response") == "False") return null
            var rt: String? = null
            val ratings = o.optJSONArray("Ratings")
            if (ratings != null) {
                for (i in 0 until ratings.length()) {
                    val r = ratings.getJSONObject(i)
                    if (r.optString("Source") == "Rotten Tomatoes") rt = r.optString("Value")
                }
            }
            fun na(s: String?): String? = if (s.isNullOrBlank() || s == "N/A") null else s
            OmdbMeta(
                title = o.optString("Title"),
                year = na(o.optString("Year")),
                rated = na(o.optString("Rated")),
                plot = na(o.optString("Plot")),
                poster = na(o.optString("Poster")),
                imdb = na(o.optString("imdbRating")),
                rt = rt,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** One-line meta for cards: "★ 8.8 · 87% · PG-13 · 2023". */
    fun metaLine(m: OmdbMeta?): String? {
        if (m == null) return null
        val parts = ArrayList<String>()
        m.imdb?.let { parts.add("★ $it") }
        m.rt?.let { parts.add(it) }
        m.rated?.let { parts.add(it) }
        m.year?.let { parts.add(it) }
        return parts.joinToString(" · ").ifEmpty { null }
    }
}
