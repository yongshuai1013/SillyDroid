package com.jm.sillydroid.feature.settings.ui.screen

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.jm.sillydroid.domain.bootstrap.RuntimePatchModuleMetadataSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingMetadataSnapshot
import com.jm.sillydroid.domain.bootstrap.RuntimePatchSettingTypes
import com.jm.sillydroid.feature.settings.R
import com.jm.sillydroid.feature.settings.model.SettingsActivityUiState
import com.jm.sillydroid.feature.settings.ui.createSettingsDenseIconButton
import com.jm.sillydroid.feature.settings.ui.createSettingsEditText
import com.jm.sillydroid.feature.settings.ui.createSettingsTextInputLayout
import com.jm.sillydroid.feature.settings.viewmodel.SettingsActivityViewModel

class RuntimePatchBottomSheetController(
    private val activity: AppCompatActivity,
    private val viewModel: SettingsActivityViewModel,
    private val onServiceRestartRequired: () -> Unit
) {
    fun show(state: SettingsActivityUiState = viewModel.uiState.value) {
        val dialog = BottomSheetDialog(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimen(R.dimen.sillydroid_dialog_content_padding_horizontal),
                dimen(R.dimen.sillydroid_dialog_content_padding_vertical),
                dimen(R.dimen.sillydroid_dialog_content_padding_horizontal),
                dimen(R.dimen.sillydroid_dialog_content_padding_vertical)
            )
        }

        content.addView(createTitleRow(dialog))
        content.addView(createSummary())
        content.addView(createWarning())
        state.tavernRuntimePatchMetadata?.frameworkVersion
            ?.takeIf { version -> version.isNotBlank() }
            ?.let { version -> content.addView(createMetaText(activity.getString(R.string.bootstrap_settings_host_runtime_patch_framework_version, version))) }

        val modules = state.tavernRuntimePatchMetadata?.modules.orEmpty()
        if (modules.isEmpty()) {
            content.addView(createEmptyText())
        } else {
            modules.forEach { module ->
                content.addView(createModuleCard(module))
            }
        }

        dialog.setContentView(createSheetScrollContainer(content))
        dialog.setOnShowListener { expandBottomSheet(dialog) }
        dialog.show()
    }

    private fun createSheetScrollContainer(content: View): ScrollView {
        val maxHeight = (activity.resources.displayMetrics.heightPixels * bottomSheetHeightFraction).toInt()
        return ScrollView(activity).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxHeight
            )
            addView(content)
        }
    }

    private fun expandBottomSheet(dialog: BottomSheetDialog) {
        val bottomSheet = dialog.findViewById<FrameLayout>(MaterialR.id.design_bottom_sheet) ?: return
        val targetHeight = (activity.resources.displayMetrics.heightPixels * bottomSheetHeightFraction).toInt()
        bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
            height = targetHeight
        }
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.peekHeight = targetHeight
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun createTitleRow(dialog: BottomSheetDialog): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(activity).apply {
                    setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsSectionTitle)
                    text = activity.getString(R.string.bootstrap_settings_host_runtime_patch_sheet_title)
                    setTextColor(resolveColor(MaterialR.attr.colorOnSurface))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            addView(
                activity.createSettingsDenseIconButton(
                    iconResId = R.drawable.ic_close,
                    contentDescriptionResId = R.string.bootstrap_settings_host_runtime_patch_close,
                    onClick = dialog::dismiss
                )
            )
        }
    }

    private fun createSummary(): TextView {
        return TextView(activity).apply {
            setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsBody)
            text = activity.getString(R.string.bootstrap_settings_host_runtime_patch_sheet_summary)
            setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, dimen(R.dimen.sillydroid_space_sm), 0, dimen(R.dimen.sillydroid_space_sm))
        }
    }

    private fun createWarning(): MaterialCardView {
        val warningText = TextView(activity).apply {
            setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsBody)
            text = activity.getString(R.string.bootstrap_settings_host_runtime_patch_sheet_warning)
            setTextColor(resolveColor(MaterialR.attr.colorError))
        }
        return MaterialCardView(activity, null, MaterialR.attr.materialCardViewStyle).apply {
            radius = dimen(R.dimen.sillydroid_nested_card_radius).toFloat()
            strokeWidth = dimen(R.dimen.sillydroid_space_xxxs)
            strokeColor = resolveColor(MaterialR.attr.colorError)
            setCardBackgroundColor(resolveColor(MaterialR.attr.colorErrorContainer))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dimen(R.dimen.sillydroid_space_sm)
                bottomMargin = dimen(R.dimen.sillydroid_space_md)
            }
            addView(
                FrameLayout(activity).apply {
                    setPadding(
                        dimen(R.dimen.sillydroid_panel_padding),
                        dimen(R.dimen.sillydroid_panel_padding),
                        dimen(R.dimen.sillydroid_panel_padding),
                        dimen(R.dimen.sillydroid_panel_padding)
                    )
                    addView(warningText)
                }
            )
        }
    }

    private fun createMetaText(textValue: String): TextView {
        return TextView(activity).apply {
            setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsMeta)
            text = textValue
            setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, 0, 0, dimen(R.dimen.sillydroid_space_sm))
        }
    }

    private fun createEmptyText(): TextView {
        return createMetaText(activity.getString(R.string.bootstrap_settings_host_runtime_patch_sheet_empty)).apply {
            setPadding(0, dimen(R.dimen.sillydroid_space_md), 0, dimen(R.dimen.sillydroid_space_md))
        }
    }

    private fun createModuleCard(module: RuntimePatchModuleMetadataSnapshot): MaterialCardView {
        val card = MaterialCardView(activity, null, MaterialR.attr.materialCardViewStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dimen(R.dimen.sillydroid_space_md)
            }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding)
            )
        }

        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(createModuleTitle(module).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(createModuleVersionPill(module))

        val importantValueView = createImportantValueText(module)
        topRow.addView(importantValueView)

        val settingsButton = createSettingsButton(module).apply {
            isVisible = module.settings.isNotEmpty()
            setOnClickListener {
                showModuleSettingsEditor(module) {
                    importantValueView.text = importantSettingsLabel(module)
                    importantValueView.isVisible = importantValueView.text.isNotBlank()
                }
            }
        }
        topRow.addView(settingsButton)

        val switch = createSettingsSwitch().apply {
            isChecked = viewModel.isRuntimePatchModuleEnabled(module.id)
            isEnabled = module.id.isNotBlank()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dimen(R.dimen.sillydroid_space_sm)
            }
        }
        switch.setOnClickListener {
            updateModuleEnabled(module.id, switch.isChecked)
        }
        topRow.addView(switch)

        content.addView(topRow)
        content.addView(createModuleSummary(module))
        if (!module.manifestIncluded) {
            content.addView(createMetaText(activity.getString(R.string.bootstrap_settings_host_runtime_patch_manifest_missing)))
        }
        card.addView(content)
        return card
    }

    private fun createImportantValueText(module: RuntimePatchModuleMetadataSnapshot): TextView {
        return TextView(activity).apply {
            setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsBadge)
            text = importantSettingsLabel(module)
            isVisible = text.isNotBlank()
            setTextColor(resolveColor(MaterialR.attr.colorPrimary))
            gravity = Gravity.CENTER
            setPadding(dimen(R.dimen.sillydroid_space_sm), 0, dimen(R.dimen.sillydroid_space_sm), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dimen(R.dimen.sillydroid_space_sm)
            }
        }
    }

    private fun createSettingsButton(module: RuntimePatchModuleMetadataSnapshot): ImageButton {
        return activity.createSettingsDenseIconButton(
            iconResId = R.drawable.ic_settings_gear,
            contentDescriptionResId = R.string.bootstrap_settings_host_runtime_patch_settings_action
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                dimen(R.dimen.sillydroid_settings_dense_icon_button_size),
                dimen(R.dimen.sillydroid_settings_dense_icon_button_size)
            ).apply {
                leftMargin = dimen(R.dimen.sillydroid_space_sm)
            }
            isEnabled = module.id.isNotBlank()
        }
    }

    private fun showModuleSettingsEditor(
        module: RuntimePatchModuleMetadataSnapshot,
        onSettingsChanged: () -> Unit
    ) {
        if (module.settings.isEmpty()) {
            Toast.makeText(activity, R.string.bootstrap_settings_host_runtime_patch_settings_empty, Toast.LENGTH_SHORT).show()
            return
        }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val content = createSettingsDialogRoot().apply {
            addView(createDialogTitleRow(moduleDisplayTitle(module)) { dialog?.dismiss() })
            module.description.takeIf { description -> description.isNotBlank() }?.let { description ->
                addView(createDialogSummary(description))
            }
            addView(createDialogSummary(activity.getString(R.string.bootstrap_settings_host_runtime_patch_restart_hint)).apply {
                setTextColor(resolveColor(MaterialR.attr.colorPrimary))
            })
            val settingsContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dimen(R.dimen.sillydroid_space_sm), 0, 0)
            }
            module.settings.forEachIndexed { index, setting ->
                settingsContainer.addView(createSettingRow(module, setting, onSettingsChanged).apply {
                    if (index > 0) {
                        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                            topMargin = dimen(R.dimen.sillydroid_space_md)
                        }
                    }
                })
            }
            addView(settingsContainer)
        }
        dialog = MaterialAlertDialogBuilder(activity)
            .setView(
                ScrollView(activity).apply {
                    addView(content)
                }
            )
            .create()
        dialog.show()
    }

    private fun createSettingsDialogRoot(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimen(R.dimen.sillydroid_dialog_content_padding_horizontal),
                dimen(R.dimen.sillydroid_dialog_content_padding_vertical),
                dimen(R.dimen.sillydroid_dialog_content_padding_horizontal),
                dimen(R.dimen.sillydroid_dialog_content_padding_vertical)
            )
        }
    }

    private fun createDialogTitleRow(title: String, onClose: () -> Unit): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(activity).apply {
                    setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsSectionTitle)
                    text = title
                    setTextColor(resolveColor(MaterialR.attr.colorOnSurface))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            addView(
                activity.createSettingsDenseIconButton(
                    iconResId = R.drawable.ic_close,
                    contentDescriptionResId = R.string.bootstrap_settings_host_runtime_patch_close,
                    onClick = onClose
                )
            )
        }
    }

    private fun createDialogSummary(textValue: String): TextView {
        return TextView(activity).apply {
            setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsBody)
            text = textValue
            setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, dimen(R.dimen.sillydroid_space_sm), 0, 0)
        }
    }

    private fun createSettingRow(
        module: RuntimePatchModuleMetadataSnapshot,
        setting: RuntimePatchSettingMetadataSnapshot,
        onSettingChanged: () -> Unit
    ): MaterialCardView {
        val normalizedType = RuntimePatchSettingTypes.normalize(setting.type)
        val card = MaterialCardView(activity, null, MaterialR.attr.materialCardViewStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            foreground = selectableItemBackground()
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding),
                dimen(R.dimen.sillydroid_panel_padding)
            )
        }
        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val currentValue = viewModel.resolveRuntimePatchSettingValue(module.id, setting.key, setting.defaultValue)
        textColumn.addView(createSettingTitle(setting))
        userFacingPatchText(setting.description).takeIf { description -> description.isNotBlank() }?.let { description ->
            textColumn.addView(createSettingDescription(description))
        }
        val settingValueView = createSettingCurrentValue(setting, currentValue)
        row.addView(textColumn)

        when (normalizedType) {
            "switch" -> {
                val toggle = createSettingsSwitch().apply {
                    isChecked = currentValue.toBooleanStrictOrNull() ?: setting.defaultValue.toBooleanStrictOrNull() ?: false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = dimen(R.dimen.sillydroid_space_md)
                    }
                }
                toggle.setOnClickListener {
                    updateSetting(module.id, setting, toggle.isChecked.toString())
                    settingValueView.text = currentValueLabel(setting, toggle.isChecked.toString())
                    onSettingChanged()
                }
                card.setOnClickListener {
                    toggle.isChecked = !toggle.isChecked
                    updateSetting(module.id, setting, toggle.isChecked.toString())
                    settingValueView.text = currentValueLabel(setting, toggle.isChecked.toString())
                    onSettingChanged()
                }
                row.addView(toggle)
            }
            "checkbox" -> {
                val checkbox = MaterialCheckBox(activity).apply {
                    isChecked = currentValue.toBooleanStrictOrNull() ?: setting.defaultValue.toBooleanStrictOrNull() ?: false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        leftMargin = dimen(R.dimen.sillydroid_space_md)
                    }
                }
                checkbox.setOnClickListener {
                    updateSetting(module.id, setting, checkbox.isChecked.toString())
                    settingValueView.text = currentValueLabel(setting, checkbox.isChecked.toString())
                    onSettingChanged()
                }
                card.setOnClickListener {
                    checkbox.isChecked = !checkbox.isChecked
                    updateSetting(module.id, setting, checkbox.isChecked.toString())
                    settingValueView.text = currentValueLabel(setting, checkbox.isChecked.toString())
                    onSettingChanged()
                }
                row.addView(checkbox)
            }
            else -> {
                settingValueView.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dimen(R.dimen.sillydroid_space_md)
                }
                card.setOnClickListener {
                    val latestValue = viewModel.resolveRuntimePatchSettingValue(module.id, setting.key, setting.defaultValue)
                    showSettingEditor(module.id, setting, latestValue) { nextValue ->
                        settingValueView.text = currentValueLabel(setting, nextValue)
                        onSettingChanged()
                    }
                }
                row.addView(settingValueView)
            }
        }

        card.addView(row)
        return card
    }

    private fun createSettingTitle(setting: RuntimePatchSettingMetadataSnapshot): TextView {
        return TextView(activity).apply {
            setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsCardTitle)
            text = setting.title.ifBlank { setting.key }
            setTextColor(resolveColor(MaterialR.attr.colorOnSurface))
        }
    }

    private fun createSettingDescription(description: String): TextView {
        return TextView(activity).apply {
            setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsBody)
            text = description
            setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, dimen(R.dimen.sillydroid_space_xs), 0, 0)
        }
    }

    private fun createSettingCurrentValue(
        setting: RuntimePatchSettingMetadataSnapshot,
        currentValue: String
    ): TextView {
        return TextView(activity).apply {
            setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsButton)
            text = currentValueLabel(setting, currentValue)
            setTextColor(resolveColor(MaterialR.attr.colorPrimary))
            gravity = Gravity.CENTER
            maxLines = 1
        }
    }

    private fun currentValueLabel(setting: RuntimePatchSettingMetadataSnapshot, value: String): String {
        return activity.getString(
            R.string.bootstrap_settings_host_runtime_patch_current_value,
            resolveDisplayValue(setting, value)
        )
    }

    private fun createSettingPickerTitle(setting: RuntimePatchSettingMetadataSnapshot): String {
        return activity.getString(
            R.string.bootstrap_settings_host_runtime_patch_picker_title,
            setting.title.ifBlank { setting.key }
        )
    }

    private fun showSettingEditor(
        moduleId: String,
        setting: RuntimePatchSettingMetadataSnapshot,
        currentValue: String,
        onDisplayValueChanged: (String) -> Unit
    ) {
        val normalizedType = RuntimePatchSettingTypes.normalize(setting.type)
        if (setting.options.isNotEmpty()) {
            showSelectSettingEditor(moduleId, setting, currentValue, onDisplayValueChanged)
        } else {
            showInputSettingEditor(moduleId, setting, currentValue, normalizedType, onDisplayValueChanged)
        }
    }

    private fun showSelectSettingEditor(
        moduleId: String,
        setting: RuntimePatchSettingMetadataSnapshot,
        currentValue: String,
        onDisplayValueChanged: (String) -> Unit
    ) {
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val content = createSettingsDialogRoot().apply {
            addView(createDialogTitleRow(createSettingPickerTitle(setting)) { dialog?.dismiss() })
            userFacingPatchText(setting.description).takeIf { description -> description.isNotBlank() }?.let { description ->
                addView(createDialogSummary(description))
            }
            val optionsContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dimen(R.dimen.sillydroid_space_sm), 0, 0)
            }
            setting.options.forEachIndexed { index, option ->
                val selected = option.value == currentValue
                optionsContainer.addView(
                    createOptionCard(
                        label = option.label,
                        description = userFacingPatchText(option.description),
                        selected = selected,
                        onClick = {
                            updateSetting(moduleId, setting, option.value)
                            onDisplayValueChanged(option.value)
                            dialog?.dismiss()
                        }
                    ).apply {
                        if (index > 0) {
                            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                                topMargin = dimen(R.dimen.sillydroid_space_md)
                            }
                        }
                    }
                )
            }
            addView(optionsContainer)
        }
        dialog = MaterialAlertDialogBuilder(activity)
            .setView(
                ScrollView(activity).apply {
                    addView(content)
                }
            )
            .create()
        dialog.show()
    }

    private fun createOptionCard(
        label: String,
        description: String,
        selected: Boolean,
        onClick: () -> Unit
    ): MaterialCardView {
        return MaterialCardView(activity, null, MaterialR.attr.materialCardViewStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (selected) {
                setCardBackgroundColor(resolveColor(MaterialR.attr.colorPrimaryContainer))
                strokeColor = resolveColor(MaterialR.attr.colorPrimary)
            }
            isClickable = true
            isFocusable = true
            foreground = selectableItemBackground()
            setOnClickListener { onClick() }
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        dimen(R.dimen.sillydroid_panel_padding),
                        dimen(R.dimen.sillydroid_panel_padding),
                        dimen(R.dimen.sillydroid_panel_padding),
                        dimen(R.dimen.sillydroid_panel_padding)
                    )
                    addView(
                        LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            addView(
                                TextView(activity).apply {
                                    setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsCardTitle)
                                    text = label
                                    setTextColor(resolveColor(MaterialR.attr.colorOnSurface))
                                }
                            )
                            if (description.isNotBlank()) {
                                addView(createSettingDescription(description))
                            }
                        }
                    )
                    if (selected) {
                        addView(
                            TextView(activity).apply {
                                setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsButton)
                                text = activity.getString(R.string.bootstrap_settings_host_runtime_patch_selected_value)
                                setTextColor(resolveColor(MaterialR.attr.colorPrimary))
                                gravity = Gravity.CENTER
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    leftMargin = dimen(R.dimen.sillydroid_space_md)
                                }
                            }
                        )
                    }
                }
            )
        }
    }

    private fun showInputSettingEditor(
        moduleId: String,
        setting: RuntimePatchSettingMetadataSnapshot,
        currentValue: String,
        normalizedType: String,
        onDisplayValueChanged: (String) -> Unit
    ) {
        val inputLayout = activity.createSettingsTextInputLayout(
            hintText = setting.title.ifBlank { setting.key },
            helperTextValue = userFacingPatchText(setting.description).takeIf { description -> description.isNotBlank() }
        ).apply {
            setPadding(
                dimen(R.dimen.sillydroid_dialog_content_padding_horizontal),
                dimen(R.dimen.sillydroid_space_md),
                dimen(R.dimen.sillydroid_dialog_content_padding_horizontal),
                0
            )
        }
        val input = inputLayout.createSettingsEditText().apply {
            setText(currentValue)
            inputType = if (normalizedType == "number") {
                android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        inputLayout.addView(input)
        MaterialAlertDialogBuilder(activity)
            .setTitle(createSettingPickerTitle(setting))
            .setView(inputLayout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val normalizedValue = sanitizeInputValue(setting, input.text?.toString().orEmpty(), normalizedType)
                updateSetting(moduleId, setting, normalizedValue)
                onDisplayValueChanged(normalizedValue)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sanitizeInputValue(
        setting: RuntimePatchSettingMetadataSnapshot,
        rawValue: String,
        normalizedType: String
    ): String {
        val trimmed = rawValue.trim()
        if (normalizedType != "number") {
            return trimmed.ifBlank { setting.defaultValue }
        }
        val value = trimmed.toDoubleOrNull() ?: setting.defaultValue.toDoubleOrNull() ?: setting.min ?: 0.0
        val clamped = value
            .coerceAtLeast(setting.min ?: value)
            .coerceAtMost(setting.max ?: value)
        return if (setting.type.equals("integer", ignoreCase = true) || setting.type.equals("int", ignoreCase = true)) {
            clamped.toInt().toString()
        } else {
            clamped.toString().trimEnd('0').trimEnd('.')
        }
    }

    private fun importantSettingsLabel(module: RuntimePatchModuleMetadataSnapshot): String {
        val importantSettings = module.settings.filter { setting -> setting.important }
        return when (importantSettings.size) {
            0 -> ""
            1 -> {
                val setting = importantSettings.first()
                val currentValue = viewModel.resolveRuntimePatchSettingValue(module.id, setting.key, setting.defaultValue)
                resolveDisplayValue(setting, currentValue)
            }
            else -> importantSettings.joinToString(" · ") { setting ->
                val currentValue = viewModel.resolveRuntimePatchSettingValue(module.id, setting.key, setting.defaultValue)
                "${setting.title.ifBlank { setting.key }}=${resolveDisplayValue(setting, currentValue)}"
            }
        }
    }

    private fun resolveDisplayValue(setting: RuntimePatchSettingMetadataSnapshot, value: String): String {
        return setting.options.firstOrNull { option -> option.value == value }?.label
            ?: value.ifBlank { setting.defaultValue }
    }

    private fun userFacingPatchText(rawText: String): String {
        return when (rawText.trim()) {
            "控制 /api/characters/all 首次索引角色卡时同时处理的数量。auto 会按 CPU 线程自动选择并限制上限；数值越高越快但内存峰值越高。" ->
                "控制首次打开角色列表时同时处理的角色数量。自动模式适合大多数设备；数字越大越快，但更容易占用内存。"
            "按设备可用 CPU 线程自动选择。" ->
                "推荐。根据当前设备自动选择，优先保证稳定。"
            "更保守，适合低内存或容易 OOM 的设备。" ->
                "更保守，适合低内存或容易闪退的设备。"
            "更激进，适合内存充足和 CPU 较强的设备。" ->
                "更激进，适合内存充足、角色很多且想更快完成索引的设备。"
            else -> rawText
        }
    }

    private fun createModuleTitle(module: RuntimePatchModuleMetadataSnapshot): TextView {
        return TextView(activity).apply {
            setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsCardTitle)
            text = moduleDisplayTitle(module)
            setTextColor(resolveColor(MaterialR.attr.colorOnSurface))
        }
    }

    private fun createModuleSummary(module: RuntimePatchModuleMetadataSnapshot): TextView {
        return TextView(activity).apply {
            setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsBody)
            text = module.description.ifBlank { module.id }
            setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
            setPadding(0, dimen(R.dimen.sillydroid_space_xs), 0, 0)
        }
    }

    private fun createModuleVersionPill(module: RuntimePatchModuleMetadataSnapshot): TextView {
        return TextView(activity).apply {
            setTextAppearance(R.style.TextAppearance_SillyDroid_SettingsMeta)
            text = moduleVersionInlineLabel(module)
            setTextColor(resolveColor(MaterialR.attr.colorOnSurfaceVariant))
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dimen(R.dimen.sillydroid_space_sm), 0, dimen(R.dimen.sillydroid_space_sm), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dimen(R.dimen.sillydroid_space_sm)
            }
        }
    }

    private fun moduleDisplayTitle(module: RuntimePatchModuleMetadataSnapshot): String {
        return module.title.ifBlank { module.id.ifBlank { unknownValue } }
    }

    private fun moduleVersionInlineLabel(module: RuntimePatchModuleMetadataSnapshot): String {
        val version = module.version.ifBlank { unknownValue }
        val supportedVersions = if (module.supportedTavernVersions.isEmpty()) {
            activity.getString(R.string.bootstrap_settings_host_runtime_patch_supported_versions_unknown)
                .substringAfter('：')
        } else {
            module.supportedTavernVersions.joinToString(", ")
        }
        return "v$version / $supportedVersions"
    }

    private fun createSettingsSwitch(): MaterialSwitch {
        return MaterialSwitch(activity, null, MaterialR.attr.materialSwitchStyle)
    }

    private fun showRestartHint() {
        Toast.makeText(
            activity,
            R.string.bootstrap_settings_host_runtime_patch_restart_hint,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showServiceRestartRequiredHint() {
        // Patch 配置只能在下一次 Node 服务启动前注入；抽屉只负责保存配置，
        // 真正的重启动作统一交给设置页底部共享操作条处理。
        showRestartHint()
        onServiceRestartRequired()
    }

    private fun updateModuleEnabled(moduleId: String, enabled: Boolean) {
        val changed = viewModel.setRuntimePatchModuleEnabled(moduleId, enabled)
        if (changed) {
            showServiceRestartRequiredHint()
        }
    }

    private fun updateSetting(moduleId: String, setting: RuntimePatchSettingMetadataSnapshot, value: String) {
        // 默认值不保存为覆盖项，避免用户切回默认后仍在崩溃日志里看起来像手动配置。
        val normalizedValue = value.trim().takeUnless { candidate -> candidate == setting.defaultValue.trim() }.orEmpty()
        val changed = viewModel.setRuntimePatchSettingOverride(
            moduleId = moduleId,
            settingKey = setting.key,
            value = normalizedValue
        )
        if (changed && setting.restartRequired) {
            showServiceRestartRequiredHint()
        }
    }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val outValue = android.util.TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return AppCompatResources.getDrawable(activity, outValue.resourceId)
    }

    private fun resolveColor(attrRes: Int): Int {
        return com.google.android.material.color.MaterialColors.getColor(activity, attrRes, android.graphics.Color.TRANSPARENT)
    }

    private fun dimen(resId: Int): Int {
        return activity.resources.getDimensionPixelSize(resId)
    }

    private companion object {
        private const val bottomSheetHeightFraction = 0.88f
        private const val unknownValue = "unknown"
    }
}
