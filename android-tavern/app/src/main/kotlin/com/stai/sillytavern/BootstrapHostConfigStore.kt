package com.stai.sillytavern

import android.content.Context

internal class BootstrapHostConfigStore(context: Context) {
    companion object {
        private const val preferencesName = "bootstrap-host-config"
        private const val servicePortKey = "service-port"
    }

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    var servicePort: Int
        get() = sanitizeServicePort(preferences.getInt(servicePortKey, BootConfig.defaultServicePort))
        set(value) {
            preferences.edit()
                .putInt(servicePortKey, sanitizeServicePort(value))
                .apply()
        }

    private fun sanitizeServicePort(value: Int): Int {
        return value.takeIf { it in 1..65535 } ?: BootConfig.defaultServicePort
    }
}