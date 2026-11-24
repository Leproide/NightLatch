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

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class NightLatchApp : Application() {

    companion object {
        const val CHANNEL_RINGING = "ringing"
        const val CHANNEL_INFO = "info"
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        // Ringing: max importance for the full-screen intent; the service plays the sound itself.
        val ringing = NotificationChannel(
            CHANNEL_RINGING, getString(R.string.channel_ringing), NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val info = NotificationChannel(
            CHANNEL_INFO, getString(R.string.channel_info), NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannels(listOf(ringing, info))
    }
}
