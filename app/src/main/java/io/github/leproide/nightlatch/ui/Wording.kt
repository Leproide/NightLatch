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

import androidx.annotation.StringRes
import io.github.leproide.nightlatch.R
import io.github.leproide.nightlatch.data.MathWording

/** All UI texts that name the math unlock, for one cosmetic [MathWording]. */
class Words(
    @StringRes val name: Int,
    @StringRes val section: Int,
    @StringRes val toggle: Int,
    @StringRes val count: Int,
    @StringRes val forced: Int,
    @StringRes val ring: Int,
    @StringRes val progress: Int,
    @StringRes val wrong: Int,
    @StringRes val tagsEmpty: Int,
    @StringRes val nfcMissing: Int,
)

fun MathWording.words(): Words = when (this) {
    MathWording.MATH -> Words(
        R.string.w_name_math, R.string.w_math_section, R.string.w_math_toggle, R.string.w_math_count,
        R.string.w_math_forced, R.string.w_math_ring, R.string.w_math_progress, R.string.w_math_wrong,
        R.string.w_math_tags_empty, R.string.w_math_nfc_missing,
    )
    MathWording.CIPHER -> Words(
        R.string.w_name_cipher, R.string.w_cipher_section, R.string.w_cipher_toggle, R.string.w_cipher_count,
        R.string.w_cipher_forced, R.string.w_cipher_ring, R.string.w_cipher_progress, R.string.w_cipher_wrong,
        R.string.w_cipher_tags_empty, R.string.w_cipher_nfc_missing,
    )
    MathWording.OVERRIDE -> Words(
        R.string.w_name_override, R.string.w_override_section, R.string.w_override_toggle, R.string.w_override_count,
        R.string.w_override_forced, R.string.w_override_ring, R.string.w_override_progress, R.string.w_override_wrong,
        R.string.w_override_tags_empty, R.string.w_override_nfc_missing,
    )
    MathWording.OPERATIONS -> Words(
        R.string.w_name_operations, R.string.w_operations_section, R.string.w_operations_toggle, R.string.w_operations_count,
        R.string.w_operations_forced, R.string.w_operations_ring, R.string.w_operations_progress, R.string.w_operations_wrong,
        R.string.w_operations_tags_empty, R.string.w_operations_nfc_missing,
    )
}
