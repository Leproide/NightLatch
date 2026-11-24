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

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.leproide.nightlatch.R
import io.github.leproide.nightlatch.ui.words
import io.github.leproide.nightlatch.data.PairedTag
import io.github.leproide.nightlatch.data.Store
import io.github.leproide.nightlatch.nfc.NfcUid
import io.github.leproide.nightlatch.ui.theme.Neon
import io.github.leproide.nightlatch.ui.theme.NeonButton
import io.github.leproide.nightlatch.ui.theme.NeonPanel
import io.github.leproide.nightlatch.ui.theme.SectionLabel

@Composable
fun TagsScreen(
    pairing: Boolean,
    hasNfc: Boolean,
    nfcEnabled: Boolean,
    @Suppress("UNUSED_PARAMETER") resumeTick: Int,
    onStartPairing: ((String) -> Unit) -> Unit,
    onStopPairing: () -> Unit,
) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val rev = Store.revision.intValue
    val tags = Store.tags(ctx)
    var message by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<PairedTag?>(null) }
    var removing by remember { mutableStateOf<PairedTag?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        when {
            !hasNfc -> NeonPanel(accent = Neon.Yellow) {
                Text(stringResource(Store.settings(ctx).mathWording.words().nfcMissing), color = Neon.Yellow)
            }
            !nfcEnabled -> NeonPanel(accent = Neon.Yellow) {
                Text(stringResource(R.string.nfc_off), color = Neon.Yellow)
                Spacer(Modifier.height(8.dp))
                NeonButton(stringResource(R.string.open_nfc_settings), color = Neon.Yellow) {
                    ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            pairing -> ScanningPanel(onStop = onStopPairing)
            else -> NeonButton(
                stringResource(R.string.scan_tag),
                color = Neon.Magenta,
                enabled = tags.size < Store.MAX_TAGS,
                modifier = Modifier.fillMaxWidth(),
            ) {
                message = null
                onStartPairing { uid ->
                    message = when {
                        NfcUid.isRandom(uid) -> ctx.getString(R.string.tag_random)
                        else -> when (Store.addTag(ctx, uid, "")) {
                            Store.AddTagResult.ADDED -> ctx.getString(R.string.tag_added, NfcUid.pretty(uid))
                            Store.AddTagResult.ALREADY_PAIRED -> ctx.getString(R.string.tag_already)
                            Store.AddTagResult.LIMIT -> ctx.getString(R.string.tag_limit, Store.MAX_TAGS)
                            Store.AddTagResult.INVALID -> ctx.getString(R.string.tag_invalid)
                        }
                    }
                    onStopPairing()
                }
            }
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text("> $it", color = Neon.Green, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel(stringResource(R.string.tags_title) + " ${tags.size}/${Store.MAX_TAGS}")
        if (tags.isEmpty()) {
            Text(stringResource(Store.settings(ctx).mathWording.words().tagsEmpty), color = Neon.Dim, style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tags, key = { it.uid }) { t ->
                NeonPanel(accent = Neon.Magenta) {
                    Text(t.name, style = MaterialTheme.typography.titleMedium, color = Neon.Text)
                    Text(NfcUid.pretty(t.uid), style = MaterialTheme.typography.bodySmall, color = Neon.Cyan)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeonButton(stringResource(R.string.rename), color = Neon.Cyan) { renaming = t }
                        NeonButton(stringResource(R.string.delete), color = Neon.Red) { removing = t }
                    }
                }
            }
        }
    }

    renaming?.let { t ->
        var name by remember(t.uid) { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = Neon.Panel,
            title = { Text(stringResource(R.string.rename), color = Neon.Cyan) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(PairedTag.MAX_NAME_LENGTH) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tag_name_hint)) },
                )
            },
            confirmButton = {
                NeonButton(stringResource(R.string.save), filled = true) {
                    Store.renameTag(ctx, t.uid, name); renaming = null
                }
            },
            dismissButton = { NeonButton(stringResource(R.string.cancel), color = Neon.Dim) { renaming = null } },
        )
    }

    removing?.let { t ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = Neon.Panel,
            title = { Text(stringResource(R.string.remove_tag_confirm, t.name), color = Neon.Red) },
            confirmButton = {
                NeonButton(stringResource(R.string.delete), color = Neon.Red, filled = true) {
                    Store.removeTag(ctx, t.uid); removing = null
                }
            },
            dismissButton = { NeonButton(stringResource(R.string.cancel), color = Neon.Dim) { removing = null } },
        )
    }
}

@Composable
private fun ScanningPanel(onStop: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "scan").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    NeonPanel(accent = Neon.Magenta) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("((( ◉ )))", style = MaterialTheme.typography.displayMedium, color = Neon.Magenta, modifier = Modifier.alpha(pulse))
            Text(stringResource(R.string.scanning), style = MaterialTheme.typography.bodyMedium, color = Neon.Text)
            Spacer(Modifier.height(10.dp))
            NeonButton(stringResource(R.string.stop), color = Neon.Dim, onClick = onStop)
        }
    }
}
