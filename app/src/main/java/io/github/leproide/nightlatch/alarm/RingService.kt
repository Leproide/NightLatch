/*
 * NightLatch - NFC alarm clock
 * Copyright (C) 2026 Leproide
 * Author: https://github.com/Leproide
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.leproide.nightlatch.alarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.leproide.nightlatch.NightLatchApp
import io.github.leproide.nightlatch.R
import io.github.leproide.nightlatch.data.Store
import io.github.leproide.nightlatch.ui.AlarmActivity

/**
 * Foreground service that owns the ringing: sound (alarm stream, looping),
 * vibration and the full-screen notification that opens [AlarmActivity].
 * There is no auto-silence: it stops only through [dismiss].
 *
 * Extras:
 *  - phone call in progress → the sound pauses (vibration keeps going) and resumes after;
 *  - optional volume ramp (settings.rampVolume): from −30 dB to full in settings.rampSeconds,
 *    linear in dB so it sounds like a steady rise (amplitude-linear ramps sound front-loaded);
 *  - optional wake check (settings.wakeCheck): a later "are you awake?" prompt that
 *    rings again if not confirmed within [CHECK_TIMEOUT_MS] (see [startCheck]).
 */
class RingService : Service() {

    companion object {
        private const val TAG = "NightLatch/Ring"
        private const val ACTION_RING = "io.github.leproide.nightlatch.action.RING"
        private const val ACTION_DISMISS = "io.github.leproide.nightlatch.action.DISMISS"
        private const val ACTION_CHECK = "io.github.leproide.nightlatch.action.CHECK"
        private const val ACTION_CHECK_OK = "io.github.leproide.nightlatch.action.CHECK_OK"
        private const val NOTIFICATION_ID = 1001

        /** Wake lock is taken with a timeout and refreshed by the tick, never held unbounded. */
        private const val WAKE_LOCK_MS = 10L * 60L * 1000L
        private const val TICK_MS = 1_000L
        /** Ramp starts at −30 dB (≈3 % amplitude) and rises linearly in dB to 0 dB. */
        private const val RAMP_FLOOR_DB = -30f
        private val RAMP_START = dbToGain(RAMP_FLOOR_DB)

        private fun dbToGain(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()
        const val CHECK_TIMEOUT_MS = 60_000L

        /** Id of the alarm currently ringing, 0 when silent. Observed by the UI. */
        val ringingAlarmId = mutableIntStateOf(0)

        /** Alarm whose wake check is waiting for confirmation (0 = none) and its deadline (wall clock). */
        val checkAlarmId = mutableIntStateOf(0)
        val checkDeadline = mutableLongStateOf(0L)

        fun start(ctx: Context, alarmId: Int) {
            val i = Intent(ctx, RingService::class.java)
                .setAction(ACTION_RING)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Background start refused (should not happen with exact alarms):
                // fall back to a plain full-screen notification; AlarmActivity restarts us.
                Log.e(TAG, "Foreground service start refused, using notification fallback", e)
                Store.setRingingAlarm(ctx, alarmId)
                AlarmScheduler.armBackup(ctx)
                ctx.getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(ctx, alarmId, check = false))
            }
        }

        /** Wake check fired: show the "are you awake?" prompt. */
        fun startCheck(ctx: Context, alarmId: Int) {
            val i = Intent(ctx, RingService::class.java)
                .setAction(ACTION_CHECK)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Can't prompt: ring for real instead, it's the safe side for an alarm clock.
                Log.e(TAG, "Wake check start refused, ringing instead", e)
                start(ctx, alarmId)
            }
        }

        fun dismiss(ctx: Context) {
            Store.setRingingAlarm(ctx, 0)
            AlarmScheduler.cancelBackup(ctx)
            send(ctx, ACTION_DISMISS)
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            ringingAlarmId.intValue = 0
        }

        /** User confirmed the wake check. */
        fun confirmAwake(ctx: Context) {
            checkAlarmId.intValue = 0
            send(ctx, ACTION_CHECK_OK)
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }

        private fun send(ctx: Context, action: String) {
            try {
                ctx.startService(Intent(ctx, RingService::class.java).setAction(action))
            } catch (e: Exception) {
                Log.w(TAG, "$action: service not reachable", e)
            }
        }

        fun buildNotification(ctx: Context, alarmId: Int, check: Boolean): Notification {
            val open = Intent(ctx, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            val pi = PendingIntent.getActivity(
                ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val label = Store.alarm(ctx, alarmId)?.label?.takeIf { it.isNotEmpty() }
            val text = when {
                check -> ctx.getString(R.string.notif_check_text)
                else -> label ?: ctx.getString(R.string.notif_ringing_text)
            }
            return NotificationCompat.Builder(ctx, NightLatchApp.CHANNEL_RINGING)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(ctx.getString(R.string.notif_ringing_title))
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tickCount = 0
    private var pausedForCall = false
    private var rampStartedAt = 0L
    private var rampEnabled = false
    private var rampMs = 30_000L

    /**
     * Every second: pause/resume around phone calls, volume ramp, re-assert max alarm
     * volume. Every 30 s: push the backup alarm forward, refresh the wake lock and write
     * a heartbeat (used by Guardian to detect a dead ring).
     */
    private val tick = object : Runnable {
        override fun run() {
            if (ringingAlarmId.intValue == 0) return
            val inCall = isInCall()
            handleCall(inCall)
            if (!inCall) {
                applyRamp()
                if (Store.settings(this@RingService).lockVolume) forceMaxVolume()
            }
            if (tickCount % 30 == 0) {
                AlarmScheduler.armBackup(this@RingService)
                acquireWakeLock()
                Store.ringHeartbeat(this@RingService)
            }
            tickCount++
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Wake check not confirmed in time: ring for real. */
    private val checkTimeout = Runnable {
        val id = checkAlarmId.intValue
        checkAlarmId.intValue = 0
        stopCheckVibration()
        if (id != 0) ring(id)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> {
                val id = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, 0)
                if (id > 0) ring(id) else if (ringingAlarmId.intValue == 0) stopRinging()
            }
            ACTION_DISMISS -> stopRinging()
            ACTION_CHECK -> {
                val id = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, 0)
                if (id > 0) check(id) else stopIfIdle()
            }
            ACTION_CHECK_OK -> {
                handler.removeCallbacks(checkTimeout)
                checkAlarmId.intValue = 0
                stopCheckVibration()
                stopIfIdle()
            }
            // Null intent = restarted by the system after being killed: resume if still pending.
            else -> {
                val pending = Store.ringingAlarm(this)
                if (pending != 0) ring(pending) else stopRinging()
            }
        }
        // Sticky: if the system kills us mid-ring it restarts the service (null intent above).
        return START_STICKY
    }

    private fun goForeground(alarmId: Int, check: Boolean): Boolean {
        val notification = buildNotification(this, alarmId, check)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground refused", e)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            false
        }
    }

    private fun showAlarmScreen(alarmId: Int) {
        // Launch the screen directly as well (works when the screen is on/unlocked,
        // where Android shows only a heads-up instead of the full-screen intent).
        try {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                    .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            )
        } catch (e: Exception) {
            Log.i(TAG, "Direct activity start blocked, full-screen notification will handle it")
        }
    }

    private fun ring(alarmId: Int) {
        // A real ring supersedes a pending wake check.
        handler.removeCallbacks(checkTimeout)
        checkAlarmId.intValue = 0
        stopCheckVibration()

        if (!goForeground(alarmId, check = false)) {
            // e.g. sticky restart from the background on Android 12+: the full-screen
            // notification is up; the backup exact alarm restarts us legitimately within 60 s.
            Store.setRingingAlarm(this, alarmId)
            AlarmScheduler.armBackup(this)
            stopSelf()
            return
        }

        // A second alarm firing while one rings just takes over the screen; sound keeps going.
        val alreadyRinging = ringingAlarmId.intValue != 0
        ringingAlarmId.intValue = alarmId
        Store.setRingingAlarm(this, alarmId)
        AlarmScheduler.armBackup(this)
        if (alreadyRinging && player != null) return

        val settings = Store.settings(this)
        rampEnabled = settings.rampVolume
        rampMs = settings.rampSeconds * 1_000L
        rampStartedAt = SystemClock.elapsedRealtime()
        acquireWakeLock()
        startSound()
        if (settings.vibrate) startVibration()
        handler.removeCallbacks(tick)
        tickCount = 0
        handler.post(tick)
        showAlarmScreen(alarmId)
    }

    /** "Are you awake?": soft pulses, full-screen prompt, rings if not confirmed in time. */
    private fun check(alarmId: Int) {
        if (ringingAlarmId.intValue != 0) return // already ringing: nothing to check
        if (!goForeground(alarmId, check = true)) {
            // Can't hold the prompt: ring for real (backup alarm will bring it back).
            Store.setRingingAlarm(this, alarmId)
            AlarmScheduler.armBackup(this)
            stopSelf()
            return
        }
        checkAlarmId.intValue = alarmId
        checkDeadline.longValue = System.currentTimeMillis() + CHECK_TIMEOUT_MS
        acquireWakeLock()
        startCheckVibration()
        handler.removeCallbacks(checkTimeout)
        handler.postDelayed(checkTimeout, CHECK_TIMEOUT_MS)
        showAlarmScreen(alarmId)
    }

    private fun startSound() {
        val settings = Store.settings(this)
        val candidates = listOfNotNull(
            settings.ringtoneUri?.let(Uri::parse),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        )
        for (uri in candidates) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@RingService, uri)
                    isLooping = true
                    setWakeMode(this@RingService, PowerManager.PARTIAL_WAKE_LOCK)
                    prepare()
                    if (rampEnabled) setVolume(RAMP_START, RAMP_START)
                    if (isInCall()) pausedForCall = true else start()
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "Cannot play $uri, trying next", e)
                player?.release()
                player = null
            }
        }
        Log.e(TAG, "No playable alarm sound; relying on vibration")
        startVibration()
    }

    /** Phone call ringing or in progress (no permission needed: audio mode only). */
    private fun isInCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    /** Sound off during a call (vibration keeps going), back on afterwards. */
    private fun handleCall(inCall: Boolean) {
        val p = player ?: return
        try {
            if (inCall && p.isPlaying) {
                p.pause()
                pausedForCall = true
            } else if (!inCall && pausedForCall) {
                pausedForCall = false
                p.start()
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Player state error around call", e)
        }
    }

    private fun applyRamp() {
        if (!rampEnabled) return
        val p = player ?: return
        val f = ((SystemClock.elapsedRealtime() - rampStartedAt).toFloat() / rampMs).coerceIn(0f, 1f)
        val v = if (f >= 1f) 1f else dbToGain(RAMP_FLOOR_DB * (1f - f))
        try {
            p.setVolume(v, v)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Ramp volume error", e)
        }
        if (v >= 1f) rampEnabled = false
    }

    private fun defaultVibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

    private fun startVibration() {
        if (vibrator != null) return
        val v = defaultVibrator()
        if (!v.hasVibrator()) return
        val pattern = longArrayOf(0, 800, 600)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        @Suppress("DEPRECATION")
        v.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
        vibrator = v
    }

    private fun startCheckVibration() {
        val v = defaultVibrator()
        if (!v.hasVibrator()) return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        // Short double pulse every 3 s: noticeable, but not an alarm yet.
        @Suppress("DEPRECATION")
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 150, 120, 2_600), 0), attrs)
        vibrator = v
    }

    private fun stopCheckVibration() {
        if (ringingAlarmId.intValue == 0) {
            vibrator?.cancel()
            vibrator = null
        }
    }

    private fun acquireWakeLock() {
        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NightLatch:ring")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        wl.acquire(WAKE_LOCK_MS) // re-acquiring a non-counted lock just extends the timeout
    }

    private fun stopIfIdle() {
        if (ringingAlarmId.intValue != 0 || checkAlarmId.intValue != 0) return
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopRinging() {
        handler.removeCallbacks(tick)
        Store.setRingingAlarm(this, 0)
        AlarmScheduler.cancelBackup(this)
        player?.runCatching { stop() }
        player?.release()
        player = null
        pausedForCall = false
        vibrator?.cancel()
        vibrator = null
        ringingAlarmId.intValue = 0
        stopIfIdle()
    }

    private fun forceMaxVolume() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        } catch (e: SecurityException) {
            // Do Not Disturb "total silence" can refuse volume changes; keep ringing anyway.
            Log.w(TAG, "Cannot raise alarm volume", e)
        }
    }

    /** Swiped away from recents: keep ringing and bring the screen back. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val id = ringingAlarmId.intValue.takeIf { it != 0 } ?: checkAlarmId.intValue
        if (id != 0) showAlarmScreen(id)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(checkTimeout)
        player?.release()
        player = null
        vibrator?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
