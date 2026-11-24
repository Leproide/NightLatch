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

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.leproide.nightlatch.NightLatchApp
import io.github.leproide.nightlatch.R

/**
 * Permissions the alarm cannot work without, and how to send the user to fix them.
 *
 * Apps cannot grant these to themselves. The full-screen-intent permission in particular
 * can be revoked by the system when the app is reinstalled/updated outside the Play
 * Store: we detect it and bring the user to the right settings page with one tap
 * (banner in the app, notification after an update or a reboot).
 */
object Permissions {

    private const val NOTIFICATION_ID = 1003

    enum class Req(val label: Int) {
        NOTIFICATIONS(R.string.perm_notifications),
        FULL_SCREEN(R.string.perm_fullscreen),
        OVERLAY(R.string.perm_overlay),
        EXACT_ALARMS(R.string.perm_exact),
    }

    fun notificationsGranted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun missing(ctx: Context): List<Req> = buildList {
        if (!notificationsGranted(ctx)) add(Req.NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) add(Req.FULL_SCREEN)
        if (!Settings.canDrawOverlays(ctx)) add(Req.OVERLAY)
        if (!AlarmScheduler.canScheduleExact(ctx)) add(Req.EXACT_ALARMS)
    }

    /** Settings page where the user can turn [req] back on. */
    fun fixIntent(ctx: Context, req: Req): Intent {
        val pkg = Uri.fromParts("package", ctx.packageName, null)
        val intent = when {
            req == Req.FULL_SCREEN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            req == Req.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            req == Req.EXACT_ALARMS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            req == Req.NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * True when Android's "restricted settings" can block us: Android 13+ and the app was
     * not installed by an app store (Play Store). Accessibility (Guardian) is restricted
     * from Android 13, "display over other apps" from Android 15. The user unlocks them
     * from App info → ⋮ → Allow restricted settings (the entry appears only after a
     * blocked attempt). Unknown installer counts as sideloaded.
     */
    fun restrictedSettingsApply(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val installer = runCatching {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        }.getOrNull()
        return installer != "com.android.vending"
    }

    /**
     * Battery optimisation exemption. Not strictly required (setAlarmClock rings in Doze),
     * but OEM battery managers (Samsung, Xiaomi, Huawei, ...) can kill the app or its
     * ring service anyway: exempting it is the recommended setup.
     */
    fun batteryUnrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Direct "allow" dialog; falls back to the global list if the OEM doesn't support it. */
    @SuppressLint("BatteryLife") // legitimate: an alarm clock must not be killed by battery managers
    fun batteryIntents(ctx: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", ctx.packageName, null)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /** Starts the first battery intent the device can handle. */
    fun openBatterySettings(ctx: Context) {
        for (i in batteryIntents(ctx)) {
            try {
                ctx.startActivity(i)
                return
            } catch (_: Exception) {
                // try the next one
            }
        }
    }

    /** Removes the reminder once everything is granted again (called when the app resumes). */
    fun clearIfResolved(ctx: Context) {
        if (missing(ctx).none { it != Req.NOTIFICATIONS }) {
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    /**
     * Posts (or clears) a "missing permission" notification. Called after an app update
     * and after boot. Needs the notification permission itself: if that is the missing
     * one, only the in-app banner can help.
     */
    fun notifyIfMissing(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val missing = missing(ctx).filter { it != Req.NOTIFICATIONS }
        if (missing.isEmpty() || !notificationsGranted(ctx)) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        val first = missing.first()
        val pi = PendingIntent.getActivity(
            ctx, 2, fixIntent(ctx, first),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = ctx.getString(R.string.perm_missing_text, missing.joinToString(", ") { ctx.getString(it.label) })
        val n = NotificationCompat.Builder(ctx, NightLatchApp.CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(ctx.getString(R.string.perm_missing_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }
}
