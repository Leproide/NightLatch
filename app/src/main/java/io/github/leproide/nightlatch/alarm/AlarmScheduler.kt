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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import io.github.leproide.nightlatch.data.Alarm
import io.github.leproide.nightlatch.data.Store
import io.github.leproide.nightlatch.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Schedules alarms with [AlarmManager.setAlarmClock]: the most reliable API
 * (fires in Doze, shows the alarm icon in the status bar, never deferred).
 */
object AlarmScheduler {
    private const val TAG = "NightLatch/Scheduler"
    const val ACTION_FIRE = "io.github.leproide.nightlatch.action.FIRE"
    const val EXTRA_ALARM_ID = "alarm_id"

    /** Next trigger strictly after [from], or null if the alarm can never ring. */
    fun nextTrigger(alarm: Alarm, from: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        val zone: ZoneId = from.zone
        val today: LocalDate = from.toLocalDate()
        // Look up to 8 days ahead: covers every weekly pattern including "today, but already passed".
        for (offset in 0..7) {
            val day = today.plusDays(offset.toLong())
            if (alarm.isRepeating && !alarm.repeatsOn(day.dayOfWeek)) continue
            // atZone resolves DST gaps by moving forward, so a 02:30 alarm on the spring-forward night rings at 03:30.
            val candidate = LocalDateTime.of(day, java.time.LocalTime.of(alarm.hour, alarm.minute)).atZone(zone)
            if (candidate.isAfter(from)) return candidate
        }
        return null
    }

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun firePendingIntent(ctx: Context, alarmId: Int): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(
            ctx, alarmId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Schedules or cancels one alarm according to its enabled flag. Returns false if scheduling failed. */
    fun apply(ctx: Context, alarm: Alarm): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val operation = firePendingIntent(ctx, alarm.id)
        am.cancel(operation)
        if (!alarm.enabled) return true
        val trigger = nextTrigger(alarm) ?: return false
        if (!canScheduleExact(ctx)) {
            Log.w(TAG, "Exact alarm permission missing; alarm ${alarm.id} not scheduled")
            return false
        }
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger.toInstant().toEpochMilli(), show), operation)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "setAlarmClock refused", e)
            false
        }
    }

    // ---------------------------------------------------------- backup ring
    // While an alarm rings, a second exact alarm is kept ~60 s in the future and
    // pushed forward by the service every 30 s. If the process dies (swipe, OEM
    // killer, crash), it fires and restarts the ringing. Request code 0 is reserved
    // for it (real alarm ids start at 1). A force-stop cancels it too: nothing can
    // survive that, which is why the Guardian service blocks the Settings app.

    const val ACTION_BACKUP = "io.github.leproide.nightlatch.action.BACKUP"
    private const val BACKUP_DELAY_MS = 60_000L

    private fun backupPendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_BACKUP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun armBackup(ctx: Context) {
        if (!canScheduleExact(ctx)) return
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            ctx.getSystemService(AlarmManager::class.java).setAlarmClock(
                AlarmManager.AlarmClockInfo(System.currentTimeMillis() + BACKUP_DELAY_MS, show),
                backupPendingIntent(ctx),
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Backup alarm refused", e)
        }
    }

    fun cancelBackup(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(backupPendingIntent(ctx))
    }

    // ---------------------------------------------------------- wake check
    // Optional "are you awake?" prompt N minutes after a dismissal. Request code -1 is
    // reserved for it (alarms use ids >= 1, the backup ring uses 0): one pending at a time.

    const val ACTION_WAKE_CHECK = "io.github.leproide.nightlatch.action.WAKE_CHECK"

    private fun wakeCheckPendingIntent(ctx: Context, alarmId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, -1,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_WAKE_CHECK).putExtra(EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun armWakeCheck(ctx: Context, alarmId: Int, minutes: Int) {
        if (!canScheduleExact(ctx)) return
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            ctx.getSystemService(AlarmManager::class.java).setAlarmClock(
                AlarmManager.AlarmClockInfo(System.currentTimeMillis() + minutes * 60_000L, show),
                wakeCheckPendingIntent(ctx, alarmId),
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Wake check refused", e)
        }
    }

    fun cancelWakeCheck(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(wakeCheckPendingIntent(ctx, 0))
    }

    fun cancel(ctx: Context, alarmId: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(firePendingIntent(ctx, alarmId))
    }

    /** Re-arms every enabled alarm (boot, time change, app update, permission granted). */
    fun rescheduleAll(ctx: Context) {
        Store.alarms(ctx).forEach { apply(ctx, it) }
    }

    /** The soonest enabled alarm, for the "next alarm in ..." header. */
    fun soonest(ctx: Context): Pair<Alarm, ZonedDateTime>? =
        Store.alarms(ctx).filter { it.enabled }
            .mapNotNull { a -> nextTrigger(a)?.let { a to it } }
            .minByOrNull { it.second }
}
