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

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.leproide.nightlatch.R
import io.github.leproide.nightlatch.alarm.AlarmScheduler
import io.github.leproide.nightlatch.alarm.GuardianService
import io.github.leproide.nightlatch.alarm.Permissions
import io.github.leproide.nightlatch.data.MathDifficulty
import io.github.leproide.nightlatch.data.MathOp
import io.github.leproide.nightlatch.data.MathWording
import io.github.leproide.nightlatch.ui.words
import io.github.leproide.nightlatch.math.MathChallenge
import io.github.leproide.nightlatch.data.Settings as AppSettings
import io.github.leproide.nightlatch.data.Store
import io.github.leproide.nightlatch.ui.theme.Neon
import io.github.leproide.nightlatch.ui.theme.NeonButton
import io.github.leproide.nightlatch.ui.theme.NeonPanel
import io.github.leproide.nightlatch.ui.theme.SectionLabel

@Composable
fun ConfigScreen(resumeTick: Int, onRequestNotifications: () -> Unit) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val rev = Store.revision.intValue
    val s = Store.settings(ctx)
    val hasTags = Store.tags(ctx).isNotEmpty()
    fun save(n: AppSettings) = Store.saveSettings(ctx, n)
    val w = s.mathWording.words()

    // Backup: export to / import from a file the user picks (Storage Access Framework,
    // no storage permission needed).
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Store.exportJson(ctx).toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        Toast.makeText(ctx, if (ok) R.string.backup_exported else R.string.backup_failed, Toast.LENGTH_LONG).show()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                // Read at most MAX_IMPORT_BYTES + 1: anything bigger is rejected.
                val buf = input.readNBytesCompat(Store.MAX_IMPORT_BYTES + 1)
                if (buf.size > Store.MAX_IMPORT_BYTES) null else String(buf, Charsets.UTF_8)
            }
        }.getOrNull()
        val ok = text != null && Store.importJson(ctx, text)
        if (ok) AlarmScheduler.rescheduleAll(ctx)
        Toast.makeText(ctx, if (ok) R.string.backup_imported else R.string.backup_invalid, Toast.LENGTH_LONG).show()
    }

    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = res.data?.let {
            IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        }
        // null = "Default" picked; anything not content/android.resource is rejected by the store.
        save(s.copy(ringtoneUri = uri?.toString()))
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ------------------------------------------------------------ math
        SectionLabel(stringResource(w.section))
        NeonPanel(accent = Neon.Cyan) {
            Text(stringResource(R.string.cfg_difficulty), style = MaterialTheme.typography.labelMedium, color = Neon.Dim)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MathDifficulty.entries.forEach { d ->
                    val on = d == s.mathDifficulty
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (on) Neon.Cyan else Neon.Void)
                            .border(1.dp, if (on) Neon.Cyan else Neon.Dim)
                            .clickable { save(s.copy(mathDifficulty = d)) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(
                                when (d) {
                                    MathDifficulty.EASY -> R.string.diff_easy
                                    MathDifficulty.NORMAL -> R.string.diff_normal
                                    MathDifficulty.HARD -> R.string.diff_hard
                                }
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) Neon.Void else Neon.Dim,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            // Operations: shared by every level; the level only changes length and number size.
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.cfg_ops), style = MaterialTheme.typography.labelMedium, color = Neon.Dim)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MathOp.entries.forEach { op ->
                    val on = s.mathOps and op.bit != 0
                    // At least one operation must stay enabled.
                    val canToggle = !on || (s.mathOps and op.bit.inv()) != 0
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (on) Neon.Magenta else Neon.Void)
                            .border(1.dp, if (on) Neon.Magenta else Neon.Dim)
                            .clickable(enabled = canToggle) { save(s.copy(mathOps = s.mathOps xor op.bit)) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(op.symbol, style = MaterialTheme.typography.titleLarge, color = if (on) Neon.Void else Neon.Dim)
                    }
                }
            }

            // Live preview: three problems with the current level + operations.
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.cfg_preview), style = MaterialTheme.typography.labelMedium, color = Neon.Dim)
            val preview = remember(s.mathDifficulty, s.mathOps) {
                List(3) { MathChallenge.next(s.mathDifficulty, s.mathOps) }
            }
            preview.forEach { p ->
                Text("> ${p.text} = ${p.answer}", style = MaterialTheme.typography.bodyLarge, color = Neon.Cyan)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(w.count, s.mathCount), style = MaterialTheme.typography.bodyLarge, color = Neon.Text)
            Slider(
                value = s.mathCount.toFloat(),
                onValueChange = { save(s.copy(mathCount = it.toInt())) },
                valueRange = AppSettings.MIN_MATH_COUNT.toFloat()..AppSettings.MAX_MATH_COUNT.toFloat(),
                steps = AppSettings.MAX_MATH_COUNT - AppSettings.MIN_MATH_COUNT - 1,
                colors = SliderDefaults.colors(thumbColor = Neon.Magenta, activeTrackColor = Neon.Magenta),
            )
            ToggleRow(
                text = stringResource(w.toggle),
                checked = s.mathFallbackEnabled,
                onChange = { save(s.copy(mathFallbackEnabled = it)) },
            )
            if (!hasTags) {
                Text(stringResource(w.forced), style = MaterialTheme.typography.bodySmall, color = Neon.Yellow)
            }
        }

        // ----------------------------------------------------------- sound
        SectionLabel(stringResource(R.string.cfg_sound))
        NeonPanel(accent = Neon.Magenta) {
            val current = s.ringtoneUri?.let { runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(it))?.getTitle(ctx) }.getOrNull() }
            Text(current ?: stringResource(R.string.ringtone_default), style = MaterialTheme.typography.bodyLarge, color = Neon.Text)
            Spacer(Modifier.height(8.dp))
            NeonButton(stringResource(R.string.pick_ringtone), color = Neon.Magenta) {
                val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, s.ringtoneUri?.let(Uri::parse))
                runCatching { ringtonePicker.launch(i) }
            }
            ToggleRow(stringResource(R.string.cfg_vibrate), s.vibrate) { save(s.copy(vibrate = it)) }
            ToggleRow(stringResource(R.string.cfg_lock_volume), s.lockVolume) { save(s.copy(lockVolume = it)) }
        }

        // -------------------------------------------------------- cosmetic
        SectionLabel(stringResource(R.string.cfg_cosmetic), color = Neon.Magenta)
        NeonPanel(accent = Neon.Magenta) {
            ToggleRow(stringResource(R.string.cfg_ring_glitch), s.ringGlitch) { save(s.copy(ringGlitch = it)) }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.cfg_wording), style = MaterialTheme.typography.labelMedium, color = Neon.Dim)
            Spacer(Modifier.height(6.dp))
            MathWording.entries.forEach { option ->
                val on = option == s.mathWording
                val ow = option.words()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .background(if (on) Neon.Magenta.copy(alpha = 0.2f) else Neon.Void)
                        .border(1.dp, if (on) Neon.Magenta else Neon.Dim)
                        .clickable { save(s.copy(mathWording = option)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        (if (on) "◉ " else "○ ") + stringResource(ow.name),
                        style = MaterialTheme.typography.bodyLarge, color = if (on) Neon.Magenta else Neon.Text,
                    )
                    // Preview of how it reads on the ringing screen.
                    Text(
                        "[ ${stringResource(ow.ring)} ] · ${stringResource(ow.progress, 2, 3)}",
                        style = MaterialTheme.typography.bodySmall, color = Neon.Dim, maxLines = 1,
                    )
                }
            }
        }

        // ---------------------------------------------------------- extras
        // Optional features, each on its own switch (all off by default).
        SectionLabel(stringResource(R.string.cfg_extras), color = Neon.Yellow)
        NeonPanel(accent = Neon.Yellow) {
            ToggleRow(stringResource(R.string.cfg_ramp), s.rampVolume) { save(s.copy(rampVolume = it)) }
            Text(stringResource(R.string.cfg_ramp_desc), style = MaterialTheme.typography.bodySmall, color = Neon.Dim)
            if (s.rampVolume) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.RAMP_CHOICES.forEach { sec ->
                        val on = s.rampSeconds == sec
                        Box(
                            Modifier
                                .weight(1f)
                                .background(if (on) Neon.Yellow else Neon.Void)
                                .border(1.dp, if (on) Neon.Yellow else Neon.Dim)
                                .clickable { save(s.copy(rampSeconds = sec)) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$sec s", style = MaterialTheme.typography.labelLarge, color = if (on) Neon.Void else Neon.Dim)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            ToggleRow(stringResource(R.string.cfg_wake_check), s.wakeCheck) { save(s.copy(wakeCheck = it)) }
            Text(stringResource(R.string.cfg_wake_check_desc), style = MaterialTheme.typography.bodySmall, color = Neon.Dim)
            if (s.wakeCheck) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.WAKE_CHECK_CHOICES.forEach { m ->
                        val on = s.wakeCheckMinutes == m
                        Box(
                            Modifier
                                .weight(1f)
                                .background(if (on) Neon.Yellow else Neon.Void)
                                .border(1.dp, if (on) Neon.Yellow else Neon.Dim)
                                .clickable { save(s.copy(wakeCheckMinutes = m)) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$m min", style = MaterialTheme.typography.labelLarge, color = if (on) Neon.Void else Neon.Dim)
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------- backup
        SectionLabel(stringResource(R.string.cfg_backup), color = Neon.Cyan)
        NeonPanel(accent = Neon.Cyan) {
            Text(stringResource(R.string.cfg_backup_desc), style = MaterialTheme.typography.bodySmall, color = Neon.Dim)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton(stringResource(R.string.cfg_export), Modifier.weight(1f), color = Neon.Cyan, compact = true) {
                    runCatching { exportLauncher.launch("nightlatch-backup.json") }
                }
                NeonButton(stringResource(R.string.cfg_import), Modifier.weight(1f), color = Neon.Magenta, compact = true) {
                    runCatching { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                }
            }
        }

        // ---------------------------------------------------------- system
        // Keyed on resumeTick: permission/Guardian states are re-read every time the
        // user comes back from a system settings screen (an unused parameter would be
        // ignored by Compose and the rows would keep showing the old state).
        key(resumeTick) {
        SectionLabel(stringResource(R.string.cfg_system))
        NeonPanel(accent = Neon.Green) {
            StatusRow(stringResource(R.string.perm_notifications), notificationsOk(ctx)) { onRequestNotifications() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val ok = ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
                StatusRow(stringResource(R.string.perm_fullscreen), ok) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", ctx.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            StatusRow(stringResource(R.string.perm_overlay), Settings.canDrawOverlays(ctx)) {
                ctx.startActivity(Permissions.fixIntent(ctx, Permissions.Req.OVERLAY))
            }
            StatusRow(stringResource(R.string.perm_battery), Permissions.batteryUnrestricted(ctx)) {
                Permissions.openBatterySettings(ctx)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                StatusRow(stringResource(R.string.perm_exact), AlarmScheduler.canScheduleExact(ctx)) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", ctx.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        // -------------------------------------------------------- guardian
        SectionLabel(stringResource(R.string.cfg_guardian), color = Neon.Red)
        NeonPanel(accent = Neon.Red) {
            Text(stringResource(R.string.cfg_guardian_text), style = MaterialTheme.typography.bodyMedium, color = Neon.Text)
            Spacer(Modifier.height(6.dp))
            StatusRow(stringResource(R.string.guardian_label), GuardianService.isEnabled(ctx)) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Text(stringResource(R.string.cfg_guardian_restricted), style = MaterialTheme.typography.bodySmall, color = Neon.Dim)
        }
        }
        Text(stringResource(R.string.cfg_footer), style = MaterialTheme.typography.bodySmall, color = Neon.Dim)
        Spacer(Modifier.height(16.dp))
    }
}

internal fun notificationsOk(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun ToggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = Neon.Text)
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Neon.Void, checkedTrackColor = Neon.Cyan),
        )
    }
}

@Composable
internal fun StatusRow(text: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Neon.Text)
        if (ok) {
            Text("[ ${stringResource(R.string.status_ok)} ]", style = MaterialTheme.typography.labelLarge, color = Neon.Green)
        } else {
            NeonButton(stringResource(R.string.status_fix), color = Neon.Red) { runCatching(onFix) }
        }
    }
}

/** Reads up to [limit] bytes (InputStream.readNBytes is API 33+). */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val n = read(chunk, 0, minOf(chunk.size, limit - out.size()))
        if (n < 0) break
        out.write(chunk, 0, n)
    }
    return out.toByteArray()
}
