package com.jm.sillydroid.feature.settings.ui.screen

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.jm.sillydroid.core.model.settings.BrowserEngine
import com.jm.sillydroid.core.model.settings.HostDisplayMode
import com.jm.sillydroid.feature.settings.R
import com.jm.sillydroid.feature.settings.model.SettingsActivityUiState
import com.jm.sillydroid.feature.settings.viewmodel.SettingsActivityViewModel
import kotlinx.coroutines.launch

class SettingsActivityStateController(
    private val activity: AppCompatActivity,
    private val viewModel: SettingsActivityViewModel,
    private val floatingLogsSwitch: MaterialSwitch,
    private val backgroundOnlyModeSwitch: MaterialSwitch,
    private val backgroundHealthCheckSwitch: MaterialSwitch,
    private val pullRefreshSwitch: MaterialSwitch,
    private val browserEngineRow: View,
    private val browserEngineValueView: TextView,
    private val hostDisplayModeRow: View,
    private val hostDisplayModeValueView: TextView,
    private val debugDiagnosticsSwitch: MaterialSwitch,
    private val unrestrictedFileImportSelectionSwitch: MaterialSwitch,
    private val applyHostDisplayMode: (HostDisplayMode) -> Unit,
    private val renderResultFlags: (SettingsActivityUiState) -> Unit
) {
    fun initialize() {
        val initialState = viewModel.uiState.value
        floatingLogsSwitch.isChecked = initialState.floatingLogsEnabled
        floatingLogsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFloatingLogsEnabled(isChecked)
        }
        backgroundOnlyModeSwitch.isChecked = initialState.backgroundOnlyModeEnabled
        backgroundOnlyModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBackgroundOnlyModeEnabled(isChecked)
        }
        backgroundHealthCheckSwitch.isChecked = initialState.backgroundHealthCheckEnabled
        backgroundHealthCheckSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBackgroundHealthCheckEnabled(isChecked)
        }
        pullRefreshSwitch.isChecked = initialState.pullRefreshEnabled
        pullRefreshSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setPullRefreshEnabled(isChecked)
        }
        browserEngineValueView.text = resolveBrowserEngineLabel(initialState.browserEngine)
        browserEngineRow.setOnClickListener {
            showBrowserEngineDialog(viewModel.uiState.value.browserEngine)
        }
        hostDisplayModeValueView.text = resolveHostDisplayModeLabel(initialState.hostDisplayMode)
        hostDisplayModeRow.setOnClickListener {
            showHostDisplayModeDialog(viewModel.uiState.value.hostDisplayMode)
        }
        debugDiagnosticsSwitch.isChecked = initialState.debugDiagnosticsEnabled
        debugDiagnosticsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDebugDiagnosticsEnabled(isChecked)
        }
        unrestrictedFileImportSelectionSwitch.isChecked = initialState.unrestrictedFileImportSelectionEnabled
        unrestrictedFileImportSelectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setUnrestrictedFileImportSelectionEnabled(isChecked)
        }
        applyHostDisplayMode(initialState.hostDisplayMode)

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun render(state: SettingsActivityUiState) {
        if (floatingLogsSwitch.isChecked != state.floatingLogsEnabled) {
            floatingLogsSwitch.isChecked = state.floatingLogsEnabled
        }
        if (backgroundOnlyModeSwitch.isChecked != state.backgroundOnlyModeEnabled) {
            backgroundOnlyModeSwitch.isChecked = state.backgroundOnlyModeEnabled
        }
        if (backgroundHealthCheckSwitch.isChecked != state.backgroundHealthCheckEnabled) {
            backgroundHealthCheckSwitch.isChecked = state.backgroundHealthCheckEnabled
        }
        if (pullRefreshSwitch.isChecked != state.pullRefreshEnabled) {
            pullRefreshSwitch.isChecked = state.pullRefreshEnabled
        }
        val browserEngineLabel = resolveBrowserEngineLabel(state.browserEngine)
        if (browserEngineValueView.text?.toString() != browserEngineLabel) {
            browserEngineValueView.text = browserEngineLabel
        }
        val hostDisplayModeLabel = resolveHostDisplayModeLabel(state.hostDisplayMode)
        if (hostDisplayModeValueView.text?.toString() != hostDisplayModeLabel) {
            hostDisplayModeValueView.text = hostDisplayModeLabel
        }
        if (debugDiagnosticsSwitch.isChecked != state.debugDiagnosticsEnabled) {
            debugDiagnosticsSwitch.isChecked = state.debugDiagnosticsEnabled
        }
        if (unrestrictedFileImportSelectionSwitch.isChecked != state.unrestrictedFileImportSelectionEnabled) {
            unrestrictedFileImportSelectionSwitch.isChecked = state.unrestrictedFileImportSelectionEnabled
        }
        applyHostDisplayMode(state.hostDisplayMode)
        renderResultFlags(state)
    }

    private fun showHostDisplayModeDialog(currentMode: HostDisplayMode) {
        val modes = HostDisplayMode.entries
        val optionLabels = modes.map(::resolveHostDisplayModeLabel).toTypedArray()
        val checkedItem = modes.indexOf(currentMode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_host_display_mode_dialog_title)
            .setSingleChoiceItems(optionLabels, checkedItem) { dialog, which ->
                val selectedMode = modes[which]
                viewModel.setHostDisplayMode(selectedMode)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBrowserEngineDialog(currentEngine: BrowserEngine) {
        val engines = BrowserEngine.entries
        val optionLabels = engines.map(::resolveBrowserEngineLabel).toTypedArray()
        val checkedItem = engines.indexOf(currentEngine).coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bootstrap_settings_host_browser_engine_dialog_title)
            .setSingleChoiceItems(optionLabels, checkedItem) { dialog, which ->
                val selectedEngine = engines[which]
                viewModel.setBrowserEngine(selectedEngine)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resolveBrowserEngineLabel(engine: BrowserEngine): String {
        return when (engine) {
            BrowserEngine.SYSTEM_WEBVIEW -> activity.getString(R.string.bootstrap_settings_host_browser_engine_system_webview)
            BrowserEngine.GECKOVIEW -> activity.getString(R.string.bootstrap_settings_host_browser_engine_geckoview)
        }
    }

    private fun resolveHostDisplayModeLabel(mode: HostDisplayMode): String {
        return when (mode) {
            HostDisplayMode.NORMAL -> activity.getString(R.string.bootstrap_settings_host_display_mode_normal)
            HostDisplayMode.STATUS_BAR_HIDDEN -> activity.getString(R.string.bootstrap_settings_host_display_mode_status_bar_hidden)
            HostDisplayMode.IMMERSIVE -> activity.getString(R.string.bootstrap_settings_host_display_mode_immersive)
        }
    }
}
