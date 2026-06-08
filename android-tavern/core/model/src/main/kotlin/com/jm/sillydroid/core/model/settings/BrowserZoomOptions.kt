package com.jm.sillydroid.core.model.settings

import kotlin.math.roundToInt

object BrowserZoomOptions {
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 150
    const val STEP_PERCENT = 5
    const val DEFAULT_PERCENT = 100

    val percentOptions: List<Int> = (MIN_PERCENT..MAX_PERCENT step STEP_PERCENT).toList()

    fun sanitize(percent: Int): Int {
        val clamped = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        val stepped = MIN_PERCENT + ((clamped - MIN_PERCENT).toFloat() / STEP_PERCENT).roundToInt() * STEP_PERCENT
        return stepped.coerceIn(MIN_PERCENT, MAX_PERCENT)
    }

    fun percentFromSliderProgress(progress: Int): Int {
        return sanitize(MIN_PERCENT + progress * STEP_PERCENT)
    }

    fun sliderProgress(percent: Int): Int {
        return (sanitize(percent) - MIN_PERCENT) / STEP_PERCENT
    }

    fun toZoomFactor(percent: Int): Float {
        return sanitize(percent) / 100f
    }
}
