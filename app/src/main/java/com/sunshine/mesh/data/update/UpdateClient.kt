package com.sunshine.mesh.data.update

/** Latest GitHub release, reduced to what the UI needs. */
data class LatestRelease(
    val tag: String,
    val downloadUrl: String,
)

sealed interface LatestReleaseResult {
    data class Found(val release: LatestRelease) : LatestReleaseResult
    data object NoConnection : LatestReleaseResult
    data object NotFound : LatestReleaseResult
    data class HttpError(val code: Int) : LatestReleaseResult
    data object Failed : LatestReleaseResult
}

/** Read-only update source, faked in ViewModel tests. */
interface UpdateClient {
    suspend fun fetchLatestRelease(): LatestReleaseResult
}
