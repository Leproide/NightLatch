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

package io.github.leproide.nightlatch

import io.github.leproide.nightlatch.alarm.AlarmScheduler
import io.github.leproide.nightlatch.data.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmSchedulerTest {

    private val rome = ZoneId.of("Europe/Rome")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, rome)
    private fun alarm(h: Int, m: Int, days: Int = 0) = Alarm(1, h, m, days, true, "")

    @Test
    fun oneShotLaterToday() {
        // Tue 24 Nov 2026 06:00 -> 07:00 same day
        assertEquals(at(2026, 11, 24, 7, 0), AlarmScheduler.nextTrigger(alarm(7, 0), at(2026, 11, 24, 6, 0)))
    }

    @Test
    fun oneShotAlreadyPassedGoesToTomorrow() {
        assertEquals(at(2026, 11, 25, 7, 0), AlarmScheduler.nextTrigger(alarm(7, 0), at(2026, 11, 24, 7, 0)))
    }

    @Test
    fun weekdaysSkipTheWeekend() {
        // Fri 27 Nov 2026 08:00, weekdays 07:00 -> Mon 30 Nov 07:00
        assertEquals(
            at(2026, 11, 30, 7, 0),
            AlarmScheduler.nextTrigger(alarm(7, 0, Alarm.WEEKDAYS), at(2026, 11, 27, 8, 0)),
        )
    }

    @Test
    fun weeklySameDayAlreadyPassedIsNextWeek() {
        // Only Tuesday (bit 1); Tue 24 Nov 2026 08:00, alarm 07:00 -> Tue 1 Dec
        assertEquals(at(2026, 12, 1, 7, 0), AlarmScheduler.nextTrigger(alarm(7, 0, 0b10), at(2026, 11, 24, 8, 0)))
    }

    @Test
    fun springForwardGapRingsOneHourLater() {
        // 29 Mar 2026: in Rome 02:00 -> 03:00. A 02:30 alarm rings at 03:30 local.
        val t = AlarmScheduler.nextTrigger(alarm(2, 30), at(2026, 3, 29, 1, 0))
        assertNotNull(t)
        assertEquals(3, t!!.hour)
        assertEquals(30, t.minute)
        assertEquals(29, t.dayOfMonth)
    }

    @Test
    fun fallBackOverlapRingsOnce() {
        // 25 Oct 2026: 03:00 -> 02:00. A 02:30 alarm rings at the first 02:30.
        val from = at(2026, 10, 25, 1, 0)
        val t = AlarmScheduler.nextTrigger(alarm(2, 30), from)!!
        assertEquals(2, t.hour)
        assertEquals(30, t.minute)
        assertEquals(java.time.ZoneOffset.ofHours(2), t.offset)
    }
}
