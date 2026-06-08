package com.jm.sillydroid.feature.main.ui.home

import com.jm.sillydroid.core.model.bootstrap.BootstrapEvent
import com.jm.sillydroid.core.model.bootstrap.BootstrapLifecycle
import com.jm.sillydroid.core.model.bootstrap.BootstrapSessionSnapshot
import com.jm.sillydroid.core.model.settings.BrowserDataClearTarget
import com.jm.sillydroid.domain.bootstrap.BootstrapController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private lateinit var fakeController: FakeBootstrapController

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fakeController = FakeBootstrapController()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial snapshot mirrors controller currentSnapshot`() = runTest {
        val initial = BootstrapSessionSnapshot(appSessionId = "init", attemptId = 7)
        fakeController.snapshotState.value = initial

        val viewModel = HomeViewModel(fakeController)

        assertEquals(initial, viewModel.bootstrapSnapshot.value)
    }

    @Test
    fun `bootstrapSnapshot updates when controller emits new snapshot`() = runTest {
        val viewModel = HomeViewModel(fakeController)

        val updated = BootstrapSessionSnapshot(
            appSessionId = "session-1",
            attemptId = 3,
            lifecycle = BootstrapLifecycle.READY_MONITORING
        )
        fakeController.snapshotState.value = updated

        assertEquals(updated, viewModel.bootstrapSnapshot.value)
        assertTrue(viewModel.bootstrapSnapshot.value.isReady)
    }

    @Test
    fun `default state fields are conservative`() = runTest {
        val viewModel = HomeViewModel(fakeController)

        assertEquals("", viewModel.loadedUrl)
        assertEquals(0, viewModel.pendingLocalRetryAttempts)
        assertFalse(viewModel.isOpeningBootstrapSettings)
        assertFalse(viewModel.isPullGestureRefreshing)
        assertFalse(viewModel.isImeVisible)
        assertFalse(viewModel.shouldForceFreshWebViewLoad)
        assertEquals(0, viewModel.browserDataClearMask)
        assertEquals("", viewModel.backgroundOnlyExternalBrowserAttemptKey)
        assertFalse(viewModel.consumeWebViewRendererCrashBrowserEngineHint())
    }

    @Test
    fun `browserDataClearMask ignores unknown bits`() = runTest {
        val viewModel = HomeViewModel(fakeController)

        viewModel.browserDataClearMask = BrowserDataClearTarget.RESOURCE_CACHE.mask or (1 shl 12)

        assertEquals(BrowserDataClearTarget.RESOURCE_CACHE.mask, viewModel.browserDataClearMask)
    }

    @Test
    fun `resetForBootstrapRestart clears webview and retry state`() = runTest {
        val viewModel = HomeViewModel(fakeController).apply {
            loadedUrl = "https://example.test/page"
            pendingLocalRetryAttempts = 4
            shouldForceFreshWebViewLoad = true
            isOpeningBootstrapSettings = true
            isPullGestureRefreshing = true
            isImeVisible = true
        }

        viewModel.resetForBootstrapRestart()

        assertEquals("", viewModel.loadedUrl)
        assertEquals(0, viewModel.pendingLocalRetryAttempts)
        assertTrue(viewModel.shouldForceFreshWebViewLoad)
        // 仅清理 WebView 恢复 / 重试相关字段；其它瞬态字段保持原值。
        assertTrue(viewModel.isOpeningBootstrapSettings)
        assertTrue(viewModel.isPullGestureRefreshing)
        assertTrue(viewModel.isImeVisible)
    }

    @Test
    fun `webview renderer crash browser engine hint is consumed once`() = runTest {
        val viewModel = HomeViewModel(fakeController)

        viewModel.requestWebViewRendererCrashBrowserEngineHint()

        assertTrue(viewModel.consumeWebViewRendererCrashBrowserEngineHint())
        assertFalse(viewModel.consumeWebViewRendererCrashBrowserEngineHint())
    }

    @Test
    fun `viewModel survives StandardTestDispatcher main`() = runTest {
        // 切换到 StandardTestDispatcher 验证 init 内 launch 在不同主调度器下也能正常订阅。
        Dispatchers.resetMain()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        val viewModel = HomeViewModel(fakeController)

        val updated = BootstrapSessionSnapshot(appSessionId = "later")
        fakeController.snapshotState.value = updated
        testScheduler.advanceUntilIdle()

        assertEquals(updated, viewModel.bootstrapSnapshot.value)
    }

    private class FakeBootstrapController : BootstrapController {
        val snapshotState = MutableStateFlow(BootstrapSessionSnapshot())
        private val eventFlow = MutableSharedFlow<BootstrapEvent>(extraBufferCapacity = 16)

        override val snapshot: StateFlow<BootstrapSessionSnapshot> = snapshotState.asStateFlow()
        override val events: SharedFlow<BootstrapEvent> = eventFlow.asSharedFlow()
        override fun currentSnapshot(): BootstrapSessionSnapshot = snapshotState.value

        override fun start(retry: Boolean) = Unit
        override suspend fun stopForSettingsAndAwait(timeoutMessage: String) = Unit
    }
}
