package com.omarlatrach.counter

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
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class WarmUpSessionState(
    val stage: WarmUpStage = WarmUpStage.IDLE,
    val warmUpElapsedSeconds: Int = 0,
    val lockedWarmUpSeconds: Int = 0,
    val countingElapsedSeconds: Int = 0,
    val finishSoundPlayed: Boolean = false,
)

object WarmUpSessionStore {
    private val _state = MutableStateFlow(WarmUpSessionState())
    val state: StateFlow<WarmUpSessionState> = _state

    fun set(newState: WarmUpSessionState) {
        _state.value = newState
    }
}

class WarmUpForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioPlayer: WarmUpServiceAudioPlayer

    private var tickerJob: Job? = null
    private var warmUpStartRealtimeMs = 0L
    private var countingStartRealtimeMs = 0L
    private var lockedWarmUpSeconds = 0
    private var finishSoundPlayed = false
    private var isForegroundStarted = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        audioPlayer = WarmUpServiceAudioPlayer(
            context = applicationContext,
            onMusicCompleted = {
                serviceScope.launch {
                    val stage = WarmUpSessionStore.state.value.stage
                    if (stage == WarmUpStage.WARMING_UP || stage == WarmUpStage.COUNTING) {
                        stopSession(resetToIdle = false)
                    }
                }
            },
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_WARM_UP -> startWarmUpSession()
            ACTION_START_COUNTER -> startCountingPhase()
            ACTION_STOP_SESSION -> stopSession(resetToIdle = false)
            ACTION_RESET_SESSION -> stopSession(resetToIdle = true)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        audioPlayer.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWarmUpSession() {
        tickerJob?.cancel()
        audioPlayer.stopAll()

        warmUpStartRealtimeMs = SystemClock.elapsedRealtime()
        countingStartRealtimeMs = 0L
        lockedWarmUpSeconds = 0
        finishSoundPlayed = false

        val newState = WarmUpSessionState(
            stage = WarmUpStage.WARMING_UP,
            warmUpElapsedSeconds = 0,
            lockedWarmUpSeconds = 0,
            countingElapsedSeconds = 0,
            finishSoundPlayed = false,
        )
        WarmUpSessionStore.set(newState)
        startOrUpdateForeground(newState)
        audioPlayer.startMusic()

        tickerJob = serviceScope.launch {
            while (isActive) {
                val elapsed = elapsedSecondsSince(warmUpStartRealtimeMs)
                val updatedState = WarmUpSessionState(
                    stage = WarmUpStage.WARMING_UP,
                    warmUpElapsedSeconds = elapsed,
                    lockedWarmUpSeconds = 0,
                    countingElapsedSeconds = 0,
                    finishSoundPlayed = false,
                )
                WarmUpSessionStore.set(updatedState)
                updateNotification(updatedState)
                delay(1000)
            }
        }
    }

    private fun startCountingPhase() {
        val currentState = WarmUpSessionStore.state.value
        if (currentState.stage != WarmUpStage.WARMING_UP) {
            return
        }

        tickerJob?.cancel()

        lockedWarmUpSeconds = elapsedSecondsSince(warmUpStartRealtimeMs)
        countingStartRealtimeMs = SystemClock.elapsedRealtime()
        finishSoundPlayed = false

        val newState = WarmUpSessionState(
            stage = WarmUpStage.COUNTING,
            warmUpElapsedSeconds = lockedWarmUpSeconds,
            lockedWarmUpSeconds = lockedWarmUpSeconds,
            countingElapsedSeconds = 0,
            finishSoundPlayed = false,
        )
        WarmUpSessionStore.set(newState)
        startOrUpdateForeground(newState)

        tickerJob = serviceScope.launch {
            while (isActive) {
                val countingElapsed = elapsedSecondsSince(countingStartRealtimeMs)

                if (!finishSoundPlayed && countingElapsed >= WarmUpFinishSoundSeconds) {
                    finishSoundPlayed = true
                    audioPlayer.playFinished()
                }

                val updatedState = WarmUpSessionState(
                    stage = WarmUpStage.COUNTING,
                    warmUpElapsedSeconds = lockedWarmUpSeconds,
                    lockedWarmUpSeconds = lockedWarmUpSeconds,
                    countingElapsedSeconds = countingElapsed,
                    finishSoundPlayed = finishSoundPlayed,
                )
                WarmUpSessionStore.set(updatedState)
                updateNotification(updatedState)

                delay(1000)
            }
        }
    }

    private fun stopSession(resetToIdle: Boolean) {
        tickerJob?.cancel()
        tickerJob = null

        val currentState = WarmUpSessionStore.state.value
        val newState = if (resetToIdle) {
            WarmUpSessionState()
        } else {
            when (currentState.stage) {
                WarmUpStage.WARMING_UP -> WarmUpSessionState(
                    stage = WarmUpStage.STOPPED,
                    warmUpElapsedSeconds = elapsedSecondsSince(warmUpStartRealtimeMs),
                    lockedWarmUpSeconds = 0,
                    countingElapsedSeconds = 0,
                    finishSoundPlayed = false,
                )

                WarmUpStage.COUNTING -> WarmUpSessionState(
                    stage = WarmUpStage.STOPPED,
                    warmUpElapsedSeconds = lockedWarmUpSeconds,
                    lockedWarmUpSeconds = lockedWarmUpSeconds,
                    countingElapsedSeconds = elapsedSecondsSince(countingStartRealtimeMs),
                    finishSoundPlayed = finishSoundPlayed,
                )

                else -> currentState.copy(stage = WarmUpStage.STOPPED)
            }
        }

        WarmUpSessionStore.set(newState)
        audioPlayer.stopAll()
        removeForegroundAndStop()
    }

    private fun elapsedSecondsSince(startRealtimeMs: Long): Int {
        if (startRealtimeMs == 0L) {
            return 0
        }

        return ((SystemClock.elapsedRealtime() - startRealtimeMs) / 1000L).toInt().coerceAtLeast(0)
    }

    private fun startOrUpdateForeground(state: WarmUpSessionState) {
        val notification = buildNotification(state)
        if (!isForegroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundStarted = true
        } else {
            notifyIfAllowed(notification)
        }
    }

    private fun updateNotification(state: WarmUpSessionState) {
        if (!isForegroundStarted) {
            return
        }
        notifyIfAllowed(buildNotification(state))
    }

    private fun notifyIfAllowed(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun removeForegroundAndStop() {
        if (isForegroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForegroundStarted = false
        }
        stopSelf()
    }

    private fun buildNotification(state: WarmUpSessionState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val titleRes = when (state.stage) {
            WarmUpStage.WARMING_UP -> R.string.warm_up_notification_warming_up_title
            WarmUpStage.COUNTING -> R.string.warm_up_notification_counting_title
            WarmUpStage.COMPLETED -> R.string.warm_up_completed_title
            else -> R.string.warm_up_counter_title
        }

        val contentText = when (state.stage) {
            WarmUpStage.WARMING_UP -> getString(
                R.string.warm_up_notification_warming_up_text,
                formatElapsedTime(displayWarmUpSeconds(state)),
            )

            WarmUpStage.COUNTING -> getString(
                R.string.warm_up_notification_counting_text,
                formatElapsedTime(displayWarmUpSeconds(state)),
                formatElapsedTime(state.countingElapsedSeconds),
            )

            WarmUpStage.COMPLETED -> getString(
                R.string.warm_up_notification_counting_text,
                formatElapsedTime(displayWarmUpSeconds(state)),
                formatElapsedTime(state.countingElapsedSeconds),
            )

            else -> getString(R.string.warm_up_counter_title)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(titleRes))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingIntent)
            .setOngoing(state.stage == WarmUpStage.WARMING_UP || state.stage == WarmUpStage.COUNTING)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun displayWarmUpSeconds(state: WarmUpSessionState): Int {
        return if (state.lockedWarmUpSeconds > 0) {
            state.lockedWarmUpSeconds
        } else {
            state.warmUpElapsedSeconds
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.warm_up_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.warm_up_service_channel_description)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "warm_up_counter_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START_WARM_UP = "com.omarlatrach.counter.action.START_WARM_UP"
        private const val ACTION_START_COUNTER = "com.omarlatrach.counter.action.START_COUNTER"
        private const val ACTION_STOP_SESSION = "com.omarlatrach.counter.action.STOP_SESSION"
        private const val ACTION_RESET_SESSION = "com.omarlatrach.counter.action.RESET_SESSION"

        fun startWarmUp(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WarmUpForegroundService::class.java).setAction(ACTION_START_WARM_UP),
            )
        }

        fun startCounter(context: Context) {
            context.startService(
                Intent(context, WarmUpForegroundService::class.java)
                    .setAction(ACTION_START_COUNTER),
            )
        }

        fun stopSession(context: Context) {
            context.startService(
                Intent(context, WarmUpForegroundService::class.java)
                    .setAction(ACTION_STOP_SESSION),
            )
        }

        fun resetSession(context: Context) {
            context.startService(
                Intent(context, WarmUpForegroundService::class.java)
                    .setAction(ACTION_RESET_SESSION),
            )
        }
    }
}

private class WarmUpServiceAudioPlayer(
    private val context: Context,
    private val onMusicCompleted: () -> Unit,
) {
    private var musicPlayer: MediaPlayer? = null
    private var voicePlayer: MediaPlayer? = null

    fun startMusic() {
        if (musicPlayer?.isPlaying == true) {
            return
        }

        stopMusic()
        musicPlayer = MediaPlayer.create(context, R.raw.music)?.apply {
            setVolume(0.32f, 0.32f)
            setOnCompletionListener { completedPlayer ->
                if (musicPlayer === completedPlayer) {
                    musicPlayer = null
                }
                completedPlayer.release()
                onMusicCompleted()
            }
            start()
        }
    }

    fun playFinished() {
        stopVoice()

        voicePlayer = MediaPlayer.create(context, R.raw.finished)?.apply {
            setOnCompletionListener { completedPlayer ->
                if (voicePlayer === completedPlayer) {
                    voicePlayer = null
                }
                completedPlayer.release()
            }
            start()
        }
    }

    fun stopMusic() {
        musicPlayer?.let { player ->
            player.setOnCompletionListener(null)
            runCatching {
                if (player.isPlaying) {
                    player.stop()
                }
            }
            player.release()
        }
        musicPlayer = null
    }

    fun stopVoice() {
        voicePlayer?.let { player ->
            player.setOnCompletionListener(null)
            runCatching {
                if (player.isPlaying) {
                    player.stop()
                }
            }
            player.release()
        }
        voicePlayer = null
    }

    fun stopAll() {
        stopVoice()
        stopMusic()
    }

    fun release() {
        stopAll()
    }
}
