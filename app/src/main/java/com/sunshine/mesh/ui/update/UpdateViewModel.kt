package com.sunshine.mesh.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sunshine.mesh.data.update.LatestReleaseResult
import com.sunshine.mesh.data.update.UpdateClient
import com.sunshine.mesh.data.update.isNewerVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class UpdateViewModel(
    private val client: UpdateClient,
    currentVersion: String,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState(currentVersion = currentVersion))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    fun checkForUpdates() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, result = null) }

        viewModelScope.launch {
            val result = try {
                when (val fetched = client.fetchLatestRelease()) {
                    is LatestReleaseResult.Found -> toResult(fetched)
                    LatestReleaseResult.NoConnection -> UpdateResult.NoConnection
                    LatestReleaseResult.NotFound -> UpdateResult.Failed("NOT_FOUND")
                    is LatestReleaseResult.HttpError ->
                        UpdateResult.Failed("HTTP_${fetched.code}")
                    LatestReleaseResult.Failed -> UpdateResult.Failed("REQUEST")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w("Update check crashed")
                UpdateResult.Failed("REQUEST")
            }
            _state.update { it.copy(checking = false, result = result) }
        }
    }

    private fun toResult(found: LatestReleaseResult.Found): UpdateResult {
        val release = found.release
        return when (isNewerVersion(release.tag, _state.value.currentVersion)) {
            true -> UpdateResult.Available(
                tag = release.tag,
                downloadUrl = release.downloadUrl,
            )
            false -> UpdateResult.UpToDate(latestTag = release.tag)
            null -> {
                Timber.w("Update version unreadable")
                UpdateResult.Failed("VERSION")
            }
        }
    }

    companion object {
        fun factory(client: UpdateClient, currentVersion: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    UpdateViewModel(client = client, currentVersion = currentVersion)
                }
            }
    }
}
