package com.nuvio.tv.tvhome

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvHomeStateStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getChannelId(kind: TvHomeChannelKind): Long? {
        val generic = prefs.getLong(channelIdKey(kind), -1L).takeIf { it > 0L }
        if (generic != null) return generic
        return legacyChannelId(kind)
    }

    fun setChannelId(kind: TvHomeChannelKind, channelId: Long?) {
        prefs.edit().apply {
            if (channelId == null) remove(channelIdKey(kind)) else putLong(channelIdKey(kind), channelId)
            remove(legacyChannelIdKey(kind))
        }.apply()
    }

    fun getProgramIds(kind: TvHomeChannelKind): Map<String, Long> {
        return decodeIdMap(
            prefs.getStringSet(programIdsKey(kind), emptySet()).orEmpty()
        )
    }

    fun setProgramIds(kind: TvHomeChannelKind, idsByProviderId: Map<String, Long>) {
        prefs.edit()
            .putStringSet(programIdsKey(kind), encodeIdMap(idsByProviderId))
            .apply()
    }

    fun findProgramProviderId(kind: TvHomeChannelKind, programId: Long): String? {
        return getProgramIds(kind)
            .entries
            .firstOrNull { it.value == programId }
            ?.key
    }

    fun getWatchNextProgramIds(): Map<String, Long> {
        return decodeIdMap(
            prefs.getStringSet(KEY_WATCH_NEXT_PROGRAM_IDS, emptySet()).orEmpty()
        )
    }

    fun setWatchNextProgramIds(idsByProviderId: Map<String, Long>) {
        prefs.edit()
            .putStringSet(KEY_WATCH_NEXT_PROGRAM_IDS, encodeIdMap(idsByProviderId))
            .apply()
    }

    fun findWatchNextProviderId(programId: Long): String? {
        return getWatchNextProgramIds()
            .entries
            .firstOrNull { it.value == programId }
            ?.key
    }

    fun enqueueBrowsableChannelId(channelId: Long) {
        val current = prefs.getStringSet(KEY_PENDING_BROWSABLE_CHANNEL_IDS, emptySet()).orEmpty().toMutableSet()
        current.add(channelId.toString())
        prefs.edit()
            .putStringSet(KEY_PENDING_BROWSABLE_CHANNEL_IDS, current)
            .remove(KEY_PENDING_BROWSABLE_CHANNEL_ID_LEGACY)
            .apply()
    }

    fun consumePendingBrowsableChannelIds(): List<Long> {
        val current = prefs.getStringSet(KEY_PENDING_BROWSABLE_CHANNEL_IDS, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toMutableSet()
        prefs.getLong(KEY_PENDING_BROWSABLE_CHANNEL_ID_LEGACY, -1L)
            .takeIf { it > 0L }
            ?.let(current::add)

        prefs.edit()
            .remove(KEY_PENDING_BROWSABLE_CHANNEL_IDS)
            .remove(KEY_PENDING_BROWSABLE_CHANNEL_ID_LEGACY)
            .apply()

        return current.toList()
    }

    fun isPreviewDismissed(kind: TvHomeChannelKind, providerId: String): Boolean {
        return prefs.getStringSet(previewDismissedKey(kind), emptySet()).orEmpty()
            .contains(encodeProviderId(providerId))
    }

    fun markPreviewDismissed(kind: TvHomeChannelKind, providerId: String) {
        val current = prefs.getStringSet(previewDismissedKey(kind), emptySet()).orEmpty().toMutableSet()
        current.add(encodeProviderId(providerId))
        prefs.edit().putStringSet(previewDismissedKey(kind), current).apply()
    }

    fun clearPreviewDismissed(kind: TvHomeChannelKind, providerId: String) {
        val current = prefs.getStringSet(previewDismissedKey(kind), emptySet()).orEmpty().toMutableSet()
        current.remove(encodeProviderId(providerId))
        prefs.edit().putStringSet(previewDismissedKey(kind), current).apply()
    }

    fun isWatchNextDismissed(providerId: String): Boolean {
        return prefs.getStringSet(KEY_DISMISSED_WATCH_NEXT_PROVIDER_IDS, emptySet()).orEmpty()
            .contains(encodeProviderId(providerId))
    }

    fun markWatchNextDismissed(providerId: String) {
        val current = prefs.getStringSet(KEY_DISMISSED_WATCH_NEXT_PROVIDER_IDS, emptySet()).orEmpty().toMutableSet()
        current.add(encodeProviderId(providerId))
        prefs.edit().putStringSet(KEY_DISMISSED_WATCH_NEXT_PROVIDER_IDS, current).apply()
    }

    fun clearWatchNextDismissed(providerId: String) {
        val current = prefs.getStringSet(KEY_DISMISSED_WATCH_NEXT_PROVIDER_IDS, emptySet()).orEmpty().toMutableSet()
        current.remove(encodeProviderId(providerId))
        prefs.edit().putStringSet(KEY_DISMISSED_WATCH_NEXT_PROVIDER_IDS, current).apply()
    }

    private fun channelIdKey(kind: TvHomeChannelKind): String = "channel_id_${kind.prefKey}"

    private fun legacyChannelId(kind: TvHomeChannelKind): Long? {
        val legacyKey = legacyChannelIdKey(kind) ?: return null
        return prefs.getLong(legacyKey, -1L).takeIf { it > 0L }
    }

    private fun legacyChannelIdKey(kind: TvHomeChannelKind): String? {
        return when (kind) {
            TvHomeChannelKind.CONTINUE_WATCHING -> KEY_CW_CHANNEL_ID_LEGACY
            TvHomeChannelKind.UPCOMING -> KEY_UPCOMING_CHANNEL_ID_LEGACY
            else -> null
        }
    }

    private fun programIdsKey(kind: TvHomeChannelKind): String = "program_ids_${kind.prefKey}"

    private fun previewDismissedKey(kind: TvHomeChannelKind): String {
        return "dismissed_preview_${kind.prefKey}"
    }

    private fun encodeIdMap(map: Map<String, Long>): Set<String> {
        return map.entries.map { (providerId, programId) ->
            "${encodeProviderId(providerId)}:$programId"
        }.toSet()
    }

    private fun decodeIdMap(encoded: Set<String>): Map<String, Long> {
        return encoded.mapNotNull { entry ->
            val separatorIndex = entry.lastIndexOf(':')
            if (separatorIndex <= 0 || separatorIndex >= entry.length - 1) return@mapNotNull null
            val providerId = decodeProviderId(entry.substring(0, separatorIndex))
            val programId = entry.substring(separatorIndex + 1).toLongOrNull() ?: return@mapNotNull null
            providerId to programId
        }.toMap()
    }

    private fun encodeProviderId(providerId: String): String {
        return Base64.encodeToString(providerId.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun decodeProviderId(encoded: String): String {
        return String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
    }

    private companion object {
        const val PREFS_NAME = "tv_home_state"
        const val KEY_CW_CHANNEL_ID_LEGACY = "cw_channel_id"
        const val KEY_UPCOMING_CHANNEL_ID_LEGACY = "upcoming_channel_id"
        const val KEY_PENDING_BROWSABLE_CHANNEL_ID_LEGACY = "pending_browsable_channel_id"
        const val KEY_PENDING_BROWSABLE_CHANNEL_IDS = "pending_browsable_channel_ids"
        const val KEY_WATCH_NEXT_PROGRAM_IDS = "watch_next_program_ids"
        const val KEY_DISMISSED_WATCH_NEXT_PROVIDER_IDS = "dismissed_watch_next_provider_ids"
    }
}
