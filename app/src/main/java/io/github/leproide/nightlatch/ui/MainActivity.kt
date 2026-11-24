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

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.leproide.nightlatch.R
import io.github.leproide.nightlatch.alarm.AlarmScheduler
import io.github.leproide.nightlatch.alarm.Permissions
import io.github.leproide.nightlatch.alarm.RingService
import io.github.leproide.nightlatch.data.Store
import io.github.leproide.nightlatch.nfc.TagScanner
import io.github.leproide.nightlatch.ui.screens.AlarmsScreen
import io.github.leproide.nightlatch.ui.screens.ConfigScreen
import io.github.leproide.nightlatch.ui.screens.SetupScreen
import io.github.leproide.nightlatch.ui.screens.StatusRow
import io.github.leproide.nightlatch.ui.screens.TagsScreen
import io.github.leproide.nightlatch.ui.theme.CyberBackground
import io.github.leproide.nightlatch.ui.theme.GlitchText
import io.github.leproide.nightlatch.ui.theme.Neon
import io.github.leproide.nightlatch.ui.theme.NeonPanel
import io.github.leproide.nightlatch.ui.theme.NightLatchTheme

class MainActivity : ComponentActivity() {

    /** Pairing mode: while true, reader mode is on and scanned UIDs go to [onPairUid]. */
    private val pairing = mutableStateOf(false)
    private var onPairUid: ((String) -> Unit)? = null
    private lateinit var scanner: TagScanner

    /** Bumped on resume so status rows (permissions, NFC on/off) refresh. */
    private val resumeTick = mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        scanner = TagScanner(this) { uid -> if (pairing.value) onPairUid?.invoke(uid) }

        // Permissions are asked by the first-launch setup wizard (SetupScreen), not here.

        setContent {
            NightLatchTheme {
                MainScaffold(
                    pairing = pairing.value,
                    resumeTick = resumeTick.intValue,
                    hasNfc = scanner.hasNfc,
                    nfcEnabled = { scanner.isEnabled },
                    // Reader mode stays on while the app is visible (see onResume): pairing
                    // only decides whether a scanned UID is used.
                    onStartPairing = { cb -> onPairUid = cb; pairing.value = true },
                    onStopPairing = { pairing.value = false; onPairUid = null },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        Permissions.clearIfResolved(this)
        // If an alarm is ringing, the ringing screen always wins.
        val ringing = RingService.ringingAlarmId.intValue.takeIf { it != 0 } ?: Store.ringingAlarm(this)
        if (ringing != 0) {
            startActivity(Intent(this, AlarmActivity::class.java).putExtra(AlarmScheduler.EXTRA_ALARM_ID, ringing))
        }
        // Always on while visible: no tag touching the phone ends up in the default NFC app.
        scanner.start()
    }

    override fun onPause() {
        scanner.stop()
        super.onPause()
    }
}

private enum class Tab(val label: Int) { ALARMS(R.string.tab_alarms), TAGS(R.string.tab_tags), CONFIG(R.string.tab_config) }

@Composable
private fun MainScaffold(
    pairing: Boolean,
    resumeTick: Int,
    hasNfc: Boolean,
    nfcEnabled: () -> Boolean,
    onStartPairing: ((String) -> Unit) -> Unit,
    onStopPairing: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.ALARMS) }
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val rev = Store.revision.intValue
    if (!Store.onboarded(ctx)) {
        CyberBackground {
            SetupScreen(
                resumeTick = resumeTick,
                hasNfc = hasNfc,
                nfcEnabled = nfcEnabled(),
                onRequestNotifications = onRequestNotifications,
                onDone = { Store.setOnboarded(ctx) },
            )
        }
        return
    }
    CyberBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlitchText("NIGHT//LATCH", MaterialTheme.typography.headlineMedium)
            }
            // Required permission revoked (e.g. full-screen alarm after an update): one-tap fix.
            val missing = remember(resumeTick) { Permissions.missing(ctx) }
            if (missing.isNotEmpty()) {
                NeonPanel(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), accent = Neon.Red) {
                    Text(stringResource(R.string.perm_missing_title), style = MaterialTheme.typography.titleMedium, color = Neon.Red)
                    missing.forEach { req ->
                        StatusRow(stringResource(req.label), ok = false) {
                            if (req == Permissions.Req.NOTIFICATIONS) onRequestNotifications()
                            else ctx.startActivity(Permissions.fixIntent(ctx, req))
                        }
                    }
                }
            }
            // Recommended: battery optimisation still on (OEM battery managers can kill the alarm).
            val batteryOk = remember(resumeTick) { Permissions.batteryUnrestricted(ctx) }
            if (!batteryOk) {
                NeonPanel(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), accent = Neon.Yellow) {
                    Text(stringResource(R.string.setup_recommended), style = MaterialTheme.typography.titleMedium, color = Neon.Yellow)
                    StatusRow(stringResource(R.string.perm_battery), ok = false) { Permissions.openBatterySettings(ctx) }
                    Text(stringResource(R.string.setup_battery_desc), style = MaterialTheme.typography.bodySmall, color = Neon.Dim)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.ALARMS -> AlarmsScreen()
                    Tab.TAGS -> TagsScreen(
                        pairing = pairing,
                        hasNfc = hasNfc,
                        nfcEnabled = nfcEnabled(),
                        resumeTick = resumeTick,
                        onStartPairing = onStartPairing,
                        onStopPairing = onStopPairing,
                    )
                    Tab.CONFIG -> ConfigScreen(resumeTick = resumeTick, onRequestNotifications = onRequestNotifications)
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                Tab.entries.forEach { t ->
                    val selected = t == tab
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .background(if (selected) Neon.Cyan.copy(alpha = 0.12f) else Neon.Panel)
                            .border(1.dp, if (selected) Neon.Cyan else Neon.PanelHi)
                            .clickable {
                                if (tab == Tab.TAGS && t != Tab.TAGS) onStopPairing()
                                tab = t
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(t.label),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) Neon.Cyan else Neon.Dim,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
