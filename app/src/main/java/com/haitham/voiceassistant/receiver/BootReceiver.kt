package com.haitham.voiceassistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.haitham.voiceassistant.service.AssistantNotifications
import com.haitham.voiceassistant.service.VoiceForegroundService
import com.haitham.voiceassistant.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles "start on boot" within Android's rules:
 *  - Android 8–10 (API 26–29): the microphone service may be started directly.
 *  - Android 11+ (API 30+): a microphone foreground service started from
 *    BOOT_COMPLETED gets no microphone access (and Android 14/15 refuse to start it),
 *    so we only post a notification; one tap starts the assistant the official way.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val s = SettingsRepository(context).current()
                if (!s.autoStartOnBoot) return@launch
                AssistantNotifications.createChannels(context)
                if (Build.VERSION.SDK_INT < 30) {
                    try { VoiceForegroundService.start(context) } catch (e: Exception) {
                        Log.w("BootReceiver", "direct start failed", e); AssistantNotifications.bootReminder(context)
                    }
                } else {
                    AssistantNotifications.bootReminder(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
