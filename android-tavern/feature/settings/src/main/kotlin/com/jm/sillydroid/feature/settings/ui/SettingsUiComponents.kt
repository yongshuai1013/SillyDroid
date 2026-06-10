package com.jm.sillydroid.feature.settings.ui

import android.content.res.ColorStateList
import android.util.TypedValue
import android.widget.ImageButton
import android.widget.ImageView
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jm.sillydroid.feature.settings.R

/**
 * 设置页动态控件统一从这里创建，避免 XML 和 Kotlin 里各自手写尺寸、padding 和触控地板。
 */
internal fun AppCompatActivity.createSettingsDenseIconButton(
    @DrawableRes iconResId: Int,
    @StringRes contentDescriptionResId: Int,
    @AttrRes tintAttr: Int = MaterialR.attr.colorOnSurfaceVariant,
    borderlessRipple: Boolean = true,
    onClick: (() -> Unit)? = null
): ImageButton {
    val size = resources.getDimensionPixelSize(R.dimen.sillydroid_settings_dense_icon_button_size)
    val padding = resources.getDimensionPixelSize(R.dimen.sillydroid_settings_dense_icon_button_padding)
    return ImageButton(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
        minimumWidth = 0
        minimumHeight = 0
        setPadding(padding, padding, padding, padding)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        background = AppCompatResources.getDrawable(
            this@createSettingsDenseIconButton,
            resolveThemeResource(
                if (borderlessRipple) {
                    android.R.attr.selectableItemBackgroundBorderless
                } else {
                    android.R.attr.selectableItemBackground
                }
            )
        )
        setImageResource(iconResId)
        imageTintList = ColorStateList.valueOf(MaterialColors.getColor(this@createSettingsDenseIconButton, tintAttr, 0))
        contentDescription = getString(contentDescriptionResId)
        if (onClick != null) {
            setOnClickListener { onClick() }
        }
    }
}

internal fun AppCompatActivity.createSettingsInputTrailingIconButton(
    @DrawableRes iconResId: Int,
    @StringRes contentDescriptionResId: Int,
    @AttrRes tintAttr: Int = MaterialR.attr.colorOnSurfaceVariant,
    onClick: (() -> Unit)? = null
): ImageButton {
    val size = resources.getDimensionPixelSize(R.dimen.sillydroid_settings_input_trailing_button_size)
    val padding = resources.getDimensionPixelSize(R.dimen.sillydroid_settings_input_trailing_button_padding)
    return ImageButton(this).apply {
        minimumWidth = 0
        minimumHeight = 0
        setPadding(padding, padding, padding, padding)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        background = AppCompatResources.getDrawable(
            this@createSettingsInputTrailingIconButton,
            resolveThemeResource(android.R.attr.selectableItemBackgroundBorderless)
        )
        setImageResource(iconResId)
        imageTintList = ColorStateList.valueOf(MaterialColors.getColor(this@createSettingsInputTrailingIconButton, tintAttr, 0))
        contentDescription = getString(contentDescriptionResId)
        if (onClick != null) {
            setOnClickListener { onClick() }
        }
        layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
    }
}

internal fun AppCompatActivity.createSettingsTextInputLayout(
    hintText: CharSequence,
    helperTextValue: CharSequence?,
    endIconMode: Int = TextInputLayout.END_ICON_NONE
): TextInputLayout {
    return TextInputLayout(this, null, MaterialR.attr.textInputStyle).apply {
        hint = hintText
        helperText = helperTextValue
        this.endIconMode = endIconMode
    }
}

internal fun TextInputLayout.createSettingsEditText(): TextInputEditText {
    return TextInputEditText(context, null, android.R.attr.editTextStyle)
}

internal fun TextInputLayout.applySettingsEndIconStyle() {
    val button = findViewById<ImageButton>(com.google.android.material.R.id.text_input_end_icon) ?: return
    val size = resources.getDimensionPixelSize(R.dimen.sillydroid_settings_input_trailing_button_size)
    val padding = resources.getDimensionPixelSize(R.dimen.sillydroid_settings_input_trailing_button_padding)
    val marginEndPx = resources.getDimensionPixelSize(R.dimen.sillydroid_settings_input_trailing_button_margin_end)
    val selectableItemBackground = TypedValue().also { typedValue ->
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
    }.resourceId
    val ripple = AppCompatResources.getDrawable(context, selectableItemBackground)

    // Material 默认 end icon 会贴着 TextInputLayout 右边界，borderless ripple 还会跑出输入框描边。
    // 设置页的输入框尾部动作统一收在描边内部，并保留可点击触控区。
    button.minimumWidth = 0
    button.minimumHeight = 0
    button.setPadding(padding, padding, padding, padding)
    button.scaleType = ImageView.ScaleType.CENTER_INSIDE
    button.background = ripple
    val params = (button.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
        width = size
        height = size
        rightMargin = marginEndPx
    } ?: ViewGroup.MarginLayoutParams(size, size).apply {
        rightMargin = marginEndPx
    }
    button.layoutParams = params
}

internal fun AppCompatActivity.resolveThemeResource(@AttrRes attrRes: Int): Int {
    val typedValue = android.util.TypedValue()
    theme.resolveAttribute(attrRes, typedValue, true)
    return typedValue.resourceId
}
