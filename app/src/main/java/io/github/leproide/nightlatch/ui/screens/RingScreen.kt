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

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.leproide.nightlatch.R
import io.github.leproide.nightlatch.ui.words
import io.github.leproide.nightlatch.data.Alarm
import io.github.leproide.nightlatch.data.Settings
import io.github.leproide.nightlatch.math.MathChallenge
import io.github.leproide.nightlatch.ui.theme.CyberBackground
import io.github.leproide.nightlatch.ui.theme.GlitchField
import io.github.leproide.nightlatch.ui.theme.GlitchText
import io.github.leproide.nightlatch.ui.theme.glitchSlices
import io.github.leproide.nightlatch.ui.theme.rememberGlitchBursts
import io.github.leproide.nightlatch.ui.theme.Neon
import io.github.leproide.nightlatch.ui.theme.NeonButton
import io.github.leproide.nightlatch.ui.theme.NeonPanel
import kotlinx.coroutines.delay
import java.time.LocalTime

@Composable
fun RingScreen(
    alarm: Alarm?,
    /** Name of the only tag that stops this alarm, or null if any paired tag does. */
    requiredTagName: String?,
    settings: Settings,
    hasTags: Boolean,
    nfcUsable: Boolean,
    locked: Boolean,
    deniedTick: Int,
    onUnlock: () -> Unit,
    onMathSolved: () -> Unit,
) {
    val ctx = LocalContext.current
    // Math is forced whenever a tag can't be used, so the alarm is never un-dismissable.
    val tagPossible = hasTags && nfcUsable
    val mathAllowed = settings.mathFallbackEnabled || !tagPossible
    var mathMode by remember { mutableStateOf(!tagPossible) }

    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { now = LocalTime.now(); delay(1000) }
    }

    var showDenied by remember { mutableStateOf(false) }
    LaunchedEffect(deniedTick) {
        if (deniedTick > 0) { showDenied = true; delay(1600); showDenied = false }
    }

    // Glitch effect (setting, on by default): backdrop + header + NFC prompt.
    // The math panel and the buttons are never glitched.
    val fx = settings.ringGlitch
    val burst by rememberGlitchBursts(fx)

    CyberBackground {
        if (fx) GlitchField(burst)
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Column(
                Modifier.glitchSlices(burst.intensity, burst.seed),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.ring_title), style = MaterialTheme.typography.titleLarge, color = Neon.Magenta)
                GlitchText(formatTime(ctx, now.hour, now.minute), MaterialTheme.typography.displayLarge, intense = true, animated = fx)
                alarm?.label?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = Neon.Dim)
                }
            }
            Spacer(Modifier.weight(1f))

            if (mathMode && mathAllowed) {
                MathPanel(settings, onSolved = onMathSolved)
                if (tagPossible) {
                    NeonButton(stringResource(R.string.ring_back_to_tag), color = Neon.Dim) { mathMode = false }
                }
            } else {
                if (locked && tagPossible) {
                    // Most phones (Pixel included) don't read NFC tags while locked: unlock first.
                    // Fingerprint/face unlock works straight on this screen; a tap opens the PIN prompt.
                    Box(Modifier.glitchSlices(burst.intensity * 0.6f, burst.seed + 7)) { LockedPrompt(onUnlock) }
                } else {
                    Box(Modifier.glitchSlices(burst.intensity * 0.6f, burst.seed + 7)) { ScanPrompt(showDenied, requiredTagName) }
                }
                if (mathAllowed) {
                    NeonButton(stringResource(settings.mathWording.words().ring), color = Neon.Cyan) { mathMode = true }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LockedPrompt(onUnlock: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "lock").animateFloat(
        0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    NeonPanel(accent = Neon.Yellow, onClick = onUnlock) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("[ ▣ ]", style = MaterialTheme.typography.displayMedium, color = Neon.Yellow, modifier = Modifier.alpha(pulse))
            Text(
                stringResource(R.string.ring_locked_title),
                style = MaterialTheme.typography.titleMedium, color = Neon.Yellow, textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.ring_locked_text),
                style = MaterialTheme.typography.bodyMedium, color = Neon.Text, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ScanPrompt(denied: Boolean, requiredTagName: String?) {
    val pulse by rememberInfiniteTransition(label = "ring").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "pulse",
    )
    NeonPanel(accent = if (denied) Neon.Red else Neon.Magenta) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "((( ◉ )))",
                style = MaterialTheme.typography.displayMedium,
                color = if (denied) Neon.Red else Neon.Magenta,
                modifier = Modifier.alpha(pulse),
            )
            Text(
                when {
                    denied -> stringResource(R.string.ring_unknown_tag)
                    requiredTagName != null -> stringResource(R.string.ring_scan_named, requiredTagName)
                    else -> stringResource(R.string.ring_scan)
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (denied) Neon.Red else Neon.Text,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MathPanel(settings: Settings, onSolved: () -> Unit) {
    var problem by remember { mutableStateOf(MathChallenge.next(settings.mathDifficulty, settings.mathOps)) }
    var solved by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    fun submit() {
        val value = MathChallenge.parse(input)
        input = ""
        if (value == problem.answer) {
            wrong = false
            solved++
            if (solved >= settings.mathCount) { onSolved(); return }
        } else {
            wrong = true
        }
        problem = MathChallenge.next(settings.mathDifficulty, settings.mathOps)
    }

    NeonPanel(accent = if (wrong) Neon.Red else Neon.Cyan) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(settings.mathWording.words().progress, solved, settings.mathCount),
                style = MaterialTheme.typography.labelMedium, color = Neon.Magenta,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${problem.text} = ?",
                // Chains are longer: smaller type so they stay on one or two lines.
                style = if (problem.text.length > 10) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayMedium, color = Neon.Text, textAlign = TextAlign.Center)
            if (wrong) Text(stringResource(settings.mathWording.words().wrong), style = MaterialTheme.typography.bodyMedium, color = Neon.Red)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { v -> input = v.filter(Char::isDigit).take(6) },
                singleLine = true,
                label = { Text(stringResource(R.string.math_answer_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            NeonButton(stringResource(R.string.submit), filled = true, enabled = input.isNotEmpty(), onClick = ::submit)
        }
    }
}

/**
 * "Are you awake?" prompt (optional wake check). Confirm with the on-screen button before
 * the countdown ends, otherwise the alarm rings again. Tags don't count here.
 */
@Composable
fun WakeCheckScreen(deadline: Long, glitch: Boolean, onConfirm: () -> Unit) {
    var left by remember { mutableStateOf(0L) }
    LaunchedEffect(deadline) {
        while (true) {
            left = ((deadline - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            delay(250)
        }
    }
    val burst by rememberGlitchBursts(glitch)
    CyberBackground {
        if (glitch) GlitchField(burst)
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.glitchSlices(burst.intensity, burst.seed)) {
                GlitchText(stringResource(R.string.check_title), MaterialTheme.typography.headlineMedium, animated = glitch)
            }
            Text("$left s", style = MaterialTheme.typography.displayLarge, color = if (left <= 10) Neon.Red else Neon.Yellow)
            Text(
                stringResource(R.string.check_text),
                style = MaterialTheme.typography.bodyLarge, color = Neon.Text, textAlign = TextAlign.Center,
            )
            NeonButton(stringResource(R.string.check_confirm), Modifier.fillMaxWidth(), color = Neon.Green, filled = true, onClick = onConfirm)
        }
    }
}
