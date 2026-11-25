package com.sunshine.mesh.ui.update

data class UpdateUiState(
    val currentVersion: String,
    val checking: Boolean = false,
    val result: UpdateResult? = null,
)

sealed interface UpdateResult {
    data class Available(
        val tag: String,
        val downloadUrl: String,
    ) : UpdateResult

    data class UpToDate(val latestTag: String) : UpdateResult

    data object NoConnection : UpdateResult

    data class Failed(val code: String) : UpdateResult
}
