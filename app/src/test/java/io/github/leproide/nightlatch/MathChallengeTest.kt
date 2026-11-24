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

package io.github.leproide.nightlatch

import io.github.leproide.nightlatch.data.MathDifficulty
import io.github.leproide.nightlatch.data.MathOp
import io.github.leproide.nightlatch.math.MathChallenge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MathChallengeTest {

    /** Independent evaluator: × and ÷ before + and −, left to right; fails on inexact ÷. */
    private fun eval(text: String): Int {
        val tokens = text.split(" ")
        val terms = mutableListOf<Int>()
        val signs = mutableListOf(1)
        var cur = tokens[0].toInt()
        var i = 1
        while (i < tokens.size) {
            val op = tokens[i]
            val n = tokens[i + 1].toInt()
            when (op) {
                "×" -> cur *= n
                "÷" -> { assertEquals("inexact division in $text", 0, cur % n); cur /= n }
                "+", "−" -> { terms += cur; signs += if (op == "+") 1 else -1; cur = n }
                else -> error("unknown op $op in $text")
            }
            i += 2
        }
        terms += cur
        return terms.indices.sumOf { signs[it] * terms[it] }
    }

    private fun opsIn(text: String): Set<String> =
        text.split(" ").filterIndexed { idx, _ -> idx % 2 == 1 }.toSet()

    @Test
    fun answersAreCorrectPositiveAndUseOnlyEnabledOps() {
        val rnd = Random(42)
        for (difficulty in MathDifficulty.entries) {
            for (mask in 1..MathOp.ALL) {
                val allowed = MathOp.fromMask(mask).map { it.symbol }.toSet()
                repeat(300) {
                    val p = MathChallenge.next(difficulty, mask, rnd)
                    assertEquals(p.text, eval(p.text), p.answer)
                    assertTrue("non-positive answer: ${p.text}", p.answer >= 1)
                    val used = opsIn(p.text)
                    // The fallback is a plain addition; everything else must respect the mask.
                    if (!(used == setOf("+") && "+" !in allowed)) {
                        assertTrue("$used not in $allowed for ${p.text}", allowed.containsAll(used))
                    }
                }
            }
        }
    }

    @Test
    fun lengthGrowsWithDifficulty() {
        val rnd = Random(7)
        fun opCount(d: MathDifficulty) = List(500) { opsIn(MathChallenge.next(d, MathOp.ALL, rnd).text).size }
        assertTrue(opCount(MathDifficulty.EASY).all { it == 1 })
        assertTrue(opCount(MathDifficulty.NORMAL).all { it in 1..2 })
        // HARD always chains at least 2 operators (counted with repeats).
        repeat(500) {
            val t = MathChallenge.next(MathDifficulty.HARD, MathOp.ALL, rnd).text
            assertTrue(t, t.split(" ").size >= 5)
        }
    }

    @Test
    fun parseIsStrict() {
        assertEquals(42, MathChallenge.parse(" 42 "))
        assertEquals(null, MathChallenge.parse("-3"))
        assertEquals(null, MathChallenge.parse("1e3"))
        assertEquals(null, MathChallenge.parse("1234567"))
        assertEquals(null, MathChallenge.parse(""))
    }
}
