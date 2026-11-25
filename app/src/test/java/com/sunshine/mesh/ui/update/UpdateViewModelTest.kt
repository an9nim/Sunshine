package com.sunshine.mesh.ui.update

import com.sunshine.mesh.data.update.LatestRelease
import com.sunshine.mesh.data.update.LatestReleaseResult
import com.sunshine.mesh.data.update.UpdateClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun newerReleaseIsOfferedForDownload() = runTest(dispatcher) {
        val viewModel = createViewModel(
            LatestReleaseResult.Found(
                LatestRelease(
                    tag = "v0.2.0",
                    downloadUrl = "https://github.com/an9nim/Sunshine/releases/tag/v0.2.0",
                ),
            ),
        )

        viewModel.checkForUpdates()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.checking)
        assertEquals(
            UpdateResult.Available(
                tag = "v0.2.0",
                downloadUrl = "https://github.com/an9nim/Sunshine/releases/tag/v0.2.0",
            ),
            state.result,
        )
    }

    @Test
    fun sameReleaseIsReportedAsUpToDate() = runTest(dispatcher) {
        val viewModel = createViewModel(
            LatestReleaseResult.Found(
                LatestRelease(
                    tag = "v0.1.0",
                    downloadUrl = "https://github.com/an9nim/Sunshine/releases/tag/v0.1.0",
                ),
            ),
        )

        viewModel.checkForUpdates()
        advanceUntilIdle()

        assertEquals(UpdateResult.UpToDate("v0.1.0"), viewModel.state.value.result)
    }

    @Test
    fun noConnectionIsReportedSeparately() = runTest(dispatcher) {
        val viewModel = createViewModel(LatestReleaseResult.NoConnection)

        viewModel.checkForUpdates()
        advanceUntilIdle()

        assertEquals(UpdateResult.NoConnection, viewModel.state.value.result)
    }

    @Test
    fun httpErrorKeepsTheCode() = runTest(dispatcher) {
        val viewModel = createViewModel(LatestReleaseResult.HttpError(503))

        viewModel.checkForUpdates()
        advanceUntilIdle()

        assertEquals(UpdateResult.Failed("HTTP_503"), viewModel.state.value.result)
    }

    @Test
    fun unreadableTagBecomesVersionError() = runTest(dispatcher) {
        val viewModel = createViewModel(
            LatestReleaseResult.Found(
                LatestRelease(
                    tag = "latest",
                    downloadUrl = "https://github.com/an9nim/Sunshine/releases/latest",
                ),
            ),
        )

        viewModel.checkForUpdates()
        advanceUntilIdle()

        assertEquals(UpdateResult.Failed("VERSION"), viewModel.state.value.result)
    }

    @Test
    fun missingReleaseIsReported() = runTest(dispatcher) {
        val viewModel = createViewModel(LatestReleaseResult.NotFound)

        viewModel.checkForUpdates()
        advanceUntilIdle()

        assertEquals(UpdateResult.Failed("NOT_FOUND"), viewModel.state.value.result)
    }

    @Test
    fun clientCrashBecomesRequestError() = runTest(dispatcher) {
        val viewModel = createViewModel(failure = IllegalStateException("boom"))

        viewModel.checkForUpdates()
        advanceUntilIdle()

        assertEquals(UpdateResult.Failed("REQUEST"), viewModel.state.value.result)
        assertFalse(viewModel.state.value.checking)
    }

    @Test
    fun currentVersionIsExposedBeforeCheck() {
        val viewModel = UpdateViewModel(FakeUpdateClient(LatestReleaseResult.Failed), "0.1.0")

        assertEquals("0.1.0", viewModel.state.value.currentVersion)
        assertEquals(null, viewModel.state.value.result)
        assertFalse(viewModel.state.value.checking)
    }

    private fun createViewModel(
        result: LatestReleaseResult = LatestReleaseResult.Failed,
        failure: Exception? = null,
    ): UpdateViewModel = UpdateViewModel(
        client = FakeUpdateClient(result, failure),
        currentVersion = "0.1.0",
    )

    private class FakeUpdateClient(
        private val result: LatestReleaseResult,
        private val failure: Exception? = null,
    ) : UpdateClient {
        override suspend fun fetchLatestRelease(): LatestReleaseResult {
            failure?.let { throw it }
            return result
        }
    }
}
