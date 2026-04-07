package com.nuvio.tv.tvhome

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvHomeStateStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var continueWatchingChannelId: Long?
        get() = prefs.getLong(KEY_CW_CHANNEL_ID, -1L).takeIf { it > 0L }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_CW_CHANNEL_ID) else putLong(KEY_CW_CHANNEL_ID, value)
            }.apply()
        }

    var upcomingChannelId: Long?
        get() = prefs.getLong(KEY_UPCOMING_CHANNEL_ID, -1L).takeIf { it > 0L }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_UPCOMING_CHANNEL_ID) else putLong(KEY_UPCOMING_CHANNEL_ID, value)
            }.apply()
        }

    fun getProgramIds(kind: TvHomeChannelKind): Set<Long> {
        return prefs.getStringSet(programIdsKey(kind), emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    }

    fun setProgramIds(kind: TvHomeChannelKind, ids: Collection<Long>) {
        prefs.edit()
            .putStringSet(programIdsKey(kind), ids.map { it.toString() }.toSet())
            .apply()
    }

    fun setPendingBrowsableChannelId(channelId: Long?) {
        prefs.edit().apply {
            if (channelId == null) remove(KEY_PENDING_BROWSABLE_CHANNEL_ID)
            else putLong(KEY_PENDING_BROWSABLE_CHANNEL_ID, channelId)
        }.apply()
    }

    fun consumePendingBrowsableChannelId(): Long? {
        val channelId = prefs.getLong(KEY_PENDING_BROWSABLE_CHANNEL_ID, -1L).takeIf { it > 0L }
        if (channelId != null) {
            prefs.edit().remove(KEY_PENDING_BROWSABLE_CHANNEL_ID).apply()
        }
        return channelId
    }

    private fun programIdsKey(kind: TvHomeChannelKind): String {
        return "program_ids_${kind.prefKey}"
    }

    private companion object {
        const val PREFS_NAME = "tv_home_state"
        const val KEY_CW_CHANNEL_ID = "cw_channel_id"
        const val KEY_UPCOMING_CHANNEL_ID = "upcoming_channel_id"
        const val KEY_PENDING_BROWSABLE_CHANNEL_ID = "pending_browsable_channel_id"
    }
}
