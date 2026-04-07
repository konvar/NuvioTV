package com.nuvio.tv.tvhome

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.MainActivity
import com.nuvio.tv.data.repository.UpcomingEntry
import com.nuvio.tv.data.repository.UpcomingRepository
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context

interface TvHomePublisher {
    fun start()
}

enum class TvHomeChannelKind(
    val prefKey: String,
    val displayName: String,
    val description: String,
    val browsePath: String
) {
    CONTINUE_WATCHING(
        prefKey = "continue_watching",
        displayName = "Continue Watching",
        description = "Resume recently watched titles",
        browsePath = "home"
    ),
    UPCOMING(
        prefKey = "upcoming",
        displayName = "Upcoming",
        description = "New episodes and upcoming releases",
        browsePath = "upcoming"
    )
}

@Singleton
class TvHomePublisherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val upcomingRepository: UpcomingRepository,
    private val stateStore: TvHomeStateStore
) : TvHomePublisher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val previewChannelHelper by lazy { PreviewChannelHelper(context) }
    @Volatile
    private var started = false

    override fun start() {
        if (started) return
        started = true
        if (!isSupported()) return

        scope.launch {
            watchProgressRepository.continueWatching.collectLatest { items ->
                publishContinueWatching(items.take(MAX_PROGRAMS))
            }
        }

        scope.launch {
            upcomingRepository.observeSections().collectLatest { sections ->
                val items = sections.flatMap { it.items }.take(MAX_PROGRAMS)
                publishUpcoming(items)
            }
        }

        scope.launch {
            runCatching { upcomingRepository.refreshNow(force = false) }
        }
    }

    private fun publishContinueWatching(items: List<WatchProgress>) {
        val channelId = ensureChannel(TvHomeChannelKind.CONTINUE_WATCHING) ?: return
        replacePrograms(
            kind = TvHomeChannelKind.CONTINUE_WATCHING,
            channelId = channelId,
            builders = items.map { buildContinueWatchingProgram(channelId, it) }
        )
    }

    private fun publishUpcoming(items: List<UpcomingEntry>) {
        val channelId = ensureChannel(TvHomeChannelKind.UPCOMING) ?: return
        replacePrograms(
            kind = TvHomeChannelKind.UPCOMING,
            channelId = channelId,
            builders = items.map { buildUpcomingProgram(channelId, it) }
        )
    }

    private fun ensureChannel(kind: TvHomeChannelKind): Long? {
        val storedId = when (kind) {
            TvHomeChannelKind.CONTINUE_WATCHING -> stateStore.continueWatchingChannelId
            TvHomeChannelKind.UPCOMING -> stateStore.upcomingChannelId
        }
        if (storedId != null && previewChannelHelper.getPreviewChannel(storedId) != null) {
            return storedId
        }
        if (storedId != null) {
            when (kind) {
                TvHomeChannelKind.CONTINUE_WATCHING -> stateStore.continueWatchingChannelId = null
                TvHomeChannelKind.UPCOMING -> stateStore.upcomingChannelId = null
            }
        }

        val channel = PreviewChannel.Builder()
            .setDisplayName(kind.displayName)
            .setDescription(kind.description)
            .setAppLinkIntentUri(buildAppIntentUri(kind.browsePath))
            .setInternalProviderId(kind.prefKey)
            .build()

        return runCatching {
            val publishAsDefault = kind == TvHomeChannelKind.CONTINUE_WATCHING &&
                stateStore.continueWatchingChannelId == null &&
                stateStore.upcomingChannelId == null

            val channelId = if (publishAsDefault) {
                previewChannelHelper.publishDefaultChannel(channel)
            } else {
                previewChannelHelper.publishChannel(channel)
            }

            when (kind) {
                TvHomeChannelKind.CONTINUE_WATCHING -> stateStore.continueWatchingChannelId = channelId
                TvHomeChannelKind.UPCOMING -> stateStore.upcomingChannelId = channelId
            }

            if (!publishAsDefault) {
                stateStore.setPendingBrowsableChannelId(channelId)
            }

            channelId
        }.onFailure {
            Log.w(TAG, "Failed to publish ${kind.prefKey} channel: ${it.message}")
        }.getOrNull()
    }

    private fun replacePrograms(
        kind: TvHomeChannelKind,
        channelId: Long,
        builders: List<PreviewProgram.Builder>
    ) {
        stateStore.getProgramIds(kind).forEach { programId ->
            runCatching { previewChannelHelper.deletePreviewProgram(programId) }
        }

        val newProgramIds = builders.mapNotNull { builder ->
            runCatching { previewChannelHelper.publishPreviewProgram(builder.build()) }
                .onFailure {
                    Log.w(TAG, "Failed to publish ${kind.prefKey} program: ${it.message}")
                }
                .getOrNull()
        }
        stateStore.setProgramIds(kind, newProgramIds)

        if (builders.isEmpty()) {
            stateStore.setProgramIds(kind, emptySet())
        }
    }

    private fun buildContinueWatchingProgram(
        channelId: Long,
        progress: WatchProgress
    ): PreviewProgram.Builder {
        val description = buildContinueWatchingDescription(progress)
        return PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(resolvePreviewType(progress.contentType, progress.season, progress.episode))
            .setTitle(progress.name)
            .setDescription(description)
            .setPosterArtUri(progress.poster?.let(Uri::parse))
            .setIntentUri(
                buildAppIntentUri(
                    path = "detail",
                    params = mapOf(
                        "itemId" to progress.contentId,
                        "itemType" to progress.contentType
                    )
                )
            )
            .setInternalProviderId(
                listOf(
                    progress.contentType,
                    progress.contentId,
                    progress.season?.toString().orEmpty(),
                    progress.episode?.toString().orEmpty()
                ).joinToString("|")
            )
            .setWeight((progress.lastWatched / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun buildUpcomingProgram(
        channelId: Long,
        item: UpcomingEntry
    ): PreviewProgram.Builder {
        val description = item.releaseInfo ?: item.description ?: item.name
        return PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(resolvePreviewType(item.contentType, item.season, item.episode))
            .setTitle(item.displayTitle)
            .setDescription(description)
            .setPosterArtUri(item.poster?.let(Uri::parse))
            .setIntentUri(
                buildAppIntentUri(
                    path = "detail",
                    params = mapOf(
                        "itemId" to item.contentId,
                        "itemType" to item.contentType
                    )
                )
            )
            .setInternalProviderId(item.key)
            .setWeight((item.releaseTimeMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun buildContinueWatchingDescription(progress: WatchProgress): String {
        val episodeLabel = if (progress.season != null && progress.episode != null) {
            buildString {
                append("Resume S")
                append(progress.season)
                append(" E")
                append(progress.episode)
                progress.episodeTitle?.takeIf { it.isNotBlank() }?.let {
                    append(" - ")
                    append(it)
                }
            }
        } else {
            "Resume playback"
        }
        val percent = (progress.progressPercentage * 100).toInt().coerceIn(0, 100)
        return "$episodeLabel - $percent% watched"
    }

    private fun resolvePreviewType(contentType: String?, season: Int?, episode: Int?): Int {
        return when {
            contentType.equals("movie", ignoreCase = true) -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
            season != null && episode != null -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
            else -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
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

    private companion object {
        const val TAG = "TvHomePublisher"
        const val MAX_PROGRAMS = 20
    }
}
