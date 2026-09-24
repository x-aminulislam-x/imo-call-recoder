package com.example.imorec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.imorec.deleteSilent
import com.example.imorec.forceSpeaker
import com.example.imorec.imoOnly
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max

/**
 * Long-lived foreground service: watches for calls, records them, and shouts if
 * the recording turns out to be silent.
 *
 * It must be started from the UI while the app is in the foreground. Android
 * grants a microphone-type foreground service while-in-use mic access at the
 * moment it starts; keeping the service alive keeps that grant. A service that
 * first starts in the background gets a live mic handle that only ever returns
 * zeroes, which is exactly the failure this app is built to surface.
 */
class RecorderService : Service() {

    companion object {
        private const val TAG = "RecorderService"

        const val ACTION_START = "com.example.imorec.START"
        const val ACTION_STOP = "com.example.imorec.STOP"
        const val ACTION_MANUAL = "com.example.imorec.MANUAL_TOGGLE"

        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALERT = "alert"
        private const val NOTE_STATUS = 1
        private const val NOTE_ALERT = 2

        /**
         * Below this RMS the capture is silence, not a quiet room. -60 dBFS is
         * well under the noise floor of any real microphone, so hitting it means
         * the buffers are literally zero-filled.
         */
        private const val SILENCE_RMS = 0.001

        /** How long to listen before deciding the capture is dead. */
        private const val SILENCE_VERDICT_MS = 12_000L

        @Volatile
        var isMonitoring = false
            private set

        @Volatile
        var isRecording = false
            private set

        @Volatile
        var currentLevelDb = -120.0
            private set

        @Volatile
        var lastStatus = "Idle"
            private set

        /**
         * Manual recording ignores call state entirely. Its purpose is the
         * second-phone workaround: on a device that is not itself in a call,
         * nothing competes for the mic, so capture actually works.
         */
        @Volatile
        var isManualRecording = false
            private set

        fun start(c: Context) {
            val i = Intent(c, RecorderService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i)
            } else {
                c.startService(i)
            }
        }

        fun stop(c: Context) {
            c.startService(Intent(c, RecorderService::class.java).setAction(ACTION_STOP))
        }

        fun toggleManual(c: Context) {
            val i = Intent(c, RecorderService::class.java).setAction(ACTION_MANUAL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i)
            } else {
                c.startService(i)
            }
        }
    }

    private lateinit var detector: CallDetector
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var recorder: AacRecorder? = null
    private var currentFile: File? = null
    private var recordingStartedAt = 0L
    private var peakRms = 0.0
    private var silenceReported = false
    private var savedSpeakerState: Boolean? = null
    private var foregroundStarted = false

    /**
     * Manual recording can be the first thing the service ever does, so the
     * foreground notification has to be up before any mic access, independently
     * of whether the call watcher is running.
     */
    private fun ensureForeground() {
        if (foregroundStarted) return
        startForeground(NOTE_STATUS, statusNotification("Ready"))
        foregroundStarted = true
        handler.post(levelTick)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannels()
        detector = CallDetector(this) { inCall ->
            if (inCall) onCallStarted() else onCallEnded()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_MANUAL -> {
                ensureForeground()
                if (isRecording) {
                    val wasManual = isManualRecording
                    stopRecording()
                    isManualRecording = false
                    updateStatus(if (wasManual) "Manual recording stopped" else "Stopped")
                } else {
                    isManualRecording = true
                    startRecording(isImo = false, manual = true)
                }
            }
            else -> {
                ensureForeground()
                if (!isMonitoring) {
                    detector.start()
                    isMonitoring = true
                    updateStatus("Waiting for a call")
                    Log.i(TAG, "monitoring started")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun stopEverything() {
        if (isRecording) stopRecording()
        if (isMonitoring) detector.stop()
        handler.removeCallbacks(levelTick)
        isMonitoring = false
        isManualRecording = false
        foregroundStarted = false
        lastStatus = "Stopped"
        stopForeground(true)
        stopSelf()
    }

    // ---- call lifecycle -----------------------------------------------------

    private fun onCallStarted() {
        // Let audio routing settle before grabbing the mic. Starting capture in
        // the same instant the call app switches the device into communication
        // mode tends to hand us a stream that gets torn down a moment later.
        handler.postDelayed({
            if (!detector.isInCall() || isRecording) return@postDelayed

            val isImo = CallNotificationListener.imoCallOngoing
            if (imoOnly && !isImo) {
                lastStatus = "Call ignored (not IMO)"
                updateStatus(lastStatus)
                return@postDelayed
            }
            startRecording(isImo)
        }, 1200L)
    }

    private fun onCallEnded() {
        // A manual recording is not owned by the call, so a call ending must not
        // cut it short.
        if (isManualRecording) return
        if (isRecording) stopRecording()
        updateStatus("Waiting for a call")
    }

    private fun startRecording(isImo: Boolean, manual: Boolean = false) {
        if (!manual) applySpeakerRouting()

        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val prefix = if (manual) "manual" else if (isImo) "imo" else "call"
        val file = File(Prefs.recordingsDir(this), prefix + "_" + stamp + ".m4a")

        peakRms = 0.0
        silenceReported = false
        recordingStartedAt = SystemClock.elapsedRealtime()

        val rec = AacRecorder(file) { rms ->
            peakRms = max(peakRms, rms)
            currentLevelDb = if (rms <= 0.0) -120.0 else 20.0 * log10(rms)
        }

        if (!rec.start()) {
            val why = rec.failure ?: "unknown error"
            Log.e(TAG, "failed to start recording: " + why)
            alert("Recording failed to start", why)
            restoreSpeakerRouting()
            return
        }

        recorder = rec
        currentFile = file
        isRecording = true
        lastStatus = "Recording " + file.name
        updateStatus(lastStatus)
        handler.postDelayed(silenceVerdict, SILENCE_VERDICT_MS)
        Log.i(TAG, "recording to " + file.name + " via " + AacRecorder.sourceName(rec.sourceUsed))
    }

    private fun stopRecording() {
        handler.removeCallbacks(silenceVerdict)
        val rec = recorder ?: return
        val file = currentFile

        rec.stop()
        recorder = null
        currentFile = null
        isRecording = false
        currentLevelDb = -120.0
        restoreSpeakerRouting()

        val seconds = rec.samplesWritten / AacRecorder.SAMPLE_RATE
        if (file != null && (!file.exists() || file.length() < 1024L)) {
            file.delete()
            Log.w(TAG, "discarded empty recording")
            return
        }
        // A whole call below -60 dBFS is a muted capture, not a quiet room, so
        // the file has nothing in it. Discard it rather than letting dead
        // recordings pile up and get mistaken for real ones later.
        if (peakRms < SILENCE_RMS && seconds > 3) {
            val deleted = deleteSilent && file != null && file.delete()
            alert(
                if (deleted) "Silent recording discarded" else "Recording was silent",
                if (deleted) {
                    "No audio reached the microphone, so the empty file was deleted. " +
                        "This phone silences background capture during calls."
                } else {
                    "The file was saved but contains no audio. This phone " +
                        "silences background capture during calls."
                }
            )
            Log.w(TAG, "silent recording, deleted=" + deleted)
            return
        }
        Log.i(TAG, "saved " + file?.name + " (" + seconds + "s, peak RMS " + peakRms + ")")
    }

    // ---- the silence alarm --------------------------------------------------

    /**
     * The whole point of the level metering. Android does not report that it has
     * muted a background capture, so after a few seconds of a live call we check
     * whether any signal at all arrived, and tell the user immediately rather
     * than letting them discover an hour of silence afterwards.
     */
    private val silenceVerdict = Runnable {
        if (!isRecording) return@Runnable
        if (peakRms < SILENCE_RMS) {
            silenceReported = true
            alert(
                "No audio is reaching the recorder",
                "Android is muting this app while the call app holds the mic. " +
                    "Recording on this phone will not work without root."
            )
            lastStatus = "Recording (SILENT - no signal)"
        } else {
            lastStatus = "Recording (signal OK)"
        }
        updateStatus(lastStatus)
    }

    private val levelTick = object : Runnable {
        override fun run() {
            if (isRecording) {
                val secs = (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000
                val db = currentLevelDb.toInt()
                val tag = if (silenceReported) " SILENT" else ""
                updateStatus("Recording " + secs + "s - level " + db + " dBFS" + tag)
            }
            handler.postDelayed(this, 2000L)
        }
    }

    // ---- audio routing ------------------------------------------------------

    /**
     * Acoustic capture only works if the far end is coming out of the
     * loudspeaker. We cannot make IMO choose speakerphone, but while the device
     * is in communication mode we can steer the communication route ourselves.
     * Best effort: some OEM builds ignore this, and the call app may override it.
     */
    private fun applySpeakerRouting() {
        if (!forceSpeaker) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audio.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) audio.setCommunicationDevice(speaker)
            } else {
                @Suppress("DEPRECATION")
                savedSpeakerState = audio.isSpeakerphoneOn
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not force speakerphone", t)
        }
    }

    private fun restoreSpeakerRouting() {
        if (!forceSpeaker) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.clearCommunicationDevice()
            } else {
                val prev = savedSpeakerState
                if (prev != null) {
                    @Suppress("DEPRECATION")
                    audio.isSpeakerphoneOn = prev
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not restore audio route", t)
        }
        savedSpeakerState = null
    }

    // ---- notifications ------------------------------------------------------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS, "Recorder status", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT, "Recording problems", NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun statusNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_STATUS)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .build()

    private fun updateStatus(text: String) {
        lastStatus = text
        getSystemService(NotificationManager::class.java)
            .notify(NOTE_STATUS, statusNotification(text))
    }

    private fun alert(title: String, body: String) {
        val n = Notification.Builder(this, CHANNEL_ALERT)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTE_ALERT, n)
    }
}
