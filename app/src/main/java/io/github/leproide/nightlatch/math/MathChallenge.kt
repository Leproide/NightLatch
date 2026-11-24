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

package io.github.leproide.nightlatch.math

import io.github.leproide.nightlatch.data.MathDifficulty
import io.github.leproide.nightlatch.data.MathOp
import kotlin.random.Random

/** One arithmetic question. Answers are always positive integers. */
data class MathProblem(val text: String, val answer: Int)

/**
 * Problem generator. The user picks WHICH operations are used ([MathOp] mask, shared by
 * all levels); the level only changes the LENGTH of the expression and the SIZE of the
 * numbers. Normal precedence applies (× and ÷ before + and −), every division is exact,
 * the running total never goes below 1.
 *
 *  EASY   1 operation, small numbers:         13 + 20 · 12 − 6 · 4 × 3 · 20 ÷ 5
 *  NORMAL 1-2 operations, two-digit numbers:  31 + 39 − 28 · 3 × 5 × 9 · 36 ÷ 3 ÷ 4
 *  HARD   2-3 operations, chains:             12 × 6 ÷ 2 + 3 · 45 + 10 × 10 · 63 ÷ 3 ÷ 7
 */
object MathChallenge {

    private class Level(
        val opsMin: Int, val opsMax: Int,
        val addMin: Int, val addMax: Int,   // plain numbers in + / − terms
        val mulMin: Int, val mulMax: Int,   // factors and start of × terms
        val divMax: Int,                    // divisors 2..divMax
        val startCap: Int,                  // max dividend at the start of a ÷ term
        val valueCap: Int,                  // max intermediate value of a term
        val resultCap: Int,
    )

    private val EASY = Level(1, 1, 2, 20, 2, 6, 5, 30, 60, 60)
    private val NORMAL = Level(1, 2, 5, 50, 2, 10, 9, 60, 150, 200)
    private val HARD = Level(2, 3, 2, 50, 3, 12, 9, 100, 300, 500)

    fun next(difficulty: MathDifficulty, ops: Int = MathOp.ALL, rnd: Random = Random.Default): MathProblem {
        val level = when (difficulty) {
            MathDifficulty.EASY -> EASY
            MathDifficulty.NORMAL -> NORMAL
            MathDifficulty.HARD -> HARD
        }
        val pool = MathOp.fromMask(ops).ifEmpty { MathOp.entries.toSet() }.toList()
        repeat(300) {
            val list = List(rnd.nextInt(level.opsMin, level.opsMax + 1)) { pool[rnd.nextInt(pool.size)] }
            build(list, level, rnd)?.let { return it }
        }
        // Practically unreachable (0 failures in 30k simulated runs); keep a safe answer.
        val a = rnd.nextInt(10, 40); val b = rnd.nextInt(10, 40)
        return MathProblem("$a + $b", a + b)
    }

    private fun build(ops: List<MathOp>, lv: Level, rnd: Random): MathProblem? {
        // Split into multiplicative terms joined by + / −.
        val terms = mutableListOf(mutableListOf<MathOp>())
        val joins = mutableListOf<MathOp>()
        for (op in ops) {
            if (op == MathOp.ADD || op == MathOp.SUB) { joins += op; terms += mutableListOf<MathOp>() }
            else terms.last() += op
        }

        val texts = ArrayList<String>(terms.size)
        val values = ArrayList<Int>(terms.size)
        for (t in terms) {
            if (t.isEmpty()) {
                val v = rnd.nextInt(lv.addMin, lv.addMax + 1)
                texts += "$v"; values += v
                continue
            }
            // Divisors first, then a start value containing all of them as factors:
            // multiplications only add factors, so every division stays exact.
            val divisors = t.filter { it == MathOp.DIV }.map { rnd.nextInt(2, lv.divMax + 1) }
            val prod = divisors.fold(1) { acc, d -> acc * d }
            if (prod * 2 > lv.startCap) return null
            val start = if (divisors.isEmpty()) rnd.nextInt(lv.mulMin, lv.mulMax + 1)
                        else prod * rnd.nextInt(2, lv.startCap / prod + 1)
            var v = start
            val sb = StringBuilder("$start")
            var di = 0
            for (op in t) {
                if (op == MathOp.MUL) {
                    val m = rnd.nextInt(lv.mulMin, lv.mulMax + 1); v *= m; sb.append(" × ").append(m)
                } else {
                    val d = divisors[di++]; v /= d; sb.append(" ÷ ").append(d)
                }
                if (v > lv.valueCap) return null
            }
            texts += sb.toString(); values += v
        }

        var total = values[0]
        val text = StringBuilder(texts[0])
        for (i in joins.indices) {
            total = if (joins[i] == MathOp.ADD) total + values[i + 1] else total - values[i + 1]
            if (total < 1) return null
            text.append(' ').append(joins[i].symbol).append(' ').append(texts[i + 1])
        }
        if (total > lv.resultCap) return null
        return MathProblem(text.toString(), total)
    }

    /** Parses user input strictly: digits only, max 6 chars, no sign. */
    fun parse(input: String): Int? =
        input.trim().takeIf { it.isNotEmpty() && it.length <= 6 && it.all(Char::isDigit) }?.toIntOrNull()
}
