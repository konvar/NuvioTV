package com.nuvio.tv.ui.screens.upcoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.UpcomingRepository
import com.nuvio.tv.data.repository.UpcomingSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpcomingUiState(
    val sections: List<UpcomingSection> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class UpcomingViewModel @Inject constructor(
    private val upcomingRepository: UpcomingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UpcomingUiState())
    val uiState: StateFlow<UpcomingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            upcomingRepository.observeSections()
                .distinctUntilChanged()
                .collectLatest { sections ->
                    _uiState.update {
                        it.copy(
                            sections = sections,
                            isLoading = false,
                            error = null
                        )
                    }
                }
        }
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                upcomingRepository.refreshNow(force = force)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to load upcoming titles"
                    )
                }
            }
        }
    }
}
