package com.kasidwala.kasidtv.stalker

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Stalker portal client backed by Lumora's proven StalkerApiService.
 * (Derived from https://github.com/LumoraPlayer/Lumora under PolyForm Noncommercial 1.0.0.)
 *
 * Keeps the same public interface as the old hand-rolled client so the UI
 * layer is untouched; all network/auth logic delegates to LumoraStalkerApi.
 */
class StalkerClient(
    private val portalUrl: String,
    private val mac: String,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val api = LumoraStalkerApi(httpClient)

    private var authed = false

    // Cache for series seasons (Lumora returns all seasons+episodes in one call).
    private var seasonsCache: Map<String, List<LumoraStalkerApi.StalkerSeason>> = emptyMap()

    /** Convert Lumora's ChannelEntry to our VodItem. */
    private fun toVodItem(e: LumoraStalkerApi.ChannelEntry, isSeries: Boolean): VodItem =
        VodItem(
            id = e.id,
            name = e.name,
            oName = e.name,
            screenshot = e.poster ?: e.logo,
            cmd = e.rawCmd,
            isSeries = isSeries,
            rating = e.rating,
        )

    // ------------------------------------------------------------------ auth

    /** Handshake + full auth against the portal. */
    fun handshake(): Boolean {
        return try {
            val result = runBlocking {
                api.authenticate(portalUrl.trimEnd('/'), mac.uppercase())
            }
            authed = result.getOrNull()?.success == true
            authed
        } catch (e: Exception) {
            false
        }
    }

    fun ensureAuth(): Boolean = authed || handshake()

    // ------------------------------------------------------------------ vod

    fun getVodCategories(): List<VodCategory> {
        if (!ensureAuth()) return emptyList()
        return try {
            val token = api.currentToken() ?: return emptyList()
            val cats = runBlocking {
                api.fetchCategories("vod", mac.uppercase(), token)
            }
            cats.map { (id, title) -> VodCategory(id, title) }
                .filter { it.id != "*" }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getVodList(categoryId: String, page: Int, search: String? = null): VodPage {
        if (!ensureAuth()) return VodPage(emptyList(), 0, 0, page)
        return try {
            val (entries, total) = runBlocking {
                api.getPagedList("vod", categoryId, page, mac.uppercase())
            }
            // Client-side search filter if requested.
            val filtered = if (!search.isNullOrBlank()) {
                entries.filter { it.name.contains(search, ignoreCase = true) }
            } else entries
            VodPage(
                items = filtered.map { toVodItem(it, isSeries = false) },
                totalItems = total,
                maxPageItems = 14,
                curPage = page,
            )
        } catch (e: Exception) {
            VodPage(emptyList(), 0, 0, page)
        }
    }

    // ------------------------------------------------------------------ series

    fun getSeriesCategories(): List<VodCategory> {
        if (!ensureAuth()) return emptyList()
        return try {
            val token = api.currentToken() ?: return emptyList()
            val cats = runBlocking {
                api.fetchCategories("series", mac.uppercase(), token)
            }
            cats.map { (id, title) -> VodCategory(id, title) }
                .filter { it.id != "*" }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getSeriesList(categoryId: String, page: Int, search: String? = null): VodPage {
        if (!ensureAuth()) return VodPage(emptyList(), 0, 0, page)
        return try {
            val (entries, total) = runBlocking {
                api.getPagedList("series", categoryId, page, mac.uppercase())
            }
            val filtered = if (!search.isNullOrBlank()) {
                entries.filter { it.name.contains(search, ignoreCase = true) }
            } else entries
            VodPage(
                items = filtered.map { toVodItem(it, isSeries = true) },
                totalItems = total,
                maxPageItems = 14,
                curPage = page,
            )
        } catch (e: Exception) {
            VodPage(emptyList(), 0, 0, page)
        }
    }

    fun getSeasons(seriesId: String): List<Season> {
        if (!ensureAuth()) return emptyList()
        return try {
            val seasons = runBlocking {
                api.getSeriesEpisodes(seriesId, null, mac.uppercase())
            }
            seasonsCache = seasonsCache + (seriesId to seasons)
            seasons.map { Season(id = it.label, name = it.label) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getEpisodes(seriesId: String, seasonId: String): List<Episode> {
        val seasons = seasonsCache[seriesId] ?: runBlocking {
            try {
                api.getSeriesEpisodes(seriesId, null, mac.uppercase())
            } catch (e: Exception) {
                emptyList()
            }
        }.also { seasonsCache = seasonsCache + (seriesId to it) }

        val season = seasons.find { it.label == seasonId } ?: return emptyList()
        return season.numbers.map { num ->
            Episode(
                id = "${seriesId}_${seasonId}_$num",
                name = "Episode $num",
                cmd = season.cmd,
                number = num.toString(),
            )
        }
    }

    // ------------------------------------------------------------------ playback

    /**
     * Resolve a play command to a stream URL. [cmd] is the raw cmd from the
     * listing; [series] is the episode number for series episodes.
     */
    fun createLink(cmd: String, series: String = ""): String? {
        if (!ensureAuth()) return null
        return try {
            val epNum = series.toIntOrNull()
            runBlocking {
                api.createLink(cmd, epNum)
            }
        } catch (e: Exception) {
            null
        }
    }
}
