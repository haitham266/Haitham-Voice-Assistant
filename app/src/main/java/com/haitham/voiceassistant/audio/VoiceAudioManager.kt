package com.haitham.voiceassistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.sqrt

/**
 * Microphone access (16 kHz mono PCM16) plus helpers:
 *  - [EnergyVad]: cheap voice-activity gate so the recogniser sleeps during silence.
 *  - in-call detection so the microphone is released during phone / VoIP calls.
 *  - "silenced" detection (Android 10+) when another app takes the microphone.
 * Audio stays in memory only; nothing is written to disk or sent anywhere.
 */
class VoiceAudioManager(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = 1600 // 100 ms
        private const val TAG = "VoiceAudio"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var record: AudioRecord? = null
    private var recordingCallback: Any? = null

    @Volatile var silencedByOtherApp = false
        private set

    val isOpen: Boolean get() = record?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    /** True while a phone call or VoIP call is active / ringing. */
    fun isInCall(): Boolean = when (audioManager.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE -> true
        else -> if (Build.VERSION.SDK_INT >= 30) audioManager.mode == AudioManager.MODE_CALL_SCREENING else false
    }

    @SuppressLint("MissingPermission") // checked by the service before starting
    fun open(): Boolean {
        if (isOpen) return true
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(min, FRAME_SAMPLES * 2 * 4)
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord failed", e); return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        try { r.startRecording() } catch (e: Exception) { r.release(); return false }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return false }
        record = r
        registerSilenceCallback(r)
        return true
    }

    private fun registerSilenceCallback(r: AudioRecord) {
        if (Build.VERSION.SDK_INT < 29) return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == r.audioSessionId }
                silencedByOtherApp = mine?.isClientSilenced ?: false
            }
        }
        audioManager.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
        recordingCallback = cb
    }

    /** Blocking read of one 100 ms frame. Returns number of samples or <= 0 on error. */
    fun read(buf: ShortArray): Int {
        val r = record ?: return -1
        var off = 0
        while (off < buf.size) {
            val n = r.read(buf, off, buf.size - off)
            if (n <= 0) return n
            off += n
        }
        return off
    }

    fun close() {
        if (Build.VERSION.SDK_INT >= 29) {
            (recordingCallback as? AudioManager.AudioRecordingCallback)?.let { audioManager.unregisterAudioRecordingCallback(it) }
        }
        recordingCallback = null
        silencedByOtherApp = false
        record?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        record = null
    }
}

/** RMS of a PCM16 frame, 0..32768. */
fun rms(buf: ShortArray, n: Int = buf.size): Double {
    if (n <= 0) return 0.0
    var sum = 0.0
    for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
    return sqrt(sum / n)
}

/**
 * Adaptive energy voice-activity detector working on 100 ms frames.
 * Pure Kotlin so it is unit-tested on the JVM.
 */
class EnergyVad(sensitivity: Int) {
    private val factor = when (sensitivity) { 0 -> 3.2; 2 -> 1.8; else -> 2.4 }
    private val absoluteMin = when (sensitivity) { 0 -> 260.0; 2 -> 90.0; else -> 160.0 }
    private var noise = 200.0
    private var hangover = 0
    private val hangoverFrames = 8 // 800 ms trailing audio so the recogniser can end the utterance

    var isSpeech = false
        private set

    /** Loudness of the last frame without the hangover (used to end commands quickly). */
    var lastFrameLoud = false
        private set

    /** Feeds a frame energy; returns true if this frame should be passed to the recogniser. */
    fun update(energy: Double): Boolean {
        val loud = energy > absoluteMin && energy > noise * factor
        lastFrameLoud = loud
        if (loud) {
            isSpeech = true
            hangover = hangoverFrames
        } else {
            // adapt the noise floor only on non-speech frames (slowly up, faster down)
            noise = if (energy > noise) noise * 0.97 + energy * 0.03 else noise * 0.85 + energy * 0.15
            noise = noise.coerceIn(30.0, 6000.0)
            if (hangover > 0) hangover-- else isSpeech = false
        }
        return isSpeech
    }

    val noiseFloor: Double get() = noise
}
