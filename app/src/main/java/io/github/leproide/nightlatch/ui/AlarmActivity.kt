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

package io.github.leproide.nightlatch.ui

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import io.github.leproide.nightlatch.alarm.AlarmScheduler
import io.github.leproide.nightlatch.alarm.RingService
import io.github.leproide.nightlatch.data.Store
import io.github.leproide.nightlatch.nfc.NfcUid
import io.github.leproide.nightlatch.nfc.TagScanner
import io.github.leproide.nightlatch.ui.screens.RingScreen
import io.github.leproide.nightlatch.ui.screens.WakeCheckScreen
import io.github.leproide.nightlatch.ui.theme.NightLatchTheme

/**
 * Ringing screen. Shown over the lock screen; back is disabled and volume keys
 * are swallowed while ringing. Dismiss = a paired NFC tag or the math fallback.
 */
class AlarmActivity : ComponentActivity() {

    private lateinit var scanner: TagScanner

    // Owner override.
    private val chord = KeyChord(
        taps = intArrayOf(
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP,
        ),
        holdKey = KeyEvent.KEYCODE_VOLUME_DOWN,
        holdMs = 5_000L,
        // Emergency stop: no wake check afterwards, and any pending one is dropped too.
        onMatch = {
            AlarmScheduler.cancelWakeCheck(this)
            dismissAlarm(scheduleWakeCheck = false)
            confirmAwake()
        },
        guard = { fingersOnScreen == 2 },
    )

    /** Pointers currently on the screen; a multi-finger gesture never reaches the UI. */
    private var fingersOnScreen = 0
    private var multiTouch = false

    /** Incremented on each unknown tag so the UI can flash "ACCESS DENIED". */
    private val deniedTick = mutableIntStateOf(0)
    private val locked = mutableStateOf(false)

    /**
     * Fingerprint/face unlock over this screen doesn't always change window focus:
     * re-read the keyguard state every second while visible.
     */
    private val lockHandler = Handler(Looper.getMainLooper())
    private val lockPoll = object : Runnable {
        override fun run() {
            locked.value = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            lockHandler.postDelayed(this, 1_000L)
        }
    }
    private val nfcUsable = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit // no escape
        })

        // No scan haptic here: it would interrupt the alarm vibration.
        scanner = TagScanner(this, haptic = false) { uid ->
            when {
                // Wake check pending: only the on-screen button confirms, tags are ignored.
                RingService.ringingAlarmId.intValue == 0 && RingService.checkAlarmId.intValue != 0 -> Unit
                acceptsTag(uid) -> dismissAlarm(scheduleWakeCheck = true)
                else -> deniedTick.intValue++
            }
        }

        ensureRinging()

        setContent {
            NightLatchTheme {
                val ringing = RingService.ringingAlarmId.intValue
                val checking = RingService.checkAlarmId.intValue
                // Close as soon as nothing is ringing and no wake check is waiting.
                LaunchedEffect(ringing, checking) {
                    if (ringing == 0 && checking == 0 && Store.ringingAlarm(this@AlarmActivity) == 0) finish()
                }
                if (ringing == 0 && checking != 0) {
                    WakeCheckScreen(
                        deadline = RingService.checkDeadline.longValue,
                        glitch = Store.settings(this).ringGlitch,
                        onConfirm = ::confirmAwake,
                    )
                } else {
                    val id = ringing.takeIf { it != 0 } ?: Store.ringingAlarm(this)
                    val alarm = Store.alarm(this, id)
                    RingScreen(
                        alarm = alarm,
                        requiredTagName = Store.tagName(this, alarm?.tagUid),
                        settings = Store.settings(this),
                        hasTags = Store.tags(this).isNotEmpty(),
                        nfcUsable = nfcUsable.value,
                        locked = locked.value,
                        deniedTick = deniedTick.intValue,
                        onUnlock = ::requestUnlock,
                        onMathSolved = { dismissAlarm(scheduleWakeCheck = true) },
                    )
                }
            }
        }
    }

    /** Restart the ring service if we were opened from the notification after a process death. */
    private fun ensureRinging() {
        val pending = Store.ringingAlarm(this)
        val fromIntent = intent?.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, 0) ?: 0
        when {
            RingService.ringingAlarmId.intValue != 0 -> Unit
            RingService.checkAlarmId.intValue != 0 -> Unit
            pending != 0 -> RingService.start(this, pending)
            fromIntent != 0 && Store.alarm(this, fromIntent) != null -> RingService.start(this, fromIntent)
            else -> finish() // nothing is ringing: stale launch
        }
    }

    private fun requestUnlock() {
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
    }

    /**
     * True if [uid] may stop the current alarm: the alarm's own tag if it has one that is
     * still paired, otherwise any paired tag.
     */
    private fun acceptsTag(uid: String): Boolean {
        if (!Store.isPaired(this, uid)) return false
        val id = RingService.ringingAlarmId.intValue.takeIf { it != 0 } ?: Store.ringingAlarm(this)
        val required = Store.alarm(this, id)?.tagUid?.takeIf { Store.tagName(this, it) != null }
        return required == null || required == NfcUid.normalise(uid)
    }

    /** Stops the ring; with the option on, arms the "are you awake?" check (not for the override). */
    private fun dismissAlarm(scheduleWakeCheck: Boolean) {
        val id = RingService.ringingAlarmId.intValue.takeIf { it != 0 } ?: Store.ringingAlarm(this)
        RingService.dismiss(this)
        val settings = Store.settings(this)
        if (scheduleWakeCheck && settings.wakeCheck && id > 0) {
            AlarmScheduler.armWakeCheck(this, id, settings.wakeCheckMinutes)
        }
        finish()
    }

    private fun confirmAwake() {
        RingService.confirmAwake(this)
        finish()
    }

    override fun onResume() {
        super.onResume()
        locked.value = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        nfcUsable.value = scanner.isEnabled
        scanner.start()
        lockHandler.removeCallbacks(lockPoll)
        lockHandler.post(lockPoll)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Refresh after the PIN/pattern bouncer closes.
        if (hasFocus) locked.value = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }

    override fun onPause() {
        lockHandler.removeCallbacks(lockPoll)
        scanner.stop()
        chord.cancel()
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE ->
                fingersOnScreen = ev.pointerCount
            MotionEvent.ACTION_POINTER_UP -> fingersOnScreen = ev.pointerCount - 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingersOnScreen = 0
        }
        if (fingersOnScreen != 2) chord.cancel()

        // As soon as a second finger lands, cancel the touch for the UI (no accidental
        // button press) and swallow everything until all fingers are lifted.
        if (!multiTouch && ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouch = true
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        if (multiTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                multiTouch = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isVolumeKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

    // Volume keys are swallowed while ringing so they can't be used to mute.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isVolumeKey(keyCode)) return super.onKeyDown(keyCode, event)
        event?.let(chord::onKeyDown)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isVolumeKey(keyCode)) return super.onKeyUp(keyCode, event)
        event?.let(chord::onKeyUp)
        return true
    }
}
