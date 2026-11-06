package com.nat.autoanswer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Long-running foreground service. Watches call state; when a call has rung long enough it
 * answers, speaks the greeting, plays a beep, records the caller and hangs up.
 */
class AnswerService : Service() {

    companion object {
        const val ACTION_STOP = "com.nat.autoanswer.STOP"
        private const val CH_SERVICE = "service"
        private const val CH_MESSAGES = "messages"
        private const val NOTIF_SERVICE = 1
        private const val NOTIF_MESSAGE = 2

        /** Bumped whenever the message list changes so the UI can refresh. */
        val messagesVersion = MutableStateFlow(0)
        val running = MutableStateFlow(false)

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AnswerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AnswerService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tm: TelephonyManager
    private lateinit var telecom: TelecomManager
    private lateinit var audio: AudioManager
    private lateinit var prefs: Prefs
    private lateinit var store: MessageStore

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var stateCallback: Any? = null
    private var legacyListener: Any? = null

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var answeredByUs = false
    private var callStartedAt = 0L
    private var pendingAnswer: Runnable? = null

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordStartedAt = 0L
    private var heardVoice = false
    private var lastVoiceAt = 0L
    private var savedMessageId: String? = null

    // ---- lifecycle -------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = MessageStore(this)
        tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        createChannels()

        try {
            val notif = serviceNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_SERVICE, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_SERVICE, notif)
            }
        } catch (e: Exception) {
            stopSelf()
            return
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }

        if (!registerCallStateListener()) {
            stopSelf()
            return
        }
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.value = false
        handler.removeCallbacksAndMessages(null)
        unregisterCallStateListener()
        releaseRecorder()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- call state ------------------------------------------------------------------

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun registerCallStateListener(): Boolean {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) return false
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = StateCallback()
                stateCallback = cb
                tm.registerTelephonyCallback(mainExecutor, cb)
            } else {
                val l = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        onCallState(state)
                    }
                }
                legacyListener = l
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            }
            true
        } catch (e: SecurityException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterCallStateListener() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                (stateCallback as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
            } else {
                (legacyListener as? PhoneStateListener)?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) {
        }
    }

    @RequiresApi(31)
    private inner class StateCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = onCallState(state)
    }

    private fun onCallState(state: Int) {
        val previous = lastState
        lastState = state
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (prefs.enabled && previous == TelephonyManager.CALL_STATE_IDLE) {
                    callStartedAt = System.currentTimeMillis()
                    scheduleAnswer()
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                cancelPendingAnswer()
                if (answeredByUs) handler.postDelayed({ beginGreeting() }, 900)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                cancelPendingAnswer()
                finishSession(hangUp = false)
                if (answeredByUs) {
                    answeredByUs = false
                    handler.postDelayed({ resolveCallerAndNotify() }, 2000)
                }
            }
        }
    }

    // ---- answering -------------------------------------------------------------------

    private fun scheduleAnswer() {
        cancelPendingAnswer()
        val r = Runnable { answerNow() }
        pendingAnswer = r
        handler.postDelayed(r, prefs.ringDelaySec * 1000L)
    }

    private fun cancelPendingAnswer() {
        pendingAnswer?.let { handler.removeCallbacks(it) }
        pendingAnswer = null
    }

    @Suppress("MissingPermission")
    private fun answerNow() {
        pendingAnswer = null
        if (lastState != TelephonyManager.CALL_STATE_RINGING) return
        try {
            answeredByUs = true
            savedMessageId = null
            telecom.acceptRingingCall()
        } catch (e: SecurityException) {
            answeredByUs = false
        }
    }

    // ---- greeting + recording --------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun beginGreeting() {
        if (!callActive()) return
        // The call audio of other apps is not accessible on Android; speakerphone lets the
        // phone's mic and speaker bridge the audio acoustically.
        audio.isSpeakerphoneOn = true

        val engine = tts
        if (engine == null || !ttsReady) {
            beepThenRecord()
            return
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                handler.post { beepThenRecord() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { beepThenRecord() }
            }
        })
        engine.speak(prefs.greeting, TextToSpeech.QUEUE_FLUSH, null, "greeting")
    }

    private fun callActive() =
        answeredByUs && lastState == TelephonyManager.CALL_STATE_OFFHOOK

    private fun beepThenRecord() {
        if (!callActive() || recorder != null) return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 350)
            handler.postDelayed({ tone.release() }, 700)
        } catch (_: Exception) {
        }
        handler.postDelayed({ startRecording() }, 600)
    }

    private fun startRecording() {
        if (!callActive() || recorder != null) return
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            hangUp()
            return
        }
        val file = store.newAudioFile()
        try {
            val r = createRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(48000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recordingFile = file
            recordStartedAt = System.currentTimeMillis()
            heardVoice = false
            lastVoiceAt = recordStartedAt
            handler.postDelayed(monitor, 400)
        } catch (e: Exception) {
            releaseRecorder()
            file.delete()
            hangUp()
        }
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
    }

    /** Polls the input level to enforce the length cap and hang up after the caller falls silent. */
    private val monitor = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val now = System.currentTimeMillis()
            val elapsed = now - recordStartedAt
            val amp = try { r.maxAmplitude } catch (_: Exception) { 0 }
            if (amp > 1800) {
                heardVoice = true
                lastVoiceAt = now
            }
            val tooLong = elapsed >= prefs.maxMessageSec * 1000L
            val silent = prefs.hangUpOnSilence && (
                (!heardVoice && elapsed > 10_000) || (heardVoice && now - lastVoiceAt > 5_000)
                )
            if (tooLong || silent) {
                finishSession(hangUp = true)
            } else {
                handler.postDelayed(this, 400)
            }
        }
    }

    private fun releaseRecorder() {
        recorder?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        recorder = null
    }

    /** Stops speech/recording, stores the message if it is worth keeping, optionally hangs up. */
    @Suppress("DEPRECATION")
    private fun finishSession(hangUp: Boolean) {
        handler.removeCallbacks(monitor)
        tts?.stop()

        val file = recordingFile
        val duration = if (recorder != null) System.currentTimeMillis() - recordStartedAt else 0L
        val hadRecorder = recorder != null
        releaseRecorder()
        recordingFile = null

        if (hadRecorder && file != null) {
            if (duration >= 1500 && file.exists() && file.length() > 0) {
                val id = UUID.randomUUID().toString()
                savedMessageId = id
                store.add(
                    VoiceMessage(
                        id = id,
                        number = "Unknown",
                        timestamp = System.currentTimeMillis(),
                        durationMs = duration,
                        fileName = file.name,
                        listened = false,
                    )
                )
                messagesVersion.value++
            } else {
                file.delete()
            }
        }

        if (hangUp) hangUp()
        if (lastState == TelephonyManager.CALL_STATE_IDLE || hangUp) {
            try { audio.isSpeakerphoneOn = false } catch (_: Exception) {}
        }
    }

    @Suppress("MissingPermission")
    private fun hangUp() {
        try {
            if (Build.VERSION.SDK_INT >= 28 && hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
                telecom.endCall()
            }
        } catch (_: Exception) {
        }
    }

    // ---- caller id + notifications ---------------------------------------------------

    private fun resolveCallerAndNotify() {
        val id = savedMessageId ?: return
        savedMessageId = null
        var number = "Unknown"
        if (hasPermission(Manifest.permission.READ_CALL_LOG)) {
            try {
                contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                    null, null, "${CallLog.Calls.DATE} DESC"
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val date = c.getLong(1)
                        val n = c.getString(0)
                        if (date >= callStartedAt - 10_000 && !n.isNullOrBlank()) number = n
                    }
                }
            } catch (_: Exception) {
            }
        }
        store.setNumber(id, number)
        messagesVersion.value++

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CH_MESSAGES)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("New voice message")
            .setContentText("From $number")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_MESSAGE, n)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Answering machine status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "New messages", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun serviceNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AnswerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Answering machine is on")
            .setContentText("Unanswered calls will be picked up after ${prefs.ringDelaySec}s")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .build()
    }
}
