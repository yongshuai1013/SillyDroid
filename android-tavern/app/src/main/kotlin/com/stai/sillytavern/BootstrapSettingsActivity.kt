package com.stai.sillytavern

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BootstrapSettingsActivity : AppCompatActivity() {
    companion object {
        private const val resultShouldStartKey = "bootstrap-settings.result.start"

        fun createIntent(activity: Activity): Intent {
            return Intent(activity, BootstrapSettingsActivity::class.java)
        }

        fun shouldStartBootstrap(data: Intent?): Boolean {
            return data?.getBooleanExtra(resultShouldStartKey, false) == true
        }
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabLayout: TabLayout
    private lateinit var rootView: View
    private lateinit var topShellView: View
    private lateinit var scrollView: NestedScrollView
    private lateinit var actionBarView: View
    private lateinit var loadingIndicator: LinearProgressIndicator
    private lateinit var dataPanelView: View
    private lateinit var quickFieldContainer: LinearLayout
    private lateinit var settingsPanelView: View
    private lateinit var sectionContainer: LinearLayout
    private lateinit var configPathView: TextView
    private lateinit var warningView: TextView
    private lateinit var restoreDefaultsButton: ImageButton
    private lateinit var importButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private lateinit var saveStartButton: MaterialButton

    private val configRepository by lazy { TavernConfigRepository(this) }
    private val archiveManager by lazy { TavernDataArchiveManager(this) }
    private val hostConfigStore by lazy { BootstrapHostConfigStore(this) }
    private lateinit var screenController: BootstrapSettingsScreenController
    private lateinit var formController: BootstrapSettingsFormController
    private lateinit var settingsCoordinator: BootstrapSettingsSettingsCoordinator
    private lateinit var dataCoordinator: BootstrapSettingsDataCoordinator

    private val exportArchiveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { targetUri ->
        if (targetUri != null) {
            dataCoordinator.exportArchive(targetUri)
        }
    }

    private val importArchiveLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { sourceUri ->
        if (sourceUri != null) {
            dataCoordinator.inspectArchive(sourceUri) { preview ->
                screenController.confirmImport(preview) {
                    dataCoordinator.importArchive(sourceUri, preview)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_bootstrap_settings)
        bindViews()
        initializeControllers()
        screenController.initialize()
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.bootstrap_settings_title)
        toolbar.setNavigationOnClickListener {
            settingsCoordinator.attemptFinish()
        }
        onBackPressedDispatcher.addCallback(this) {
            settingsCoordinator.attemptFinish()
        }

        restoreDefaultsButton.setOnClickListener {
            screenController.confirmRestoreDefaults {
                dataCoordinator.restoreDefaults()
            }
        }
        importButton.setOnClickListener {
            importArchiveLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        exportButton.setOnClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            exportArchiveLauncher.launch(getString(R.string.bootstrap_settings_export_name, timestamp))
        }
        saveStartButton.setOnClickListener {
            settingsCoordinator.saveAndStart()
        }

        screenController.setBusy(true)
        window.decorView.post {
            startService(StartupCoordinatorService.createStopForSettingsIntent(this))
            settingsCoordinator.loadConfiguration()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            settingsCoordinator.attemptFinish()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    private fun bindViews() {
        rootView = findViewById(R.id.bootstrapSettingsRoot)
        topShellView = findViewById(R.id.bootstrapSettingsTopShell)
        toolbar = findViewById(R.id.bootstrapSettingsToolbar)
        tabLayout = findViewById(R.id.bootstrapSettingsTabs)
        scrollView = findViewById(R.id.bootstrapSettingsScrollView)
        actionBarView = findViewById(R.id.bootstrapSettingsActionBar)
        loadingIndicator = findViewById(R.id.bootstrapSettingsLoading)
        dataPanelView = findViewById(R.id.bootstrapSettingsDataPanel)
        quickFieldContainer = findViewById(R.id.bootstrapSettingsQuickFieldContainer)
        settingsPanelView = findViewById(R.id.bootstrapSettingsSettingsPanel)
        sectionContainer = findViewById(R.id.bootstrapSettingsSectionContainer)
        configPathView = findViewById(R.id.bootstrapSettingsConfigPath)
        warningView = findViewById(R.id.bootstrapSettingsWarning)
        restoreDefaultsButton = findViewById(R.id.bootstrapSettingsRestoreDefaultsButton)
        importButton = findViewById(R.id.bootstrapSettingsImportButton)
        exportButton = findViewById(R.id.bootstrapSettingsExportButton)
        saveStartButton = findViewById(R.id.bootstrapSettingsSaveButton)
    }

    private fun initializeControllers() {
        screenController = BootstrapSettingsScreenController(
            activity = this,
            rootView = rootView,
            topShellView = topShellView,
            scrollView = scrollView,
            actionBarView = actionBarView,
            tabLayout = tabLayout,
            dataPanelView = dataPanelView,
            settingsPanelView = settingsPanelView,
            configPathView = configPathView,
            warningView = warningView,
            loadingIndicator = loadingIndicator,
            restoreDefaultsButton = restoreDefaultsButton,
            importButton = importButton,
            exportButton = exportButton,
            saveStartButton = saveStartButton
        )
        formController = BootstrapSettingsFormController(
            activity = this,
            configRepository = configRepository,
            quickFieldContainer = quickFieldContainer,
            sectionContainer = sectionContainer,
            scrollView = scrollView,
            onFieldEdited = { changedFieldPath -> settingsCoordinator.clearBlockingFeedback(changedFieldPath) },
            onFormChanged = { settingsCoordinator.refreshDirtyState() }
        )
        settingsCoordinator = BootstrapSettingsSettingsCoordinator(
            activity = this,
            configRepository = configRepository,
            formController = formController,
            screenController = screenController,
            onStartBootstrapConfirmed = {
                setResult(Activity.RESULT_OK, Intent().putExtra(resultShouldStartKey, true))
                finish()
            }
        )
        dataCoordinator = BootstrapSettingsDataCoordinator(
            activity = this,
            configRepository = configRepository,
            archiveManager = archiveManager,
            hostConfigStore = hostConfigStore,
            setBusy = screenController::setBusy,
            applyDraft = settingsCoordinator::applyDraftConfiguration,
            reloadConfiguration = settingsCoordinator::loadConfiguration,
            showDataError = settingsCoordinator::showValidationMessage,
            showBanner = { message -> screenController.showBanner(message) },
            showMessage = screenController::showMessage,
            updateDirtyState = settingsCoordinator::refreshDirtyState
        )
    }
}