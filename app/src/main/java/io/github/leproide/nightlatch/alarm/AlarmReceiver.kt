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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.leproide.nightlatch.data.Store

/** Receives the AlarmManager trigger (non-exported, reached only via our PendingIntent). */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmScheduler.ACTION_BACKUP) {
            // Process was killed while ringing: resume.
            val ringing = Store.ringingAlarm(context)
            if (ringing != 0) RingService.start(context, ringing)
            return
        }
        if (intent.action == AlarmScheduler.ACTION_WAKE_CHECK) {
            val id = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, 0)
            if (id > 0) RingService.startCheck(context, id)
            return
        }
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        val alarm = Store.alarm(context, id)
        if (alarm == null || !alarm.enabled) {
            Log.i("NightLatch/Receiver", "Stale trigger for alarm $id, ignored")
            return
        }

        // Re-arm first: if anything below fails, tomorrow's alarm is already set.
        if (alarm.isRepeating) {
            AlarmScheduler.apply(context, alarm)
        } else {
            Store.upsertAlarm(context, alarm.copy(enabled = false))
            AlarmScheduler.cancel(context, alarm.id)
        }

        RingService.start(context, alarm.id)
    }
}
