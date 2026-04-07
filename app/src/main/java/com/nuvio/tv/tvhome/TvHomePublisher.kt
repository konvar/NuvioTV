package com.nuvio.tv.tvhome

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.nuvio.tv.MainActivity
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

interface TvHomePublisher {
    fun start()
    fun requestRefresh(force: Boolean = false)
    fun handleInitializePrograms()
    fun handlePreviewProgramBrowsableDisabled(programId: Long)
    fun handleWatchNextProgramBrowsableDisabled(programId: Long)
    fun handlePreviewProgramAddedToWatchNext(programId: Long)
}

@Singleton
class TvHomePublisherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recommendationsRepository: TvHomeRecommendationsRepository,
    private val stateStore: TvHomeStateStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : TvHomePublisher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val previewChannelHelper by lazy { PreviewChannelHelper(context) }
    private val appLogo by lazy { loadAppLogo() }
    @Volatile
    private var started = false

    override fun start() {
        if (started) return
        started = true
        if (!isSupported()) return

        TvHomeChannelKind.entries.forEach { kind ->
            scope.launch {
                combine(
                    layoutPreferenceDataStore.tvHomeEnabled,
                    channelEnabledFlow(kind),
                    recommendationsRepository.observeChannelItems(kind)
                ) { tvHomeEnabled, channelEnabled, items ->
                    Triple(tvHomeEnabled, channelEnabled, items)
                }.collectLatest { (tvHomeEnabled, channelEnabled, items) ->
                    if (!tvHomeEnabled || !channelEnabled) {
                        removeChannel(kind)
                    } else {
                        syncChannel(kind, items)
                    }
                }
            }
        }

        scope.launch {
            combine(
                layoutPreferenceDataStore.tvHomeEnabled,
                layoutPreferenceDataStore.tvHomeWatchNextEnabled,
                recommendationsRepository.observeWatchNextItems()
            ) { tvHomeEnabled, watchNextEnabled, items ->
                Triple(tvHomeEnabled, watchNextEnabled, items)
            }.collectLatest { (tvHomeEnabled, watchNextEnabled, items) ->
                if (!tvHomeEnabled || !watchNextEnabled) {
                    clearWatchNextPrograms()
                } else {
                    syncWatchNext(items)
                }
            }
        }

        requestRefresh(force = false)
    }

    override fun requestRefresh(force: Boolean) {
        if (!isSupported()) return
        scope.launch {
            recommendationsRepository.refreshNow(force = force)
        }
    }

    override fun handleInitializePrograms() {
        requestRefresh(force = true)
    }

    override fun handlePreviewProgramBrowsableDisabled(programId: Long) {
        TvHomeChannelKind.entries.forEach { kind ->
            val providerId = stateStore.findProgramProviderId(kind, programId) ?: return@forEach
            stateStore.markPreviewDismissed(kind, providerId)
            val remaining = stateStore.getProgramIds(kind).toMutableMap().also { it.remove(providerId) }
            stateStore.setProgramIds(kind, remaining)
            deletePreviewProgram(programId)
            return
        }
    }

    override fun handleWatchNextProgramBrowsableDisabled(programId: Long) {
        val providerId = stateStore.findWatchNextProviderId(programId) ?: return
        stateStore.markWatchNextDismissed(providerId)
        val remaining = stateStore.getWatchNextProgramIds().toMutableMap().also { it.remove(providerId) }
        stateStore.setWatchNextProgramIds(remaining)
        deleteWatchNextProgram(programId)
    }

    override fun handlePreviewProgramAddedToWatchNext(programId: Long) {
        TvHomeChannelKind.entries.forEach { kind ->
            val providerId = stateStore.findProgramProviderId(kind, programId) ?: return@forEach
            stateStore.clearWatchNextDismissed(providerId)
            return
        }
    }

    private fun channelEnabledFlow(kind: TvHomeChannelKind): Flow<Boolean> {
        return when (kind) {
            TvHomeChannelKind.CONTINUE_WATCHING -> layoutPreferenceDataStore.tvHomeContinueWatchingChannelEnabled
            TvHomeChannelKind.UPCOMING -> layoutPreferenceDataStore.tvHomeUpcomingChannelEnabled
            TvHomeChannelKind.MOVIES -> layoutPreferenceDataStore.tvHomeMoviesChannelEnabled
            TvHomeChannelKind.SHOWS -> layoutPreferenceDataStore.tvHomeShowsChannelEnabled
            TvHomeChannelKind.NEW_EPISODES -> layoutPreferenceDataStore.tvHomeNewEpisodesChannelEnabled
        }
    }

    private fun syncChannel(kind: TvHomeChannelKind, items: List<TvHomeItem>) {
        val channelId = ensureChannel(kind) ?: return
        val desiredItems = items
            .filterNot { stateStore.isPreviewDismissed(kind, it.providerId) }
            .take(MAX_PROGRAMS)

        val existingPrograms = stateStore.getProgramIds(kind).toMutableMap()
        val desiredProviderIds = desiredItems.map { it.providerId }.toSet()

        existingPrograms.entries
            .filter { it.key !in desiredProviderIds }
            .forEach { entry ->
                deletePreviewProgram(entry.value)
            }

        val nextProgramIds = linkedMapOf<String, Long>()
        desiredItems.forEachIndexed { index, item ->
            val existingProgramId = existingPrograms[item.providerId]
            val builder = buildPreviewProgram(channelId = channelId, item = item, weight = computeWeight(item, index))
            val programId = if (existingProgramId != null) {
                updatePreviewProgram(existingProgramId, builder)
            } else {
                publishPreviewProgram(builder)
            }
            if (programId != null) {
                nextProgramIds[item.providerId] = programId
                stateStore.clearPreviewDismissed(kind, item.providerId)
            }
        }

        stateStore.setProgramIds(kind, nextProgramIds)
    }

    private fun syncWatchNext(items: List<TvHomeItem>) {
        val desiredItems = items
            .filterNot { stateStore.isWatchNextDismissed(it.providerId) }
            .take(MAX_PROGRAMS)

        val existingPrograms = stateStore.getWatchNextProgramIds().toMutableMap()
        val desiredProviderIds = desiredItems.map { it.providerId }.toSet()

        existingPrograms.entries
            .filter { it.key !in desiredProviderIds }
            .forEach { entry ->
                deleteWatchNextProgram(entry.value)
            }

        val nextProgramIds = linkedMapOf<String, Long>()
        desiredItems.forEach { item ->
            val watchNextType = item.watchNextType ?: return@forEach
            val existingProgramId = existingPrograms[item.providerId]
            val builder = buildWatchNextProgram(item, watchNextType)
            val programId = if (existingProgramId != null) {
                updateWatchNextProgram(existingProgramId, builder)
            } else {
                publishWatchNextProgram(builder)
            }
            if (programId != null) {
                nextProgramIds[item.providerId] = programId
                stateStore.clearWatchNextDismissed(item.providerId)
            }
        }

        stateStore.setWatchNextProgramIds(nextProgramIds)
    }

    private fun ensureChannel(kind: TvHomeChannelKind): Long? {
        val existingId = stateStore.getChannelId(kind)
        if (existingId != null && previewChannelHelper.getPreviewChannel(existingId) != null) {
            updateChannel(existingId, kind)
            return existingId
        }

        if (existingId != null) {
            stateStore.setChannelId(kind, null)
        }

        val channel = buildChannel(kind)
        return runCatching {
            val shouldPublishAsDefault = kind.isDefaultChannel &&
                TvHomeChannelKind.entries.none { stateStore.getChannelId(it) != null }

            val channelId = if (shouldPublishAsDefault) {
                previewChannelHelper.publishDefaultChannel(channel)
            } else {
                previewChannelHelper.publishChannel(channel)
            }

            stateStore.setChannelId(kind, channelId)
            if (!kind.isDefaultChannel) {
                stateStore.enqueueBrowsableChannelId(channelId)
            }
            storeChannelLogo(channelId)
            channelId
        }.onFailure {
            Log.w(TAG, "Failed to publish ${kind.prefKey} channel: ${it.message}")
        }.getOrNull()
    }

    private fun updateChannel(channelId: Long, kind: TvHomeChannelKind) {
        runCatching {
            previewChannelHelper.updatePreviewChannel(channelId, buildChannel(kind))
            storeChannelLogo(channelId)
        }.onFailure {
            Log.w(TAG, "Failed to update ${kind.prefKey} channel: ${it.message}")
        }
    }

    private fun buildChannel(kind: TvHomeChannelKind): PreviewChannel {
        return PreviewChannel.Builder()
            .setDisplayName(kind.displayName)
            .setDescription(kind.description)
            .setAppLinkIntentUri(buildAppIntentUri(kind.browsePath))
            .setInternalProviderId(kind.prefKey)
            .apply {
                appLogo?.let(::setLogo)
            }
            .build()
    }

    private fun removeChannel(kind: TvHomeChannelKind) {
        stateStore.getProgramIds(kind).values.forEach(::deletePreviewProgram)
        stateStore.setProgramIds(kind, emptyMap())

        val channelId = stateStore.getChannelId(kind) ?: return
        runCatching {
            context.contentResolver.delete(
                ContentUris.withAppendedId(TvContractCompat.Channels.CONTENT_URI, channelId),
                null,
                null
            )
        }.onFailure {
            Log.w(TAG, "Failed to remove ${kind.prefKey} channel: ${it.message}")
        }
        stateStore.setChannelId(kind, null)
    }

    private fun clearWatchNextPrograms() {
        stateStore.getWatchNextProgramIds().values.forEach(::deleteWatchNextProgram)
        stateStore.setWatchNextProgramIds(emptyMap())
    }

    private fun buildPreviewProgram(
        channelId: Long,
        item: TvHomeItem,
        weight: Int
    ): PreviewProgram.Builder {
        return PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(resolvePreviewType(item))
            .setTitle(item.title)
            .setEpisodeTitle(item.episodeTitle)
            .setDescription(item.subtitle ?: item.description ?: item.title)
            .setLongDescription(item.description ?: item.subtitle ?: item.title)
            .setPosterArtUri(item.posterUri?.let(Uri::parse))
            .setThumbnailUri(item.thumbnailUri?.let(Uri::parse))
            .setIntentUri(buildItemIntentUri(item))
            .setInternalProviderId(item.providerId)
            .setContentId(item.contentId)
            .setWeight(weight)
            .setSearchable(true)
            .apply {
                if (item.isEpisode) {
                    setSeriesId(item.contentId)
                    item.season?.let { setSeasonNumber(it) }
                    item.episode?.let { setEpisodeNumber(it) }
                }
                item.releaseTimeMs?.let { setReleaseDate(Date(it)) }
                item.imdbRating?.let {
                    setReviewRating(String.format(java.util.Locale.US, "%.1f", it))
                }
                if (item.genres.isNotEmpty()) {
                    setCanonicalGenres(item.genres.toTypedArray())
                }
                item.durationMs?.takeIf { it > 0 }?.let(::setDurationMillis)
                item.playbackPositionMs?.takeIf { it >= 0 }?.let(::setLastPlaybackPositionMillis)
            }
    }

    private fun buildWatchNextProgram(
        item: TvHomeItem,
        watchNextType: TvHomeWatchNextType
    ): WatchNextProgram.Builder {
        return WatchNextProgram.Builder()
            .setType(resolvePreviewType(item))
            .setTitle(item.title)
            .setEpisodeTitle(item.episodeTitle)
            .setDescription(item.subtitle ?: item.description ?: item.title)
            .setLongDescription(item.description ?: item.subtitle ?: item.title)
            .setPosterArtUri(item.posterUri?.let(Uri::parse))
            .setThumbnailUri(item.thumbnailUri?.let(Uri::parse))
            .setIntentUri(buildItemIntentUri(item))
            .setInternalProviderId(item.providerId)
            .setContentId(item.contentId)
            .setWatchNextType(watchNextType.providerValue)
            .setLastEngagementTimeUtcMillis(item.lastEngagementTimeMs ?: System.currentTimeMillis())
            .setSearchable(true)
            .apply {
                if (item.isEpisode) {
                    setSeriesId(item.contentId)
                    item.season?.let { setSeasonNumber(it) }
                    item.episode?.let { setEpisodeNumber(it) }
                }
                item.releaseTimeMs?.let { setReleaseDate(Date(it)) }
                item.imdbRating?.let {
                    setReviewRating(String.format(java.util.Locale.US, "%.1f", it))
                }
                if (item.genres.isNotEmpty()) {
                    setCanonicalGenres(item.genres.toTypedArray())
                }
                item.durationMs?.takeIf { it > 0 }?.let(::setDurationMillis)
                item.playbackPositionMs?.takeIf { it >= 0 }?.let(::setLastPlaybackPositionMillis)
            }
    }

    private fun publishPreviewProgram(builder: PreviewProgram.Builder): Long? {
        return runCatching { previewChannelHelper.publishPreviewProgram(builder.build()) }
            .onFailure { Log.w(TAG, "Failed to publish preview program: ${it.message}") }
            .getOrNull()
    }

    private fun updatePreviewProgram(existingProgramId: Long, builder: PreviewProgram.Builder): Long? {
        return runCatching {
            previewChannelHelper.updatePreviewProgram(existingProgramId, builder.build())
            existingProgramId
        }.recoverCatching {
            deletePreviewProgram(existingProgramId)
            previewChannelHelper.publishPreviewProgram(builder.build())
        }.onFailure {
            Log.w(TAG, "Failed to update preview program $existingProgramId: ${it.message}")
        }.getOrNull()
    }

    private fun deletePreviewProgram(programId: Long) {
        runCatching { previewChannelHelper.deletePreviewProgram(programId) }
            .onFailure { Log.w(TAG, "Failed to delete preview program $programId: ${it.message}") }
    }

    private fun publishWatchNextProgram(builder: WatchNextProgram.Builder): Long? {
        return runCatching { previewChannelHelper.publishWatchNextProgram(builder.build()) }
            .onFailure { Log.w(TAG, "Failed to publish watch-next program: ${it.message}") }
            .getOrNull()
    }

    private fun updateWatchNextProgram(existingProgramId: Long, builder: WatchNextProgram.Builder): Long? {
        return runCatching {
            deleteWatchNextProgram(existingProgramId)
            previewChannelHelper.publishWatchNextProgram(builder.build())
        }.onFailure {
            Log.w(TAG, "Failed to update watch-next program $existingProgramId: ${it.message}")
        }.getOrNull()
    }

    private fun deleteWatchNextProgram(programId: Long) {
        runCatching {
            context.contentResolver.delete(
                ContentUris.withAppendedId(TvContractCompat.WatchNextPrograms.CONTENT_URI, programId),
                null,
                null
            )
        }.onFailure {
            Log.w(TAG, "Failed to delete watch-next program $programId: ${it.message}")
        }
    }

    private fun computeWeight(item: TvHomeItem, index: Int): Int {
        val base = when {
            item.lastEngagementTimeMs != null -> item.lastEngagementTimeMs / 1000L
            item.releaseTimeMs != null -> (Int.MAX_VALUE.toLong() - (item.releaseTimeMs / 1000L)).coerceAtLeast(0L)
            else -> (Int.MAX_VALUE - index).toLong()
        }
        return base.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun resolvePreviewType(item: TvHomeItem): Int {
        return when {
            item.isMovie -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
            item.isEpisode -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
            else -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
        }
    }

    private fun buildItemIntentUri(item: TvHomeItem): Uri {
        val streamCapable = !item.videoId.isNullOrBlank()
        return if (streamCapable) {
            buildAppIntentUri(
                path = "stream",
                params = mapOf(
                    "videoId" to item.videoId,
                    "contentType" to item.contentType,
                    "title" to (item.episodeTitle ?: item.title),
                    "poster" to item.posterUri,
                    "backdrop" to item.thumbnailUri,
                    "logo" to item.logoUri,
                    "season" to item.season?.toString(),
                    "episode" to item.episode?.toString(),
                    "episodeName" to item.episodeTitle,
                    "contentId" to item.contentId,
                    "contentName" to item.title,
                    "genres" to item.genres.joinToString(","),
                    "year" to item.yearText,
                    "returnToHomeOnBack" to "true"
                )
            )
        } else {
            buildAppIntentUri(
                path = "detail",
                params = mapOf(
                    "itemId" to item.contentId,
                    "itemType" to item.contentType,
                    "addonBaseUrl" to item.addonBaseUrl
                )
            )
        }
    }

    private fun buildAppIntentUri(
        path: String,
        params: Map<String, String?> = emptyMap()
    ): Uri {
        val baseUri = buildString {
            append("nuvio://")
            append(path)
            if (params.isNotEmpty()) {
                append("?")
                append(
                    params.entries.joinToString("&") { (key, value) ->
                        "${encode(key)}=${encode(value.orEmpty())}"
                    }
                )
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(baseUri), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun loadAppLogo(): Bitmap? {
        return runCatching {
            context.packageManager.getApplicationIcon(context.applicationInfo)
                .toBitmap(LOGO_SIZE_PX, LOGO_SIZE_PX)
        }.getOrNull()
    }

    private fun storeChannelLogo(channelId: Long) {
        val logo = appLogo ?: return
        runCatching {
            ChannelLogoUtils.storeChannelLogo(context, channelId, logo)
        }.onFailure {
            Log.w(TAG, "Failed to store logo for channel $channelId: ${it.message}")
        }
    }

    private companion object {
        const val TAG = "TvHomePublisher"
        const val MAX_PROGRAMS = 20
        const val LOGO_SIZE_PX = 320
    }
}
