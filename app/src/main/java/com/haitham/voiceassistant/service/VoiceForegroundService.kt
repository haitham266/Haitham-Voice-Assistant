package com.haitham.voiceassistant.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.haitham.voiceassistant.assistant.AssistantController
import com.haitham.voiceassistant.assistant.AssistantRepository
import com.haitham.voiceassistant.model.AssistantStatus
import com.haitham.voiceassistant.settings.AssistantSettings
import com.haitham.voiceassistant.settings.SettingsRepository
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Foreground service of type "microphone". It keeps a visible, ongoing
 * notification while the assistant listens, as required by Android 8+ and the
 * Android 14+ foreground-service-type rules. It must be started while the app
 * is visible (button in the UI, notification tap, Quick action), never silently
 * from the background on Android 11+.
 */
class VoiceForegroundService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.haitham.voiceassistant.START"
        const val ACTION_STOP = "com.haitham.voiceassistant.STOP"
        const val ACTION_TEST = "com.haitham.voiceassistant.TEST"
        private const val TAG = "VoiceService"

        fun start(context: Context, action: String = ACTION_START) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceForegroundService::class.java).setAction(action))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VoiceForegroundService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var settingsRepo: SettingsRepository
    private var controller: AssistantController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakePhrase = AssistantSettings.DEFAULT_WAKE_PHRASE
    private var userStopped = false

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        AssistantNotifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { userStopped = true; stopEverything(); return START_NOT_STICKY }
            ACTION_TEST -> { if (!ensureStarted()) return START_NOT_STICKY; controller?.runRecognitionTest() }
            else -> if (!ensureStarted()) return START_NOT_STICKY
        }
        // Android 11+ does not give microphone access to a service restarted from the
        // background, so a sticky restart would be useless there.
        return if (Build.VERSION.SDK_INT < 30) START_STICKY else START_NOT_STICKY
    }

    private fun ensureStarted(): Boolean {
        if (controller != null) return true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AssistantRepository.error("صلاحية الميكروفون غير ممنوحة")
            stopSelf(); return false
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(
                this, AssistantNotifications.ID_FOREGROUND,
                AssistantNotifications.foreground(this, AssistantStatus.LOADING_MODEL, null, wakePhrase), type
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background
            Log.e(TAG, "startForeground refused", e)
            AssistantRepository.error("رفض النظام تشغيل الميكروفون في الخلفية. افتح التطبيق واضغط تشغيل.")
            AssistantNotifications.bootReminder(this)
            stopSelf(); return false
        }
        AssistantRepository.running(true)
        lifecycleScope.launch { settingsRepo.setWasRunning(true) }

        val c = AssistantController(
            context = this,
            settingsRepo = settingsRepo,
            onStopRequested = { userStopped = true; stopEverything() },
            onStatus = { status, detail -> updateStatus(status, detail) }
        )
        controller = c
        lifecycleScope.launch {
            val s = settingsRepo.current()
            wakePhrase = s.wakePhrase
            if (s.keepCpuAwake) acquireWakeLock()
            c.start()
            settingsRepo.settings.drop(1).collect { ns ->
                wakePhrase = ns.wakePhrase
                c.applySettings(ns)
                if (ns.keepCpuAwake) acquireWakeLock() else releaseWakeLock()
            }
        }
        return true
    }

    private fun updateStatus(status: AssistantStatus, detail: String?) {
        AssistantRepository.status(status)
        if (controller == null) return
        AssistantNotifications.notify(this, AssistantNotifications.ID_FOREGROUND,
            AssistantNotifications.foreground(this, status, detail, wakePhrase))
    }

    /**
     * Keeps the CPU running with the screen off so audio frames keep being
     * processed (the microphone itself is kept alive by the foreground service).
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HaithamVoice:listening").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopEverything() {
        controller?.release(); controller = null
        releaseWakeLock()
        AssistantRepository.running(false)
        if (userStopped) lifecycleScope.launch { settingsRepo.setWasRunning(false) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        controller?.release(); controller = null
        releaseWakeLock()
        AssistantRepository.running(false)
        super.onDestroy()
    }
}
