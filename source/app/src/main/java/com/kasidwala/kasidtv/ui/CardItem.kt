package com.kasidwala.kasidtv.ui

import com.kasidwala.kasidtv.stalker.VodItem

/** Build a browse card from a Stalker VOD/series item. */
fun vodCard(item: VodItem, seriesMode: Boolean): CardItem = CardItem(
    key = (if (seriesMode) "s:" else "m:") + item.id,
    title = item.name.ifEmpty { item.oName },
    posterUrl = item.screenshot,
    meta = item.rating?.let { "★ $it" },
    stalkerId = item.id,
    kind = if (seriesMode) CardKind.SERIES else CardKind.MOVIE,
    cmd = item.cmd,
)

/** What a card represents. */
enum class CardKind { MOVIE, SERIES, HISTORY }

/**
 * One browsable card. For [CardKind.HISTORY] the hist* fields carry the
 * payload needed to resume (movie) or reopen the series at the episode.
 */
data class CardItem(
    val key: String,
    val title: String,
    var posterUrl: String?,
    var meta: String?,
    val stalkerId: String,
    val kind: CardKind,
    val cmd: String?,
    var progress: Float = -1f, // 0..1, -1 = no bar
    val histKind: String? = null, // "movie" | "episode"
    val histKey: String? = null,
    val seriesTitle: String? = null,
    val seasonId: String? = null,
    val seasonName: String? = null,
    val episodeId: String? = null,
    val episodeNum: String? = null,
    val episodeCmd: String? = null,
)
