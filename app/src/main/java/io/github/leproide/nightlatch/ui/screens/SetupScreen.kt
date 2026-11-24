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

package io.github.leproide.nightlatch.ui.screens

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.leproide.nightlatch.R
import io.github.leproide.nightlatch.data.Store
import io.github.leproide.nightlatch.ui.words
import io.github.leproide.nightlatch.alarm.AlarmScheduler
import io.github.leproide.nightlatch.alarm.GuardianService
import io.github.leproide.nightlatch.alarm.Permissions
import io.github.leproide.nightlatch.ui.theme.GlitchText
import io.github.leproide.nightlatch.ui.theme.Neon
import io.github.leproide.nightlatch.ui.theme.NeonButton
import io.github.leproide.nightlatch.ui.theme.NeonPanel
import io.github.leproide.nightlatch.ui.theme.SectionLabel

/**
 * First-launch setup wizard: one screen listing every permission the alarm needs,
 * each with its own FIX button. States are re-read every time the user comes back
 * from a system settings screen (keyed on [resumeTick]). Only the steps that apply
 * to this Android version / hardware are shown. The user can always continue.
 */
@Composable
fun SetupScreen(
    resumeTick: Int,
    hasNfc: Boolean,
    nfcEnabled: Boolean,
    onRequestNotifications: () -> Unit,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    fun open(intent: Intent) {
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    val pkgUri = Uri.fromParts("package", ctx.packageName, null)

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlitchText("NIGHT//LATCH", MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge, color = Neon.Magenta)
        Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium, color = Neon.Text)

        // Sideloaded apps on Android 13+: some settings stay greyed out until the user
        // allows "restricted settings" from the App info page. Explain it up front.
        if (Permissions.restrictedSettingsApply(ctx)) {
            NeonPanel(accent = Neon.Yellow) {
                Text(stringResource(R.string.setup_restricted_title), style = MaterialTheme.typography.titleMedium, color = Neon.Yellow)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.setup_restricted_desc), style = MaterialTheme.typography.bodySmall, color = Neon.Text)
                Spacer(Modifier.height(10.dp))
                NeonButton(stringResource(R.string.setup_open_app_info), Modifier.fillMaxWidth(), color = Neon.Yellow) {
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
                }
            }
        }

        key(resumeTick) {
            var requiredMissing = false

            SectionLabel(stringResource(R.string.setup_required))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val ok = notificationsOk(ctx)
                if (!ok) requiredMissing = true
                Step(stringResource(R.string.perm_notifications), stringResource(R.string.setup_notif_desc), ok) {
                    onRequestNotifications()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val ok = ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
                if (!ok) requiredMissing = true
                Step(stringResource(R.string.perm_fullscreen), stringResource(R.string.setup_fullscreen_desc), ok) {
                    open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri))
                }
            }
            run {
                val ok = Settings.canDrawOverlays(ctx)
                if (!ok) requiredMissing = true
                Step(stringResource(R.string.perm_overlay), stringResource(R.string.setup_overlay_desc), ok) {
                    open(Permissions.fixIntent(ctx, Permissions.Req.OVERLAY))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val ok = AlarmScheduler.canScheduleExact(ctx)
                if (!ok) requiredMissing = true
                Step(stringResource(R.string.perm_exact), stringResource(R.string.setup_exact_desc), ok) {
                    open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri))
                }
            }

            SectionLabel(stringResource(R.string.setup_recommended), color = Neon.Cyan)

            Step(
                stringResource(R.string.perm_battery),
                stringResource(R.string.setup_battery_desc),
                Permissions.batteryUnrestricted(ctx),
                accent = Neon.Cyan,
            ) { Permissions.openBatterySettings(ctx) }
            if (hasNfc) {
                Step(stringResource(R.string.setup_nfc), stringResource(R.string.setup_nfc_desc), nfcEnabled, accent = Neon.Cyan) {
                    open(Intent(Settings.ACTION_NFC_SETTINGS))
                }
            } else {
                Text(stringResource(Store.settings(ctx).mathWording.words().nfcMissing), style = MaterialTheme.typography.bodySmall, color = Neon.Yellow)
            }
            Step(
                stringResource(R.string.guardian_label),
                stringResource(R.string.setup_guardian_desc) + "\n" + stringResource(R.string.cfg_guardian_restricted),
                GuardianService.isEnabled(ctx),
                accent = Neon.Cyan,
            ) {
                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }

            Spacer(Modifier.height(8.dp))
            NeonButton(
                stringResource(if (requiredMissing) R.string.setup_skip else R.string.setup_done),
                Modifier.fillMaxWidth(),
                color = if (requiredMissing) Neon.Dim else Neon.Green,
                filled = !requiredMissing,
                onClick = onDone,
            )
            if (requiredMissing) {
                Text(stringResource(R.string.setup_skip_hint), style = MaterialTheme.typography.bodySmall, color = Neon.Yellow)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Step(title: String, description: String, ok: Boolean, accent: Color = Neon.Magenta, onFix: () -> Unit) {
    NeonPanel(accent = if (ok) Neon.Green else accent) {
        StatusRow(title, ok, onFix)
        Text(description, style = MaterialTheme.typography.bodySmall, color = Neon.Dim)
    }
}
