package com.kasidwala.kasidtv.stalker

/** A VOD or series category from the portal. */
data class VodCategory(val id: String, val title: String)

/** One movie or series entry from get_ordered_list. */
data class VodItem(
    val id: String,
    val name: String,
    val oName: String,
    val screenshot: String?,
    val cmd: String?,
    val isSeries: Boolean,
    val rating: String?,
)

/** A page of VOD/series results. */
data class VodPage(
    val items: List<VodItem>,
    val totalItems: Int,
    val maxPageItems: Int,
    val curPage: Int,
)

/** A season of a series. */
data class Season(val id: String, val name: String)

/** One episode. `number` feeds the `series=` param of create_link. */
data class Episode(
    val id: String,
    val name: String,
    val cmd: String?,
    val number: String,
)
