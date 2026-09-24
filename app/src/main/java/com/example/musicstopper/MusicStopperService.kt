package com.example.musicstopper

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import kotlin.math.max
import kotlin.math.sqrt

class MusicStopperService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "music_stopper_channel"
        var isRunning = false
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var shouldStop = false
    private var prefs: android.content.SharedPreferences? = null
    private var musicSeconds = 0f
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (data != null) startCapture(code, data)
            }
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        createChannel()
        startForeground(NOTIF_ID, buildNotif("جاري مراقبة الصوت"))

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, resultData)

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val sampleRate = 44100
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        shouldStop = false
        captureThread = Thread {
            audioRecord?.startRecording()
            val buffer = FloatArray(sampleRate)
            while (!shouldStop) {
                val read = audioRecord?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: 0
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    if (detectMusic(chunk, sampleRate)) {
                        musicSeconds += 1f
                    } else {
                        musicSeconds = max(0f, musicSeconds - 0.5f)
                    }
                    val now = System.currentTimeMillis()
                    if (musicSeconds >= 1.5f && (now - lastTrigger) > 5000) {
                        triggerActions()
                        musicSeconds = 0f
                        lastTrigger = now
                    }
                }
            }
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }.also { it.start() }

        isRunning = true
    }

    private fun detectMusic(samples: FloatArray, sampleRate: Int): Boolean {
        var sum = 0.0
        for (s in samples) sum += s * s
        val rms = sqrt(sum / samples.size).toFloat()
        if (rms < 0.02f) return false

        var bassEnergy = 0.0
        for (freq in intArrayOf(60, 100, 150, 200)) {
            bassEnergy += goertzel(samples, sampleRate, freq)
        }
        val totalEnergy = sum + 1e-10
        val bassRatio = (bassEnergy / totalEnergy).toFloat()
        return bassRatio > 0.20f && rms > 0.03f
    }

    private fun goertzel(samples: FloatArray, sr: Int, freq: Int): Double {
        val k = (samples.size * freq.toDouble() / sr).toInt()
        val w = 2.0 * Math.PI * k / samples.size
        val coeff = 2.0 * Math.cos(w)
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        for (x in samples) {
            s0 = x + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private fun triggerActions() {
        val p = prefs ?: return
        if (p.getBoolean("opt_dnd", true)) enableDnd()
        if (p.getBoolean("opt_mute", true)) muteMedia()
        if (p.getBoolean("opt_media_key", true)) sendPauseKey()
        if (p.getBoolean("opt_noise", false)) playNoise()
    }

    private fun enableDnd() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                Handler(Looper.getMainLooper()).postDelayed({
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }, 10000)
            }
        } catch (_: Exception) {}
    }

    private fun muteMedia() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            Handler(Looper.getMainLooper()).postDelayed({
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }, 5000)
        } catch (_: Exception) {}
    }

    private fun sendPauseKey() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (_: Exception) {}
    }

    private fun playNoise() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2000)
            Handler(Looper.getMainLooper()).postDelayed({ toneGen.release() }, 2500)
        } catch (_: Exception) {}
    }

    private fun stopCapture() {
        shouldStop = true
        captureThread?.join(2000)
        mediaProjection?.stop()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Music Stopper",
                NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Music Stopper")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
