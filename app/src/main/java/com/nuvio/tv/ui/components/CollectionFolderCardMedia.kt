package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.tv.domain.model.CollectionFolder
import kotlinx.coroutines.delay

fun collectionFolderCardImageUrl(
    folder: CollectionFolder,
    isFocused: Boolean
): String? {
    if (!folder.focusGifEnabled) {
        return firstNonBlank(folder.coverImageUrl)
    }
    return if (isFocused) {
        firstNonBlank(folder.focusGifUrl, folder.coverImageUrl)
    } else {
        firstNonBlank(folder.coverImageUrl, folder.focusGifUrl)
    }
}

@Composable
fun rememberCollectionFolderCardImageUrl(
    folder: CollectionFolder,
    isFocused: Boolean,
    focusGifDelayMs: Long = 220L
): String? {
    var focusGifReady by remember(folder.id, folder.focusGifEnabled, folder.focusGifUrl) {
        mutableStateOf(false)
    }

    LaunchedEffect(folder.id, isFocused, folder.focusGifEnabled, folder.focusGifUrl, focusGifDelayMs) {
        if (!isFocused || !folder.focusGifEnabled || folder.focusGifUrl.isNullOrBlank()) {
            focusGifReady = false
            return@LaunchedEffect
        }

        focusGifReady = false
        delay(focusGifDelayMs)
        if (isFocused) {
            focusGifReady = true
        }
    }

    return remember(folder, isFocused, focusGifReady) {
        when {
            !folder.focusGifEnabled -> firstNonBlank(folder.coverImageUrl)
            isFocused && focusGifReady -> firstNonBlank(folder.focusGifUrl, folder.coverImageUrl)
            else -> firstNonBlank(folder.coverImageUrl, folder.focusGifUrl)
        }
    }
}

private fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}
