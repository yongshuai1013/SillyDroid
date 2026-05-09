package com.jm.sillydroid

import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface HostProcessManager {
    val state: StateFlow<StartupState>

    fun currentState(): StartupState

    fun start(forceRestart: Boolean = false)

    fun stopForSettings()

    fun restart() {
        start(forceRestart = true)
    }

    suspend fun stopForSettingsAndAwait(timeoutMessage: String, timeoutMillis: Long = 15_000L)
}

internal class DefaultHostProcessManager(context: Context) : HostProcessManager {
    private val appContext = context.applicationContext

    private val stoppedPhases = setOf(
        StartupPhase.CONFIGURING,
        StartupPhase.IDLE,
        StartupPhase.BLOCKED,
        StartupPhase.ERROR
    )

    override val state: StateFlow<StartupState>
        get() = StartupRuntimeStore.state

    override fun currentState(): StartupState {
        return state.value
    }

    override fun start(forceRestart: Boolean) {
        val intent = StartupCoordinatorService.createStartIntent(appContext, retry = forceRestart)
        ContextCompat.startForegroundService(appContext, intent)
    }

    override fun stopForSettings() {
        appContext.startService(StartupCoordinatorService.createStopForSettingsIntent(appContext))
    }

    override suspend fun stopForSettingsAndAwait(timeoutMessage: String, timeoutMillis: Long) {
        if (currentState().phase !in stoppedPhases) {
            withContext(Dispatchers.Main.immediate) {
                stopForSettings()
            }
        }

        val stoppedState = withTimeoutOrNull(timeoutMillis) {
            state.first { startupState ->
                startupState.phase in stoppedPhases
            }
        }

        if (stoppedState == null) {
            throw BootstrapException(timeoutMessage)
        }
    }
}