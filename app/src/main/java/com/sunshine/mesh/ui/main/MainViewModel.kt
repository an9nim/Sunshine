package com.sunshine.mesh.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sunshine.mesh.data.profile.CreateProfileResult
import com.sunshine.mesh.data.profile.ProfileRepository
import com.sunshine.mesh.domain.model.Profile
import com.sunshine.mesh.domain.validation.Nickname
import com.sunshine.mesh.domain.validation.NicknameError
import com.sunshine.mesh.domain.validation.NicknameResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data object Missing : ProfileUiState
    data class Ready(val profile: Profile) : ProfileUiState
    data object LoadFailed : ProfileUiState
}

data class OnboardingForm(
    val nickname: String = "",
    val nicknameError: NicknameError? = null,
    val isSubmitting: Boolean = false,
    val submitFailed: Boolean = false,
)

class MainViewModel(private val repository: ProfileRepository) : ViewModel() {

    private val _profileState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val profileState: StateFlow<ProfileUiState> = _profileState.asStateFlow()

    private val _form = MutableStateFlow(OnboardingForm())
    val form: StateFlow<OnboardingForm> = _form.asStateFlow()

    init {
        loadProfile()
    }

    fun onNicknameChange(value: String) {
        _form.update {
            it.copy(
                nickname = value,
                nicknameError = null,
                submitFailed = false,
            )
        }
    }

    fun createProfile() {
        if (_form.value.isSubmitting) return

        val nickname = when (val result = Nickname.normalize(_form.value.nickname)) {
            is NicknameResult.Invalid -> {
                _form.update { it.copy(nicknameError = result.reason) }
                return
            }
            is NicknameResult.Ok -> result.value
        }

        _form.update {
            it.copy(
                isSubmitting = true,
                nicknameError = null,
                submitFailed = false,
            )
        }

        viewModelScope.launch {
            when (val result = repository.createProfile(nickname)) {
                is CreateProfileResult.Created -> {
                    _form.value = OnboardingForm()
                    _profileState.value = ProfileUiState.Ready(result.profile)
                }
                is CreateProfileResult.InvalidNickname -> {
                    _form.update {
                        it.copy(
                            isSubmitting = false,
                            nicknameError = result.reason,
                        )
                    }
                }
                CreateProfileResult.AlreadyExists -> {
                    _form.update { it.copy(isSubmitting = false) }
                    loadProfile()
                }
                is CreateProfileResult.Failed -> {
                    Timber.e(result.cause, "Profile creation failed")
                    _form.update {
                        it.copy(
                            isSubmitting = false,
                            submitFailed = true,
                        )
                    }
                }
            }
        }
    }

    fun reload() {
        if (_profileState.value is ProfileUiState.Loading) return
        loadProfile()
    }

    private fun loadProfile() {
        _profileState.value = ProfileUiState.Loading
        viewModelScope.launch {
            try {
                val profile = repository.loadProfile()
                if (profile == null) {
                    _profileState.update { current ->
                        if (current is ProfileUiState.Ready) current else ProfileUiState.Missing
                    }
                } else {
                    _profileState.value = ProfileUiState.Ready(profile)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.e(exception, "Profile load failed")
                _profileState.value = ProfileUiState.LoadFailed
            }
        }
    }

    companion object {
        fun factory(repository: ProfileRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    MainViewModel(repository)
                }
            }
    }
}
