package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktCalendarShowItemDto(
    @Json(name = "first_aired") val firstAired: String? = null,
    @Json(name = "episode") val episode: TraktEpisodeDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCalendarMovieItemDto(
    @Json(name = "released") val released: String? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null
)
