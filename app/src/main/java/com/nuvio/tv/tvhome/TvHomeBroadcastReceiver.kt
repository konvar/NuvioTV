package com.nuvio.tv.tvhome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TvHomeBroadcastReceiver : BroadcastReceiver() {

    @Inject
    lateinit var tvHomePublisher: TvHomePublisher

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INITIALIZE_PROGRAMS -> tvHomePublisher.handleInitializePrograms()
            ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED -> {
                val programId = intent.getLongExtra(TvContractCompat.EXTRA_PREVIEW_PROGRAM_ID, -1L)
                if (programId > 0L) {
                    tvHomePublisher.handlePreviewProgramBrowsableDisabled(programId)
                }
            }
            ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED -> {
                val programId = intent.getLongExtra(TvContractCompat.EXTRA_WATCH_NEXT_PROGRAM_ID, -1L)
                if (programId > 0L) {
                    tvHomePublisher.handleWatchNextProgramBrowsableDisabled(programId)
                }
            }
            ACTION_PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT -> {
                val programId = intent.getLongExtra(TvContractCompat.EXTRA_PREVIEW_PROGRAM_ID, -1L)
                if (programId > 0L) {
                    tvHomePublisher.handlePreviewProgramAddedToWatchNext(programId)
                }
            }
        }
    }

    companion object {
        const val ACTION_INITIALIZE_PROGRAMS = "android.media.tv.action.INITIALIZE_PROGRAMS"
        const val ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED =
            "android.media.tv.action.PREVIEW_PROGRAM_BROWSABLE_DISABLED"
        const val ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED =
            "android.media.tv.action.WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED"
        const val ACTION_PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT =
            "android.media.tv.action.PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT"
    }
}
