package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktCalendarShowItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktImagesDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class UpcomingEntry(
    val key: String,
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val genres: List<String>,
    val releasedAt: String?,
    val releaseTimeMs: Long,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val isNewEpisode: Boolean = false,
    val isPremiere: Boolean = false,
    val source: String
) {
    val displayTitle: String
        get() = if (season != null && episode != null) {
            "$name • S${season}E${episode}"
        } else {
            name
        }

    fun toMetaPreview(): MetaPreview {
        return MetaPreview(
            id = contentId,
            type = if (contentType.equals("movie", ignoreCase = true)) ContentType.MOVIE else ContentType.SERIES,
            rawType = contentType,
            name = displayTitle,
            poster = poster,
            posterShape = PosterShape.LANDSCAPE,
            background = backdrop,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            released = releasedAt
        )
    }
}

data class UpcomingSection(
    val key: String,
    val title: String,
    val items: List<UpcomingEntry>
)

const val UPCOMING_HOME_ADDON_ID = "trakt_upcoming"
const val UPCOMING_HOME_CATALOG_ID = "upcoming"
const val UPCOMING_HOME_RAW_TYPE = "series"

interface UpcomingRepository {
    fun observeSections(): Flow<List<UpcomingSection>>
    suspend fun refreshNow(force: Boolean = false)
}

@Singleton
class UpcomingRepositoryImpl @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository
) : UpcomingRepository {

    private val sectionsState = MutableStateFlow<List<UpcomingSection>?>(null)
    private val refreshMutex = Mutex()
    private var lastRefreshMs: Long = 0L

    override fun observeSections(): Flow<List<UpcomingSection>> {
        return sectionsState
            .filterNotNull()
            .onStart {
            if (sectionsState.value == null) {
                refreshNow(force = false)
            }
        }
    }

    override suspend fun refreshNow(force: Boolean) {
        val now = System.currentTimeMillis()
        refreshMutex.withLock {
            if (!force && sectionsState.value != null && now - lastRefreshMs < CACHE_TTL_MS) {
                return
            }
            sectionsState.value = if (traktAuthService.getCurrentAuthState().isAuthenticated) {
                fetchTraktUpcomingSections().ifEmpty {
                    fetchFallbackUpcomingSections()
                }
            } else {
                fetchFallbackUpcomingSections()
            }
            lastRefreshMs = now
        }
    }

    private suspend fun fetchTraktUpcomingSections(): List<UpcomingSection> {
        val startDate = LocalDate.now(ZoneOffset.UTC).toString()
        val calendarItems = mutableListOf<TraktCalendarShowItemDto>()

        val showsResponse = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getCalendarShows(
                authorization = authHeader,
                target = "my",
                startDate = startDate,
                days = UPCOMING_DAYS,
                extended = "full,images"
            )
        }
        if (showsResponse?.isSuccessful == true) {
            calendarItems += showsResponse.body().orEmpty()
        }

        val newShowsResponse = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getCalendarNewShows(
                authorization = authHeader,
                target = "my",
                startDate = startDate,
                days = UPCOMING_DAYS,
                extended = "full,images"
            )
        }
        val newEpisodeKeys = if (newShowsResponse?.isSuccessful == true) {
            newShowsResponse.body().orEmpty()
                .mapNotNull { dto ->
                    val showId = normalizeContentId(dto.show?.ids).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val season = dto.episode?.season ?: return@mapNotNull null
                    val episode = dto.episode?.number ?: return@mapNotNull null
                    "$showId|$season|$episode"
                }
                .toSet()
        } else {
            emptySet()
        }

        val entries = calendarItems
            .mapNotNull { dto ->
                dto.toUpcomingEntry(
                    isNewEpisode = dto.calendarKey() in newEpisodeKeys,
                    isPremiere = dto.episode?.season == 1 && dto.episode?.number == 1
                )
            }
            .sortedBy { it.releaseTimeMs }
            .distinctBy { it.contentId }
            .map { enrichUpcomingEntry(it) }

        return bucketUpcoming(entries)
    }

    private suspend fun fetchFallbackUpcomingSections(): List<UpcomingSection> {
        val seeds = watchProgressRepository.observeNextUpSeeds().first()
        val entries = seeds
            .sortedByDescending { it.lastWatched }
            .distinctBy { canonicalLookupKey(it.contentId) }
            .mapNotNull { seed -> resolveFallbackUpcoming(seed.contentId, seed.season, seed.episode) }
            .sortedBy { it.releaseTimeMs }
        return bucketUpcoming(entries)
    }

    private suspend fun resolveFallbackUpcoming(
        contentId: String,
        seedSeason: Int?,
        seedEpisode: Int?
    ): UpcomingEntry? {
        val meta = loadSeriesMeta(contentId) ?: return null
        val today = LocalDate.now(ZoneOffset.UTC)
        val nextEpisode = meta.videos
            .asSequence()
            .filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }
            .sortedWith(compareBy<Video>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))
            .filter { video ->
                val season = video.season ?: return@filter false
                val episode = video.episode ?: return@filter false
                when {
                    seedSeason == null || seedEpisode == null -> true
                    season > seedSeason -> true
                    season == seedSeason && episode > seedEpisode -> true
                    else -> false
                }
            }
            .firstOrNull { video ->
                val releaseDate = parseIsoDate(video.released) ?: return@firstOrNull false
                !releaseDate.isBefore(today)
            } ?: return null

        val season = nextEpisode.season ?: return null
        val episode = nextEpisode.episode ?: return null
        return UpcomingEntry(
            key = "${meta.id}|$season|$episode",
            contentId = meta.id,
            contentType = meta.apiType,
            name = meta.name,
            poster = meta.poster,
            backdrop = meta.background ?: meta.landscapePoster ?: meta.poster,
            logo = meta.logo,
            description = nextEpisode.overview ?: meta.description,
            releaseInfo = buildEpisodeLabel(season, episode, nextEpisode.title),
            imdbRating = meta.imdbRating,
            genres = meta.genres,
            releasedAt = nextEpisode.released,
            releaseTimeMs = parseIsoMillis(nextEpisode.released),
            season = season,
            episode = episode,
            episodeTitle = nextEpisode.title,
            isNewEpisode = false,
            isPremiere = season == 1 && episode == 1,
            source = "local_fallback"
        )
    }

    private suspend fun enrichUpcomingEntry(entry: UpcomingEntry): UpcomingEntry {
        if (!entry.poster.isNullOrBlank()) {
            return entry
        }

        val metadata = withTimeoutOrNull(META_ENRICH_TIMEOUT_MS) {
            loadSeriesMeta(entry.contentId)
        } ?: return entry

        return entry.copy(
            poster = metadata.poster ?: metadata.landscapePoster ?: metadata.background ?: entry.poster,
            backdrop = entry.backdrop ?: metadata.background ?: metadata.landscapePoster ?: metadata.poster,
            logo = entry.logo ?: metadata.logo,
            description = entry.description ?: metadata.description,
            imdbRating = entry.imdbRating ?: metadata.imdbRating,
            genres = if (entry.genres.isNotEmpty()) entry.genres else metadata.genres,
            contentType = entry.contentType.ifBlank { metadata.apiType },
            name = entry.name.ifBlank { metadata.name }
        )
    }

    private suspend fun loadSeriesMeta(contentId: String): Meta? {
        val candidates = buildList {
            add(contentId)
            if (contentId.startsWith("tmdb:", ignoreCase = true)) add(contentId.substringAfter(':'))
            if (contentId.startsWith("trakt:", ignoreCase = true)) add(contentId.substringAfter(':'))
        }.distinct()

        for (type in listOf("series", "tv")) {
            for (candidate in candidates) {
                val result = metaRepository.getMetaFromAllAddons(type = type, id = candidate)
                    .first { it !is NetworkResult.Loading }
                val meta = (result as? NetworkResult.Success)?.data
                if (meta != null) return meta
            }
        }
        return null
    }

    private fun bucketUpcoming(items: List<UpcomingEntry>): List<UpcomingSection> {
        if (items.isEmpty()) return emptyList()
        return items
            .groupBy { Instant.ofEpochMilli(it.releaseTimeMs).atZone(ZoneOffset.UTC).toLocalDate() }
            .toSortedMap()
            .map { (date, groupedItems) ->
                UpcomingSection(
                    key = date.toString(),
                    title = formatSectionTitle(date),
                    items = groupedItems.sortedBy { it.releaseTimeMs }
                )
            }
    }

    private fun formatSectionTitle(date: LocalDate): String {
        val today = LocalDate.now(ZoneOffset.UTC)
        return when {
            date.isEqual(today) -> "Today • ${date.format(SECTION_DATE_FORMATTER)}"
            date.isEqual(today.plusDays(1)) -> "Tomorrow • ${date.format(SECTION_DATE_FORMATTER)}"
            else -> date.format(SECTION_TITLE_FORMATTER)
        }
    }

    private fun TraktCalendarShowItemDto.calendarKey(): String {
        val showId = normalizeContentId(show?.ids).takeIf { it.isNotBlank() } ?: return ""
        val season = episode?.season ?: return showId
        val number = episode?.number ?: return showId
        return "$showId|$season|$number"
    }

    private fun TraktCalendarShowItemDto.toUpcomingEntry(
        isNewEpisode: Boolean,
        isPremiere: Boolean
    ): UpcomingEntry? {
        val showDto = show ?: return null
        val episodeDto = episode ?: return null
        val contentId = normalizeContentId(showDto.ids).takeIf { it.isNotBlank() } ?: return null
        val season = episodeDto.season ?: return null
        val episodeNumber = episodeDto.number ?: return null

        return UpcomingEntry(
            key = "$contentId|$season|$episodeNumber",
            contentId = contentId,
            contentType = "series",
            name = showDto.title ?: contentId,
            poster = showDto.images.bestLandscapeImage(),
            backdrop = showDto.images.bestBackdropImage(),
            logo = showDto.images.bestLogoImage(),
            description = episodeDto.overview ?: showDto.overview,
            releaseInfo = buildEpisodeLabel(season, episodeNumber, episodeDto.title),
            imdbRating = showDto.rating?.toFloat(),
            genres = showDto.genres.orEmpty(),
            releasedAt = firstAired,
            releaseTimeMs = parseIsoMillis(firstAired),
            season = season,
            episode = episodeNumber,
            episodeTitle = episodeDto.title,
            isNewEpisode = isNewEpisode,
            isPremiere = isPremiere,
            source = "trakt_calendar"
        )
    }

    private fun buildEpisodeLabel(season: Int, episode: Int, title: String?): String {
        return buildString {
            append("S")
            append(season)
            append(" E")
            append(episode)
            title?.takeIf { it.isNotBlank() }?.let {
                append(" - ")
                append(it)
            }
        }
    }

    private fun parseIsoMillis(value: String?): Long {
        return runCatching { Instant.parse(value).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }
    }

    private fun parseIsoDate(value: String?): LocalDate? {
        return runCatching { Instant.parse(value).atZone(ZoneOffset.UTC).toLocalDate() }
            .getOrNull()
    }

    private fun TraktImagesDto?.bestLandscapeImage(): String? {
        if (this == null) return null
        return thumb.firstImageUrl()
            ?: fanart.firstImageUrl()
            ?: banner.firstImageUrl()
            ?: poster.firstImageUrl()
    }

    private fun TraktImagesDto?.bestBackdropImage(): String? {
        if (this == null) return null
        return fanart.firstImageUrl()
            ?: banner.firstImageUrl()
            ?: thumb.firstImageUrl()
            ?: poster.firstImageUrl()
    }

    private fun TraktImagesDto?.bestLogoImage(): String? {
        if (this == null) return null
        return logo.firstImageUrl() ?: clearart.firstImageUrl()
    }

    private fun List<String>?.firstImageUrl(): String? {
        return this.orEmpty()
            .firstOrNull { it.isNotBlank() }
            ?.toHttpsImageUrl()
    }

    private fun String.toHttpsImageUrl(): String {
        val normalized = trim()
        return when {
            normalized.startsWith("https://", ignoreCase = true) -> normalized
            normalized.startsWith("http://", ignoreCase = true) -> "https://${normalized.removePrefix("http://")}"
            normalized.startsWith("//") -> "https:$normalized"
            else -> "https://$normalized"
        }
    }

    private fun canonicalLookupKey(contentId: String): String {
        return normalizeContentId(toTraktIds(parseContentIds(contentId)), fallback = contentId)
    }

    private companion object {
        const val UPCOMING_DAYS = 14
        const val CACHE_TTL_MS = 30 * 60_000L
        const val META_ENRICH_TIMEOUT_MS = 2_000L
        val SECTION_TITLE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE • d MMM", Locale.getDefault())
        val SECTION_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    }
}
