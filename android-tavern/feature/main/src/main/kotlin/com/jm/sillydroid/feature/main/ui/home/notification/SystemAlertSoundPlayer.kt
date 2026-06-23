package com.jm.sillydroid.feature.main.ui.home.notification

import android.content.Context
import android.media.RingtoneManager
import android.os.SystemClock

interface SystemAlertSoundPlayer {
    fun playMessageAlert(): Boolean
}

/**
 * 网页音频在 WebView/GeckoView 后台时可能被暂停或静音；消息提示音由宿主直接播放系统通知音。
 */
class AndroidSystemAlertSoundPlayer(
    private val context: Context,
    private val clockElapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val minIntervalMillis: Long = 900L
) : SystemAlertSoundPlayer {
    private var lastPlayedAtMillis: Long = 0L

    override fun playMessageAlert(): Boolean {
        val now = clockElapsedRealtime()
        if (now - lastPlayedAtMillis < minIntervalMillis) {
            return false
        }
        val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return false
        return runCatching {
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, notificationUri)
                ?: return false
            ringtone.play()
        }.onSuccess {
            lastPlayedAtMillis = now
        }.isSuccess
    }
}

object NoOpSystemAlertSoundPlayer : SystemAlertSoundPlayer {
    override fun playMessageAlert(): Boolean = false
}
