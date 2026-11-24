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

import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.leproide.nightlatch.R
import io.github.leproide.nightlatch.alarm.AlarmScheduler
import io.github.leproide.nightlatch.data.Alarm
import io.github.leproide.nightlatch.data.Store
import io.github.leproide.nightlatch.ui.theme.GlitchOut
import io.github.leproide.nightlatch.ui.theme.Neon
import io.github.leproide.nightlatch.ui.theme.NeonButton
import io.github.leproide.nightlatch.ui.theme.NeonPanel
import io.github.leproide.nightlatch.ui.theme.SectionLabel
import java.time.Duration
import java.time.ZonedDateTime

private val DAY_LABELS = intArrayOf(
    R.string.day_mon, R.string.day_tue, R.string.day_wed, R.string.day_thu,
    R.string.day_fri, R.string.day_sat, R.string.day_sun,
)

fun formatTime(ctx: Context, hour: Int, minute: Int): String =
    if (DateFormat.is24HourFormat(ctx)) "%02d:%02d".format(hour, minute)
    else "%d:%02d %s".format(if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "AM" else "PM")

/** Saves + arms the alarm and tells the user if arming failed. */
private fun commit(ctx: Context, alarm: Alarm) {
    val stored = Store.upsertAlarm(ctx, alarm)
    if (stored == null) {
        Toast.makeText(ctx, ctx.getString(R.string.alarm_limit, Store.MAX_ALARMS), Toast.LENGTH_LONG).show()
        return
    }
    if (!AlarmScheduler.apply(ctx, stored)) {
        Toast.makeText(ctx, R.string.schedule_failed, Toast.LENGTH_LONG).show()
    }
}

@Composable
fun AlarmsScreen() {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val rev = Store.revision.intValue // recompose on store changes
    val alarms = Store.alarms(ctx)
    var editing by remember { mutableStateOf<Alarm?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        val next = AlarmScheduler.soonest(ctx)
        NeonPanel(accent = if (next != null) Neon.Green else Neon.Dim) {
            Text(
                if (next != null) stringResource(R.string.next_alarm, untilText(next.second)) else stringResource(R.string.no_alarm),
                style = MaterialTheme.typography.titleMedium,
                color = if (next != null) Neon.Green else Neon.Dim,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.tab_alarms), modifier = Modifier.weight(1f))
            NeonButton(stringResource(R.string.add_alarm), color = Neon.Magenta) {
                // New alarms start with no repeat days: the user picks them each time.
                editing = Alarm(0, 7, 0, 0, true, "")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (alarms.isEmpty()) {
            Text(stringResource(R.string.alarms_empty), color = Neon.Dim, style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(alarms, key = { it.id }) { a ->
                SwipeToDelete(
                    onDelete = { AlarmScheduler.cancel(ctx, a.id); Store.deleteAlarm(ctx, a.id) },
                ) {
                    AlarmRow(
                        alarm = a,
                        onToggle = { commit(ctx, a.copy(enabled = it)) },
                        onClick = { editing = a },
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    editing?.let { a ->
        AlarmEditDialog(
            initial = a,
            onDismiss = { editing = null },
            onSave = { commit(ctx, it); editing = null },
            onDelete = if (a.id > 0) {
                { AlarmScheduler.cancel(ctx, a.id); Store.deleteAlarm(ctx, a.id); editing = null }
            } else null,
        )
    }
}

/**
 * Swipe left to delete, with our own drag handling so the rule is exact:
 * the row is deleted only if it is released after being dragged past HALF of its width.
 * Released earlier it springs back to its place.
 * On delete the row springs back and plays [GlitchOut] in place, then calls [onDelete].
 *
 * The drag offset is a plain state updated synchronously by the drag callback (no coroutine
 * per delta): a late drag update can therefore never cancel the spring-back animation.
 */
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    var deleting by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(false) } // currently past the half-way point
    var offsetX by remember { mutableFloatStateOf(0f) }
    var springJob by remember { mutableStateOf<Job?>(null) }
    val progress = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Springy return to 0 from wherever the row is.
    fun springBack() {
        springJob?.cancel()
        springJob = scope.launch {
            animate(
                initialValue = offsetX,
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) { value, _ -> offsetX = value }
        }
    }

    val dragState = rememberDraggableState { delta ->
        if (deleting || width == 0) return@rememberDraggableState
        // Left only: offset goes from 0 to -width.
        offsetX = (offsetX + delta).coerceIn(-width.toFloat(), 0f)
        val nowArmed = -offsetX > width / 2f
        if (nowArmed != armed) {
            armed = nowArmed
            // Tick when crossing the threshold, both ways, so the user feels it.
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    LaunchedEffect(deleting) {
        if (!deleting) return@LaunchedEffect
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        springBack()
        progress.animateTo(1f, tween(durationMillis = 520, easing = LinearEasing))
        onDelete()
    }

    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        // Red "delete" layer, revealed behind the row while dragging.
        if (offsetX < 0f) {
            val shape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)
            Box(
                Modifier
                    .matchParentSize()
                    .background(Neon.Red.copy(alpha = if (armed) 0.45f else 0.15f), shape)
                    .border(1.dp, Neon.Red.copy(alpha = if (armed) 1f else 0.5f), shape)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    "◀ ${stringResource(R.string.delete)} //",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (armed) Neon.Text else Neon.Red,
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = !deleting,
                    // Grabbing the row again stops a running spring so the finger takes over.
                    onDragStarted = { springJob?.cancel() },
                    onDragStopped = {
                        // Decision on release only: past half -> delete, otherwise spring back.
                        if (width > 0 && -offsetX > width / 2f) {
                            deleting = true
                        } else {
                            armed = false
                            springBack()
                        }
                    },
                ),
        ) {
            GlitchOut(progress.value) { content() }
        }
    }
}

@Composable
private fun untilText(at: ZonedDateTime): String {
    val d = Duration.between(ZonedDateTime.now(), at).coerceAtLeast(Duration.ZERO)
    val h = d.toHours()
    val m = d.toMinutes() % 60
    return if (h >= 24) "${h / 24}d ${h % 24}h" else "${h}h ${m}m"
}

@Composable
private fun AlarmRow(alarm: Alarm, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val ctx = LocalContext.current
    NeonPanel(accent = if (alarm.enabled) Neon.Cyan else Neon.Dim, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatTime(ctx, alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.displayMedium,
                    color = if (alarm.enabled) Neon.Text else Neon.Dim,
                )
                Text(daysText(alarm), style = MaterialTheme.typography.bodySmall, color = Neon.Magenta)
                if (alarm.label.isNotEmpty()) {
                    Text(alarm.label, style = MaterialTheme.typography.bodyMedium, color = Neon.Dim)
                }
                Store.tagName(ctx, alarm.tagUid)?.let {
                    Text("◉ $it", style = MaterialTheme.typography.bodySmall, color = Neon.Cyan, maxLines = 1)
                }
            }
            Switch(
                checked = alarm.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Neon.Void, checkedTrackColor = Neon.Cyan,
                    uncheckedThumbColor = Neon.Dim, uncheckedTrackColor = Neon.Panel,
                ),
            )
        }
    }
}

@Composable
private fun daysText(alarm: Alarm): String {
    if (!alarm.isRepeating) return stringResource(R.string.repeat_once)
    if (alarm.days == Alarm.ALL_DAYS) return stringResource(R.string.repeat_daily)
    return DAY_LABELS.indices.filter { alarm.days and (1 shl it) != 0 }
        .map { stringResource(DAY_LABELS[it]) }
        .joinToString(" ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditDialog(
    initial: Alarm,
    onDismiss: () -> Unit,
    onSave: (Alarm) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val time = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(ctx))
    var days by remember { mutableIntStateOf(initial.days) }
    var label by remember { mutableStateOf(initial.label) }
    val tags = Store.tags(ctx)
    // A tag that is no longer paired shows as "any tag".
    var tagUid by remember { mutableStateOf(initial.tagUid?.takeIf { u -> tags.any { it.uid == u } }) }

    // Custom dialog body so the three buttons can share the full width (AlertDialog's
    // button slots wrap the labels on narrow screens).
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(Neon.Panel, CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp))
                .border(1.dp, Neon.Cyan, CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                stringResource(if (initial.id > 0) R.string.edit_alarm else R.string.add_alarm),
                style = MaterialTheme.typography.titleLarge, color = Neon.Cyan,
            )
            Spacer(Modifier.height(12.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(
                    state = time,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Neon.Void,
                        selectorColor = Neon.Magenta,
                        timeSelectorSelectedContainerColor = Neon.Cyan.copy(alpha = 0.2f),
                        timeSelectorSelectedContentColor = Neon.Cyan,
                        timeSelectorUnselectedContainerColor = Neon.Void,
                        timeSelectorUnselectedContentColor = Neon.Dim,
                    ),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DAY_LABELS.forEachIndexed { i, res ->
                        val on = days and (1 shl i) != 0
                        Box(
                            Modifier
                                .size(36.dp)
                                .background(if (on) Neon.Magenta else Neon.Void)
                                .border(1.dp, if (on) Neon.Magenta else Neon.Dim)
                                .clickable { days = days xor (1 shl i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(res).take(2),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (on) Neon.Void else Neon.Dim,
                            )
                        }
                    }
                }
                Text(
                    if (days == 0) stringResource(R.string.repeat_once_hint) else "",
                    style = MaterialTheme.typography.bodySmall, color = Neon.Dim,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(Alarm.MAX_LABEL_LENGTH) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                // Optional: only one specific tag stops this alarm.
                if (tags.isNotEmpty()) {
                    Text(
                        stringResource(R.string.alarm_tag),
                        style = MaterialTheme.typography.labelMedium, color = Neon.Dim,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                    )
                    val options = listOf<String?>(null) + tags.map { it.uid }
                    options.forEach { uid ->
                        val on = tagUid == uid
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .background(if (on) Neon.Magenta.copy(alpha = 0.2f) else Neon.Void)
                                .border(1.dp, if (on) Neon.Magenta else Neon.Dim)
                                .clickable { tagUid = uid }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                (if (on) "◉ " else "○ ") +
                                    (uid?.let { u -> tags.first { it.uid == u }.name } ?: stringResource(R.string.alarm_tag_any)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (on) Neon.Magenta else Neon.Text,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    NeonButton(stringResource(R.string.delete), Modifier.weight(1f), color = Neon.Red, compact = true, onClick = onDelete)
                }
                NeonButton(stringResource(R.string.cancel), Modifier.weight(1f), color = Neon.Dim, compact = true, onClick = onDismiss)
                NeonButton(stringResource(R.string.save), Modifier.weight(1f), filled = true, compact = true) {
                    onSave(initial.copy(hour = time.hour, minute = time.minute, days = days, label = label, enabled = true, tagUid = tagUid))
                }
            }
        }
    }
}
