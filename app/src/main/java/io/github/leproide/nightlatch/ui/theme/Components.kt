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

package io.github.leproide.nightlatch.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay
import kotlin.random.Random
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Full-screen backdrop with faint CRT scanlines. */
@Composable
fun CyberBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Neon.Void)) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 4.dp.toPx()
            var y = 0f
            while (y < size.height) {
                drawLine(Color.White.copy(alpha = 0.025f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
        }
        content()
    }
}

/** Panel with a cut corner and a cyan-to-magenta neon edge. */
@Composable
fun NeonPanel(
    modifier: Modifier = Modifier,
    accent: Color = Neon.Cyan,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)
    val base = modifier
        .fillMaxWidth()
        .background(Neon.Panel, shape)
        .border(1.dp, Brush.linearGradient(listOf(accent, Neon.Magenta.copy(alpha = 0.6f))), shape)
    Column(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(16.dp),
        content = content,
    )
}

/**
 * Bracketed button: [ LABEL ]. The label never wraps: it stays on one line and shrinks
 * (down to 9 sp) when the button is narrow. [compact] drops the brackets and uses less
 * padding, for rows of buttons that share the width.
 */
@Composable
fun NeonButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Neon.Cyan,
    enabled: Boolean = true,
    filled: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    val c = if (enabled) color else Neon.Dim
    val base = MaterialTheme.typography.labelLarge
    Box(
        modifier
            .background(if (filled && enabled) c else Color.Transparent, shape)
            .border(1.5.dp, c, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 6.dp else 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = if (compact) text else "[ $text ]",
            style = base.copy(color = if (filled && enabled) Neon.Void else c, textAlign = TextAlign.Center),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = base.fontSize, stepSize = 0.5.sp),
        )
    }
}

/** Small section header: // TITLE ________ */
@Composable
fun SectionLabel(text: String, color: Color = Neon.Magenta, modifier: Modifier = Modifier) {
    Text(
        "// ${text.uppercase()}",
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier.padding(top = 8.dp, bottom = 6.dp),
    )
}

/**
 * Draws the content cut into horizontal slices shifted at random, plus magenta / cyan
 * ghost copies pulled apart (chromatic aberration). [intensity] 0 = untouched;
 * [seed] re-rolls the random layout (change it every frame for a live glitch).
 * Drawing only: layout and touch areas are not moved.
 */
fun Modifier.glitchSlices(intensity: Float, seed: Int): Modifier = drawWithContent {
    if (intensity <= 0f) {
        drawContent()
        return@drawWithContent
    }
    val rnd = Random(seed)
    val amp = 16.dp.toPx() * intensity
    listOf(Neon.Magenta to -1f, Neon.Cyan to 1f).forEach { (tint, dir) ->
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn)
                alpha = 0.55f
            }
            canvas.saveLayer(Rect(Offset.Zero, size), paint)
            translate(left = dir * amp * (0.3f + 0.7f * rnd.nextFloat())) {
                this@drawWithContent.drawContent()
            }
            canvas.restore()
        }
    }
    val slices = 7
    val h = size.height / slices
    for (i in 0 until slices) {
        val shift = if (rnd.nextFloat() < 0.65f) (rnd.nextFloat() * 2f - 1f) * amp else 0f
        clipRect(top = i * h, bottom = (i + 1) * h) {
            translate(left = shift) { this@drawWithContent.drawContent() }
        }
    }
}

/**
 * "Signal lost" exit effect, driven by [progress] 0→1: live [glitchSlices], then in the
 * last 30 % a vertical collapse + fade, like a CRT switching off.
 */
@Composable
fun GlitchOut(progress: Float, content: @Composable () -> Unit) {
    Box(
        Modifier
            .graphicsLayer {
                val tail = ((progress - 0.7f) / 0.3f).coerceIn(0f, 1f)
                scaleY = 1f - 0.97f * tail
                scaleX = 1f + 0.08f * tail
                alpha = 1f - tail
            }
            .glitchSlices(if (progress <= 0f) 0f else 0.5f + progress, (progress * 24).toInt() + 1),
    ) { content() }
}

/** Current glitch burst: [intensity] 0 = calm; [seed] changes every frame of a burst. */
@Immutable
data class GlitchBurst(val intensity: Float = 0f, val seed: Int = 0)

/**
 * Shared glitch clock: calm for 1.2–3.5 s, then a 240–440 ms burst re-rolled every
 * 40 ms, forever. Returns a calm state when [enabled] is false.
 */
@Composable
fun rememberGlitchBursts(enabled: Boolean): State<GlitchBurst> {
    val state = remember { mutableStateOf(GlitchBurst()) }
    LaunchedEffect(enabled) {
        state.value = GlitchBurst()
        if (!enabled) return@LaunchedEffect
        val rnd = Random(System.nanoTime())
        var seed = 1
        while (true) {
            delay(rnd.nextLong(1_200L, 3_500L))
            val frames = rnd.nextInt(6, 12)
            repeat(frames) {
                state.value = GlitchBurst(rnd.nextFloat() * 0.6f + 0.4f, seed++)
                delay(40L)
            }
            state.value = GlitchBurst()
        }
    }
    return state
}

/**
 * Animated backdrop for the ringing screen: a slow scan beam all the time, and during a
 * [burst] random tear bars, noise blocks and a faint colour flash. Draw it BEHIND the
 * content: opaque panels on top (the math panel) are not affected.
 */
@Composable
fun GlitchField(burst: GlitchBurst, modifier: Modifier = Modifier) {
    val beam by rememberInfiniteTransition(label = "beam").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3_200, easing = LinearEasing)), label = "beamY",
    )
    Canvas(modifier.fillMaxSize()) {
        // Scan beam with a short fading tail.
        val y = beam * size.height
        val tail = 60.dp.toPx()
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Neon.Cyan.copy(alpha = 0.10f)), startY = y - tail, endY = y),
            topLeft = Offset(0f, y - tail), size = Size(size.width, tail),
        )
        drawRect(Neon.Cyan.copy(alpha = 0.35f), topLeft = Offset(0f, y), size = Size(size.width, 2.dp.toPx()))

        if (burst.intensity <= 0f) return@Canvas
        val rnd = Random(burst.seed)
        val palette = listOf(Neon.Magenta, Neon.Cyan, Neon.Yellow)
        drawRect(Neon.Magenta.copy(alpha = 0.05f * burst.intensity))
        repeat(rnd.nextInt(3, 7)) {
            val h = (2 + rnd.nextInt(12)).dp.toPx()
            val top = rnd.nextFloat() * size.height
            val left = if (rnd.nextBoolean()) 0f else rnd.nextFloat() * size.width * 0.5f
            drawRect(
                palette[rnd.nextInt(palette.size)].copy(alpha = (0.12f + rnd.nextFloat() * 0.25f) * burst.intensity),
                topLeft = Offset(left, top), size = Size(size.width - left, h),
            )
        }
        repeat(rnd.nextInt(4, 11)) {
            val w = (6 + rnd.nextInt(40)).dp.toPx()
            val h = (3 + rnd.nextInt(10)).dp.toPx()
            drawRect(
                palette[rnd.nextInt(palette.size)].copy(alpha = 0.3f * burst.intensity),
                topLeft = Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height), size = Size(w, h),
            )
        }
    }
}

/**
 * Text with chromatic-aberration glitch: cyan and magenta ghosts that jitter
 * every couple of seconds over a glowing main layer.
 */
@Composable
fun GlitchText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    intense: Boolean = false,
    /** false = static neon text (no jitter, no flicker). */
    animated: Boolean = true,
) {
    if (!animated) {
        Box(modifier) {
            Text(text, style = style, color = Neon.Magenta.copy(alpha = 0.5f), modifier = Modifier.offset(x = (-2).dp))
            Text(text, style = style, color = Neon.Cyan.copy(alpha = 0.5f), modifier = Modifier.offset(x = 2.dp))
            Text(text, style = style.copy(shadow = Shadow(Neon.Cyan, Offset.Zero, blurRadius = 24f)), color = Neon.Text)
        }
        return
    }
    val t = rememberInfiniteTransition(label = "glitch")
    val jitter by t.animateFloat(
        initialValue = 0f, targetValue = 0f, label = "jitter",
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = if (intense) 900 else 2600
                0f at 0
                0f at (if (intense) 600 else 2300)
                5f at (if (intense) 650 else 2350)
                -4f at (if (intense) 720 else 2420)
                2f at (if (intense) 800 else 2500)
                0f at (if (intense) 900 else 2600)
            }
        ),
    )
    val flicker by t.animateFloat(
        initialValue = 0.85f, targetValue = 1f, label = "flicker",
        animationSpec = infiniteRepeatable(tween(140, easing = LinearEasing), RepeatMode.Reverse),
    )
    Box(modifier) {
        Text(text, style = style, color = Neon.Magenta.copy(alpha = 0.7f), modifier = Modifier.offset(x = (-2 + jitter).dp))
        Text(text, style = style, color = Neon.Cyan.copy(alpha = 0.7f), modifier = Modifier.offset(x = (2 - jitter).dp))
        Text(
            text,
            style = style.copy(shadow = Shadow(Neon.Cyan, Offset.Zero, blurRadius = 24f)),
            color = Neon.Text,
            modifier = Modifier.alpha(flicker),
        )
    }
}
