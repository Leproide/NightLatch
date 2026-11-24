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

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import io.github.leproide.nightlatch.data.Store
import io.github.leproide.nightlatch.ui.AlarmActivity

/**
 * "Guardian": optional, user-enabled accessibility service that makes the alarm
 * hard to escape while it rings. When an alarm is ringing and a foreign window
 * comes to the foreground (Settings / App info "Force stop", package installer,
 * launcher, notification shade with the task manager, power menu), it sends the
 * user back to the ringing screen.
 *
 * Privacy: it only looks at the package/class name of window-state events; it
 * never reads window content (canRetrieveWindowContent=false) and is completely
 * inert when no alarm is ringing.
 *
 * Safety: the phone/dialer, telecom and emergency packages and the keyboard are
 * always allowed, so emergency calls are never blocked.
 */
class GuardianService : AccessibilityService() {

    companion object {
        private const val TAG = "NightLatch/Guardian"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val MIN_ACTION_INTERVAL_MS = 400L
        private const val STALE_GRACE_MS = 5L * 60L * 1000L

        /** Always allowed, ringing or not (calls and emergency). */
        private val ALWAYS_ALLOWED = setOf(
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.emergency",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.dialer",
            "com.android.incallui",
        )

        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, GuardianService::class.java)
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    private var lastActionAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Inert when not ringing. The persisted flag also covers the window between a
        // process restart and the backup alarm bringing the sound back.
        if (RingService.ringingAlarmId.intValue == 0) {
            if (Store.ringingAlarm(this) == 0) return
            // Fail-open: the alarm itself never stops on its own, but if no ring service has
            // reported a heartbeat for several minutes (and the 60 s backup alarm failed to
            // bring it back), the flag is stale: clear it so Guardian can't lock the phone
            // with nothing ringing.
            val silentFor = System.currentTimeMillis() - Store.lastRingHeartbeat(this)
            if (silentFor > STALE_GRACE_MS || silentFor < 0) {
                Store.setRingingAlarm(this, 0)
                AlarmScheduler.cancelBackup(this)
                return
            }
        }
        val pkg = event.packageName?.toString() ?: return
        if (isAllowed(pkg)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < MIN_ACTION_INTERVAL_MS) return
        lastActionAt = now

        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (pkg == SYSTEM_UI) {
            // On the lock screen SystemUI also hosts the PIN/pattern bouncer the user needs
            // to unlock for NFC: only intervene on the power menu there.
            val cls = event.className?.toString().orEmpty()
            if (locked && !cls.contains("GlobalActions", ignoreCase = true)) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
        } else {
            Log.i(TAG, "Foreign window $pkg while ringing, returning to alarm")
        }
        bringAlarmBack()
    }

    private fun isAllowed(pkg: String): Boolean {
        if (pkg == packageName || pkg in ALWAYS_ALLOWED) return true
        val defaultDialer = runCatching { getSystemService(TelecomManager::class.java).defaultDialerPackage }.getOrNull()
        if (pkg == defaultDialer) return true
        // Keyboards (needed to type math answers).
        val imm = getSystemService(InputMethodManager::class.java)
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    private fun bringAlarmBack() {
        // Bound accessibility services are exempt from background-activity-start limits.
        try {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(AlarmScheduler.EXTRA_ALARM_ID, Store.ringingAlarm(this))
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot bring alarm screen back", e)
        }
    }

    override fun onInterrupt() = Unit
}
