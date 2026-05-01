package com.nuvio.tv.ui.screens.upcoming

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.UpcomingEntry
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpcomingScreen(
    viewModel: UpcomingViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onNavigateToDetail: (String, String, String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    when {
        uiState.isLoading && uiState.sections.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        }

        uiState.error != null && uiState.sections.isEmpty() -> {
            ErrorState(
                message = uiState.error ?: stringResource(R.string.error_generic),
                onRetry = { viewModel.refresh(force = true) }
            )
        }

        uiState.sections.isEmpty() -> {
            EmptyScreenState(
                title = stringResource(R.string.upcoming_empty_title),
                subtitle = stringResource(R.string.upcoming_empty_subtitle)
            )
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.upcoming_screen_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = NuvioColors.TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.upcoming_screen_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextSecondary
                        )
                    }
                }

                uiState.sections.forEach { section ->
                    item(key = "section_${section.key}") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.titleLarge,
                                color = NuvioColors.TextPrimary
                            )
                        }
                    }

                    items(section.items, key = { it.key }) { item ->
                        UpcomingAgendaCard(
                            item = item,
                            onClick = { onNavigateToDetail(item.contentId, item.contentType, null) }
                        )
                    }
                }

                item(key = "bottom_spacing") {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UpcomingAgendaCard(
    item: UpcomingEntry,
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(22.dp)
    val context = LocalContext.current
    val density = LocalDensity.current
    val imageUrl = item.poster ?: item.backdrop
    val requestWidthPx = remember(density) { with(density) { 240.dp.roundToPx() } }
    val requestHeightPx = remember(density) { with(density) { 135.dp.roundToPx() } }
    val imageModel = remember(imageUrl, requestWidthPx, requestHeightPx) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .crossfade(false)
            .memoryCacheKey("${imageUrl}_${requestWidthPx}x${requestHeightPx}")
            .size(width = requestWidthPx, height = requestHeightPx)
            .build()
    }

    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = cardShape),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.BackgroundElevated
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UpcomingDateBadge(item = item)

            Box(
                modifier = Modifier
                    .width(240.dp)
                    .height(135.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(NuvioColors.BackgroundElevated)
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.isPremiere) {
                        UpcomingChip(label = stringResource(R.string.upcoming_chip_premiere))
                    } else if (item.isNewEpisode) {
                        UpcomingChip(label = stringResource(R.string.upcoming_chip_new_episode))
                    }
                    if (item.source == "local_fallback") {
                        UpcomingChip(label = stringResource(R.string.upcoming_chip_predicted))
                    }
                }

                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                item.releaseInfo?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = formatScheduleLine(item.releaseTimeMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )

                item.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingDateBadge(item: UpcomingEntry) {
    val dayNumber = remember(item.releaseTimeMs) {
        SimpleDateFormat("d", Locale.getDefault()).format(Date(item.releaseTimeMs))
    }
    val monthLabel = remember(item.releaseTimeMs) {
        SimpleDateFormat("MMM", Locale.getDefault()).format(Date(item.releaseTimeMs)).uppercase(Locale.getDefault())
    }
    val weekdayLabel = remember(item.releaseTimeMs) {
        SimpleDateFormat("EEE", Locale.getDefault()).format(Date(item.releaseTimeMs)).uppercase(Locale.getDefault())
    }

    Column(
        modifier = Modifier.width(72.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = weekdayLabel,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioColors.TextSecondary
        )
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NuvioColors.BackgroundElevated),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = dayNumber,
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = monthLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun UpcomingChip(label: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(NuvioColors.FocusBackground.copy(alpha = 0.32f))
            .clickable(enabled = false, onClick = {})
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = NuvioColors.TextPrimary
        )
    }
}

@Composable
private fun formatScheduleLine(timestampMs: Long): String {
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val datePattern = remember(locale) {
        DateFormat.getBestDateTimePattern(locale, "EEE d MMM")
    }
    val timePattern = remember(locale) {
        DateFormat.getBestDateTimePattern(locale, "Hm")
    }
    val dateText = remember(timestampMs, locale) {
        SimpleDateFormat(datePattern, locale).format(Date(timestampMs))
    }
    val timeText = remember(timestampMs, locale) {
        SimpleDateFormat(timePattern, locale).format(Date(timestampMs))
    }
    return stringResource(R.string.upcoming_schedule_line, dateText, timeText)
}
