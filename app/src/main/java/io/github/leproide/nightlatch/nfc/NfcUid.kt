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

package io.github.leproide.nightlatch.nfc

/**
 * Tag identity = hardware UID only. Nothing is read from or written to the tag
 * memory, so any NFC tag/sticker/card with a stable UID can be paired.
 */
object NfcUid {
    /** 4..10 bytes covers ISO 14443 (4/7/10), ISO 15693 and FeliCa (8). */
    private const val MIN_BYTES = 4
    private const val MAX_BYTES = 10

    fun fromBytes(id: ByteArray?): String =
        id?.joinToString("") { "%02X".format(it.toInt() and 0xFF) } ?: ""

    /** Keeps hex digits only, upper-case. "04:a2-3b" -> "04A23B". */
    fun normalise(raw: String): String =
        raw.take(64).filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.uppercase()

    fun isValid(uid: String): Boolean =
        uid.length % 2 == 0 && uid.length / 2 in MIN_BYTES..MAX_BYTES &&
            uid.all { it in '0'..'9' || it in 'A'..'F' }

    /**
     * ISO 14443-3 random UID: 4 bytes starting with 0x08. Bank cards, passports and
     * phones emulating cards use it; it changes at every read, so it cannot be paired.
     */
    fun isRandom(uid: String): Boolean = uid.length == 8 && uid.startsWith("08")

    /** "04A23B11" -> "04:A2:3B:11" for display. */
    fun pretty(uid: String): String = uid.chunked(2).joinToString(":")
}
