package com.nuvio.tv.tvhome

import androidx.tvprovider.media.tv.TvContractCompat

enum class TvHomeChannelKind(
    val prefKey: String,
    val displayName: String,
    val description: String,
    val browsePath: String,
    val isDefaultChannel: Boolean = false
) {
    CONTINUE_WATCHING(
        prefKey = "continue_watching",
        displayName = "Continue Watching",
        description = "Resume recently watched titles",
        browsePath = "home",
        isDefaultChannel = true
    ),
    UPCOMING(
        prefKey = "upcoming",
        displayName = "Upcoming",
        description = "New episodes and upcoming releases",
        browsePath = "upcoming"
    ),
    MOVIES(
        prefKey = "movies",
        displayName = "Movies",
        description = "Movies from your Trakt favorites and watchlist",
        browsePath = "home"
    ),
    SHOWS(
        prefKey = "shows",
        displayName = "Shows",
        description = "Shows from your Trakt favorites and watchlist",
        browsePath = "home"
    ),
    NEW_EPISODES(
        prefKey = "new_episodes",
        displayName = "New Episodes",
        description = "Next and newly released episodes to watch",
        browsePath = "upcoming"
    );
}

enum class TvHomeWatchNextType(val providerValue: Int) {
    CONTINUE(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE),
    NEXT(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT),
    NEW(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEW);
}

data class TvHomeItem(
    val providerId: String,
    val contentId: String,
    val contentType: String,
    val videoId: String? = null,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val posterUri: String? = null,
    val thumbnailUri: String? = null,
    val logoUri: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val releaseTimeMs: Long? = null,
    val lastEngagementTimeMs: Long? = null,
    val playbackPositionMs: Int? = null,
    val durationMs: Int? = null,
    val watchNextType: TvHomeWatchNextType? = null,
    val genres: List<String> = emptyList(),
    val imdbRating: Float? = null,
    val yearText: String? = null,
    val addonBaseUrl: String? = null
) {
    val normalizedContentType: String
        get() = when (contentType.lowercase()) {
            "movie" -> "movie"
            "tv", "show", "series" -> "series"
            else -> contentType.lowercase()
        }

    val isMovie: Boolean
        get() = normalizedContentType == "movie"

    val isEpisode: Boolean
        get() = !isMovie && season != null && episode != null

    val isSeries: Boolean
        get() = !isMovie
}
