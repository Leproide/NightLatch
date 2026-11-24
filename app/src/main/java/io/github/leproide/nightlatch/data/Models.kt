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

package io.github.leproide.nightlatch.data

import java.time.DayOfWeek

/**
 * One alarm.
 *
 * @param days bitmask of repeat days: bit 0 = Monday ... bit 6 = Sunday.
 *             0 means "ring once" (the alarm disables itself after ringing).
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Int,
    val enabled: Boolean,
    val label: String,
    /** Optional: only this paired tag (UID) dismisses the alarm. null = any paired tag. */
    val tagUid: String? = null,
) {
    val isRepeating: Boolean get() = days != 0

    fun repeatsOn(day: DayOfWeek): Boolean = days and (1 shl (day.value - 1)) != 0

    companion object {
        const val ALL_DAYS = 0b1111111
        const val WEEKDAYS = 0b0011111
        const val MAX_LABEL_LENGTH = 32

        /** Collapses whitespace, strips control characters and caps the length. */
        fun sanitiseLabel(raw: String): String =
            raw.filter { !it.isISOControl() }
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_LABEL_LENGTH)
    }
}

/** A paired NFC tag. [uid] is upper-case hex without separators. */
data class PairedTag(
    val uid: String,
    val name: String,
) {
    companion object {
        const val MAX_NAME_LENGTH = 24

        fun sanitiseName(raw: String): String =
            raw.filter { !it.isISOControl() }
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_NAME_LENGTH)
    }
}

enum class MathDifficulty { EASY, NORMAL, HARD }

/** Cosmetic: how the math unlock is named in the UI. Behaviour is identical. */
enum class MathWording { MATH, CIPHER, OVERRIDE, OPERATIONS }

/** Operations the math problems may use (all levels); stored as a bitmask in [Settings.mathOps]. */
enum class MathOp(val symbol: String, val bit: Int) {
    ADD("+", 1), SUB("−", 2), MUL("×", 4), DIV("÷", 8);

    companion object {
        const val ALL = 0b1111
        fun fromMask(mask: Int): Set<MathOp> = entries.filter { mask and it.bit != 0 }.toSet()
    }
}

data class Settings(
    val mathDifficulty: MathDifficulty = MathDifficulty.NORMAL,
    /** How many correct answers are needed to dismiss with math. */
    val mathCount: Int = 3,
    /** If false, math is offered only when no tag can be used (no tags, no NFC). */
    val mathFallbackEnabled: Boolean = true,
    val vibrate: Boolean = true,
    /** Operations used by the math problems, every level (bitmask of [MathOp]); never 0. */
    val mathOps: Int = MathOp.ALL,
    /** Cosmetic name of the math unlock. */
    val mathWording: MathWording = MathWording.MATH,
    /** Glitch effect on the ringing screen (the math panel is never glitched). */
    val ringGlitch: Boolean = true,
    /** Optional: volume rises from very low to full in [rampSeconds] (logarithmic curve). */
    val rampVolume: Boolean = false,
    val rampSeconds: Int = 30,
    /** Optional: "are you awake?" prompt [wakeCheckMinutes] after dismissal; rings again if ignored. */
    val wakeCheck: Boolean = false,
    val wakeCheckMinutes: Int = 5,
    /** While ringing, keep the alarm stream at maximum volume. */
    val lockVolume: Boolean = true,
    /** content:// or android.resource:// ringtone URI; null = system default alarm. */
    val ringtoneUri: String? = null,
) {
    companion object {
        const val MIN_MATH_COUNT = 1
        const val MAX_MATH_COUNT = 10
        val WAKE_CHECK_CHOICES = listOf(3, 5, 10)
        /** Ramp presets: quick / default / gradual. Longer than a minute would just delay waking up. */
        val RAMP_CHOICES = listOf(15, 30, 60)
    }
}
