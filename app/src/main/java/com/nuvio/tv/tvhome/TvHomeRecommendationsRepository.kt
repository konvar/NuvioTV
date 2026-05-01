package com.nuvio.tv.tvhome

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.data.repository.UpcomingEntry
import com.nuvio.tv.data.repository.UpcomingRepository
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

interface TvHomeRecommendationsRepository {
    fun observeChannelItems(kind: TvHomeChannelKind): Flow<List<TvHomeItem>>
    fun observeWatchNextItems(): Flow<List<TvHomeItem>>
    suspend fun refreshNow(force: Boolean = false)
}

@Singleton
class TvHomeRecommendationsRepositoryImpl @Inject constructor(
    private val watchProgressRepository: WatchProgressRepository,
    private val upcomingRepository: UpcomingRepository,
    private val traktProgressService: TraktProgressService,
    private val traktLibraryService: TraktLibraryService,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) : TvHomeRecommendationsRepository {

    private data class TraktLibraryRows(
        val movies: List<TvHomeItem> = emptyList(),
        val shows: List<TvHomeItem> = emptyList()
    )

    private val fallbackMoviesState = MutableStateFlow<List<TvHomeItem>>(emptyList())
    private val fallbackShowsState = MutableStateFlow<List<TvHomeItem>>(emptyList())
    private val refreshMutex = Mutex()
    private var fallbackRefreshedAtMs: Long = 0L

    private val continueWatchingFlow = watchProgressRepository.continueWatching
        .map { items ->
            items.sortedByDescending { it.lastWatched }
                .map(::mapContinueWatchingItem)
                .take(MAX_ITEMS)
        }

    private val upcomingFlow = upcomingRepository.observeSections()
        .map { sections ->
            sections
                .flatMap { it.items }
                .map(::mapUpcomingItem)
                .dedupeByProvider()
                .take(MAX_ITEMS)
        }

    private val traktLibraryRowsFlow = traktLibraryService.observeAllItems()
        .map(::mapTraktLibraryRows)

    private val upNextFlow = traktProgressService.observeUpNext()
        .map { items ->
            items.map(::mapUpNextItem)
                .dedupeByProvider()
                .take(MAX_ITEMS)
        }

    override fun observeChannelItems(kind: TvHomeChannelKind): Flow<List<TvHomeItem>> {
        return when (kind) {
            TvHomeChannelKind.CONTINUE_WATCHING -> combine(
                continueWatchingFlow,
                upNextFlow,
                upcomingFlow
            ) { continueWatching, upNext, upcoming ->
                buildContinueWatchingChannelItems(
                    continueWatching = continueWatching,
                    upNext = upNext,
                    upcoming = upcoming
                ).take(MAX_ITEMS)
            }
            TvHomeChannelKind.UPCOMING -> upcomingFlow
            TvHomeChannelKind.MOVIES -> combine(traktLibraryRowsFlow, fallbackMoviesState) { trakt, fallback ->
                (if (trakt.movies.isNotEmpty()) trakt.movies else fallback).take(MAX_ITEMS)
            }
            TvHomeChannelKind.SHOWS -> combine(traktLibraryRowsFlow, fallbackShowsState) { trakt, fallback ->
                (if (trakt.shows.isNotEmpty()) trakt.shows else fallback).take(MAX_ITEMS)
            }
            TvHomeChannelKind.NEW_EPISODES -> combine(upNextFlow, upcomingFlow) { upNext, upcoming ->
                buildNewEpisodesChannel(upNext = upNext, upcoming = upcoming).take(MAX_ITEMS)
            }
        }
    }

    override fun observeWatchNextItems(): Flow<List<TvHomeItem>> {
        return combine(continueWatchingFlow, upNextFlow, upcomingFlow) { continueWatching, upNext, upcoming ->
            buildWatchNextItems(
                continueWatching = continueWatching,
                upNext = upNext,
                upcoming = upcoming
            ).take(MAX_ITEMS)
        }
    }

    override suspend fun refreshNow(force: Boolean) {
        runCatching { upcomingRepository.refreshNow(force = force) }
        runCatching { traktLibraryService.refreshNow() }
        runCatching { traktProgressService.refreshNow() }

        val now = System.currentTimeMillis()
        refreshMutex.withLock {
            if (!force && now - fallbackRefreshedAtMs < FALLBACK_CACHE_TTL_MS) {
                return
            }

            fallbackMoviesState.value = fetchFallbackCatalogItems(
                kind = TvHomeChannelKind.MOVIES,
                catalogMatcher = { descriptor ->
                    descriptor.apiType.equals("movie", ignoreCase = true)
                }
            )
            fallbackShowsState.value = fetchFallbackCatalogItems(
                kind = TvHomeChannelKind.SHOWS,
                catalogMatcher = { descriptor ->
                    descriptor.apiType.equals("series", ignoreCase = true) ||
                        descriptor.apiType.equals("tv", ignoreCase = true)
                }
            )
            fallbackRefreshedAtMs = now
        }
    }

    private fun mapTraktLibraryRows(entries: List<LibraryEntry>): TraktLibraryRows {
        val relevant = entries.filter { entry ->
            entry.listKeys.contains(TraktLibraryService.FAVORITES_KEY) ||
                entry.listKeys.contains(TraktLibraryService.WATCHLIST_KEY)
        }

        val comparator = compareBy<LibraryEntry> { entry ->
            when {
                entry.listKeys.contains(TraktLibraryService.FAVORITES_KEY) -> 0
                entry.listKeys.contains(TraktLibraryService.WATCHLIST_KEY) -> 1
                else -> 2
            }
        }.thenBy { it.traktRank ?: Int.MAX_VALUE }
            .thenByDescending { it.listedAt }

        val movies = relevant.asSequence()
            .filter { it.type.equals("movie", ignoreCase = true) }
            .sortedWith(comparator)
            .map { mapLibraryItem(TvHomeChannelKind.MOVIES, it) }
            .distinctBy { "${it.normalizedContentType}:${it.contentId}" }
            .take(MAX_ITEMS)
            .toList()

        val shows = relevant.asSequence()
            .filter { !it.type.equals("movie", ignoreCase = true) }
            .sortedWith(comparator)
            .map { mapLibraryItem(TvHomeChannelKind.SHOWS, it) }
            .distinctBy { "${it.normalizedContentType}:${it.contentId}" }
            .take(MAX_ITEMS)
            .toList()

        return TraktLibraryRows(
            movies = movies,
            shows = shows
        )
    }

    private fun buildNewEpisodesChannel(
        upNext: List<TvHomeItem>,
        upcoming: List<TvHomeItem>
    ): List<TvHomeItem> {
        val rows = mutableListOf<TvHomeItem>()
        val seenShowIds = mutableSetOf<String>()

        upNext.asSequence()
            .filter { it.isSeries }
            .sortedBy { it.releaseTimeMs ?: Long.MAX_VALUE }
            .forEach { item ->
                if (seenShowIds.add(item.contentId)) {
                    rows += item.copy(providerId = "new_episodes|${item.providerId}")
                }
            }

        upcoming.asSequence()
            .filter { it.isSeries }
            .sortedBy { it.releaseTimeMs ?: Long.MAX_VALUE }
            .forEach { item ->
                if (seenShowIds.add(item.contentId)) {
                    rows += item.copy(providerId = "new_episodes|${item.providerId}")
                }
            }

        return rows.dedupeByProvider()
    }

    private fun buildWatchNextItems(
        continueWatching: List<TvHomeItem>,
        upNext: List<TvHomeItem>,
        upcoming: List<TvHomeItem>
    ): List<TvHomeItem> {
        val result = mutableListOf<TvHomeItem>()
        val activeSeriesIds = mutableSetOf<String>()

        continueWatching.asSequence()
            .sortedByDescending { it.lastEngagementTimeMs ?: 0L }
            .forEach { item ->
                if (item.isMovie) {
                    result += item.copy(providerId = "watchnext|continue|movie|${item.contentId}")
                    return@forEach
                }
                if (activeSeriesIds.add(item.contentId)) {
                    result += item.copy(
                        providerId = "watchnext|continue|series|${item.contentId}",
                        watchNextType = TvHomeWatchNextType.CONTINUE
                    )
                }
            }

        upcoming.asSequence()
            .filter { it.isSeries && it.releaseTimeMs != null && it.releaseTimeMs <= System.currentTimeMillis() }
            .sortedByDescending { it.releaseTimeMs ?: 0L }
            .forEach { item ->
                if (item.contentId in activeSeriesIds) return@forEach
                activeSeriesIds += item.contentId

                result += item.copy(
                    providerId = "watchnext|new|${item.providerId}",
                    watchNextType = TvHomeWatchNextType.NEW,
                    lastEngagementTimeMs = item.releaseTimeMs
                )
            }

        upNext.asSequence()
            .sortedBy { it.releaseTimeMs ?: Long.MAX_VALUE }
            .forEach { item ->
                if (!item.isSeries || item.videoId.isNullOrBlank()) return@forEach
                if (item.contentId in activeSeriesIds) return@forEach
                activeSeriesIds += item.contentId

                val watchNextType = resolveUpNextWatchNextType(item)
                result += item.copy(
                    providerId = "watchnext|${watchNextType.name.lowercase()}|series|${item.contentId}",
                    watchNextType = watchNextType
                )
            }

        return result.dedupeByProvider()
    }

    private fun buildContinueWatchingChannelItems(
        continueWatching: List<TvHomeItem>,
        upNext: List<TvHomeItem>,
        upcoming: List<TvHomeItem>
    ): List<TvHomeItem> {
        val result = mutableListOf<TvHomeItem>()
        val seenEpisodeKeys = mutableSetOf<String>()
        val now = System.currentTimeMillis()

        fun add(item: TvHomeItem, providerPrefix: String): Boolean {
            val key = listOf(
                item.normalizedContentType,
                item.contentId,
                item.season?.toString().orEmpty(),
                item.episode?.toString().orEmpty(),
                item.videoId.orEmpty()
            ).joinToString("|")
            if (!seenEpisodeKeys.add(key)) return false
            result += item.copy(providerId = "$providerPrefix|${item.providerId}")
            return true
        }

        upcoming.asSequence()
            .filter { it.isSeries && it.releaseTimeMs != null && it.releaseTimeMs <= now }
            .sortedByDescending { it.releaseTimeMs ?: 0L }
            .forEach { add(it, "continue_channel|new") }

        continueWatching.asSequence()
            .sortedByDescending { it.lastEngagementTimeMs ?: 0L }
            .forEach { add(it, "continue_channel|resume") }

        upNext.asSequence()
            .sortedBy { it.releaseTimeMs ?: Long.MAX_VALUE }
            .forEach { add(it, "continue_channel|next") }

        return result.dedupeByProvider()
    }

    private fun resolveUpNextWatchNextType(item: TvHomeItem): TvHomeWatchNextType {
        val releasedAt = item.releaseTimeMs ?: return TvHomeWatchNextType.NEXT
        val lastWatched = item.lastEngagementTimeMs ?: return TvHomeWatchNextType.NEXT
        val now = System.currentTimeMillis()
        val releasedRecently = releasedAt <= now && now - releasedAt <= NEW_EPISODE_WINDOW_MS
        return if (releasedRecently && lastWatched < releasedAt) {
            TvHomeWatchNextType.NEW
        } else {
            TvHomeWatchNextType.NEXT
        }
    }

    private suspend fun fetchFallbackCatalogItems(
        kind: TvHomeChannelKind,
        catalogMatcher: (CatalogDescriptor) -> Boolean
    ): List<TvHomeItem> {
        val addons = addonRepository.getInstalledAddons().first()
        val results = mutableListOf<TvHomeItem>()

        addonLoop@ for (addon in addons) {
            for (catalog in addon.catalogs) {
                if (!catalog.shouldShowOnHomeCompat()) continue
                if (!catalogMatcher(catalog)) continue

                val row = withTimeoutOrNull(CATALOG_FETCH_TIMEOUT_MS) {
                    catalogRepository.getCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        supportsSkip = false
                    ).first { result -> result !is NetworkResult.Loading }
                } as? NetworkResult.Success ?: continue

                val mapped = row.data.items
                    .map { mapCatalogItem(kind, addon.baseUrl, it) }
                    .filter { it.posterUri != null || it.thumbnailUri != null }
                    .distinctBy { "${it.normalizedContentType}:${it.contentId}" }

                results += mapped
                if (results.size >= MAX_ITEMS) {
                    break@addonLoop
                }
            }
        }

        if (results.isEmpty()) {
            Log.d(TAG, "No fallback catalog items resolved for ${kind.prefKey}")
        }

        return results
            .distinctBy { "${it.normalizedContentType}:${it.contentId}" }
            .take(MAX_ITEMS)
    }

    private fun mapContinueWatchingItem(progress: WatchProgress): TvHomeItem {
        val episodeLabel = if (progress.season != null && progress.episode != null) {
            "S${progress.season}E${progress.episode}"
        } else {
            null
        }
        val subtitle = episodeLabel?.let { label ->
            progress.episodeTitle?.takeIf { it.isNotBlank() }?.let { title ->
                "$label - $title"
            } ?: label
        } ?: "Resume playback"
        val percent = (progress.progressPercentage * 100f).toInt().coerceIn(0, 100)
        val description = "$subtitle - $percent% watched"

        return TvHomeItem(
            providerId = buildProgressProviderId("continue", progress),
            contentId = progress.contentId,
            contentType = progress.contentType,
            videoId = progress.videoId,
            title = progress.name,
            subtitle = subtitle,
            description = description,
            posterUri = progress.poster,
            thumbnailUri = progress.backdrop ?: progress.poster,
            logoUri = progress.logo,
            season = progress.season,
            episode = progress.episode,
            episodeTitle = progress.episodeTitle,
            lastEngagementTimeMs = progress.lastWatched,
            playbackPositionMs = progress.position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            durationMs = progress.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            addonBaseUrl = progress.addonBaseUrl,
            watchNextType = TvHomeWatchNextType.CONTINUE
        )
    }

    private fun mapUpcomingItem(entry: UpcomingEntry): TvHomeItem {
        return TvHomeItem(
            providerId = "upcoming|${entry.key}",
            contentId = entry.contentId,
            contentType = entry.contentType,
            title = entry.name,
            subtitle = entry.releaseInfo ?: entry.episodeTitle,
            description = entry.description ?: entry.releaseInfo ?: entry.name,
            posterUri = entry.poster,
            thumbnailUri = entry.backdrop ?: entry.poster,
            logoUri = entry.logo,
            season = entry.season,
            episode = entry.episode,
            episodeTitle = entry.episodeTitle,
            releaseTimeMs = entry.releaseTimeMs,
            genres = entry.genres,
            imdbRating = entry.imdbRating,
            yearText = extractYear(entry.releaseInfo)
        )
    }

    private fun mapUpNextItem(entry: TraktProgressService.TraktUpNextEntry): TvHomeItem {
        val releasedTimeMs = parseReleaseTimeMs(entry.released)
        val subtitle = buildString {
            append("S")
            append(entry.season)
            append("E")
            append(entry.episode)
            entry.episodeTitle?.takeIf { it.isNotBlank() }?.let {
                append(" • ")
                append(it)
            }
        }
        return TvHomeItem(
            providerId = "upnext|${entry.contentId}|${entry.season}|${entry.episode}",
            contentId = entry.contentId,
            contentType = "series",
            videoId = entry.videoId,
            title = entry.name,
            subtitle = subtitle,
            description = entry.episodeDescription ?: entry.releaseInfo ?: subtitle,
            posterUri = entry.poster,
            thumbnailUri = entry.thumbnail ?: entry.backdrop ?: entry.poster,
            logoUri = entry.logo,
            season = entry.season,
            episode = entry.episode,
            episodeTitle = entry.episodeTitle,
            releaseTimeMs = releasedTimeMs,
            lastEngagementTimeMs = entry.lastWatched,
            genres = entry.genres,
            imdbRating = entry.imdbRating,
            watchNextType = TvHomeWatchNextType.NEXT
        )
    }

    private fun mapLibraryItem(kind: TvHomeChannelKind, entry: LibraryEntry): TvHomeItem {
        return TvHomeItem(
            providerId = "${kind.prefKey}|${entry.type}|${entry.id}",
            contentId = entry.id,
            contentType = entry.type,
            title = entry.name,
            subtitle = entry.releaseInfo,
            description = entry.description ?: entry.releaseInfo ?: entry.name,
            posterUri = entry.poster,
            thumbnailUri = entry.background ?: entry.poster,
            logoUri = entry.logo,
            lastEngagementTimeMs = entry.listedAt,
            genres = entry.genres,
            imdbRating = entry.imdbRating,
            yearText = extractYear(entry.releaseInfo),
            addonBaseUrl = entry.addonBaseUrl
        )
    }

    private fun mapCatalogItem(
        kind: TvHomeChannelKind,
        addonBaseUrl: String,
        item: MetaPreview
    ): TvHomeItem {
        return TvHomeItem(
            providerId = "${kind.prefKey}|${item.apiType}|${item.id}",
            contentId = item.id,
            contentType = item.apiType,
            title = item.name,
            subtitle = item.releaseInfo,
            description = item.description ?: item.releaseInfo ?: item.name,
            posterUri = item.poster,
            thumbnailUri = item.landscapePoster ?: item.background ?: item.poster,
            logoUri = item.logo,
            releaseTimeMs = parseReleaseTimeMs(item.released),
            genres = item.genres,
            imdbRating = item.imdbRating,
            yearText = extractYear(item.releaseInfo),
            addonBaseUrl = addonBaseUrl
        )
    }

    private fun buildProgressProviderId(prefix: String, progress: WatchProgress): String {
        return listOf(
            prefix,
            progress.contentType,
            progress.contentId,
            progress.season?.toString().orEmpty(),
            progress.episode?.toString().orEmpty(),
            progress.videoId
        ).joinToString("|")
    }

    private fun List<TvHomeItem>.dedupeByProvider(): List<TvHomeItem> {
        return distinctBy { it.providerId }
    }

    private fun CatalogDescriptor.shouldShowOnHomeCompat(): Boolean {
        if (extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired }) {
            return false
        }
        return !hasExplicitShowInHome || showInHome
    }

    private fun parseReleaseTimeMs(released: String?): Long? {
        val value = released?.trim().orEmpty()
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrElse {
            runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
                .getOrNull()
        }
    }

    private fun extractYear(releaseInfo: String?): String? {
        if (releaseInfo.isNullOrBlank()) return null
        return YEAR_REGEX.find(releaseInfo)?.value
    }

    private companion object {
        const val TAG = "TvHomeRecommendations"
        const val MAX_ITEMS = 20
        const val FALLBACK_CACHE_TTL_MS = 30 * 60_000L
        const val CATALOG_FETCH_TIMEOUT_MS = 4_000L
        const val NEW_EPISODE_WINDOW_MS = 14L * 24L * 60L * 60L * 1000L
        val YEAR_REGEX = Regex("\\b(19|20)\\d{2}\\b")
    }
}
