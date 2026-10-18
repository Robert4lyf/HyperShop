package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.model.Rational

enum class Kind { NUM, WORD, COMMA, SEMI, COLON, LPAREN, RPAREN, DASH, SLASH, PERIOD, OTHER }

/** [start] and [end] index into the source line; [value] is set for NUM. */
data class Token(
    val kind: Kind,
    val text: String,
    val start: Int,
    val end: Int,
    val value: Rational? = null,
)

/**
 * Splits an ingredient line into tokens in one left-to-right pass, without
 * regular expressions, so a hostile 10 KB line costs linear time (PLAN §3.1).
 *
 * Numbers: integers, decimals (`1.5`, and `1,5` when one or two digits follow
 * the comma and no digit after that), slash fractions (`1/2`, also `1 / 2`
 * when both sides are digits), unicode vulgar fractions, and a digit run glued
 * to a unicode fraction (`1½`). Letters glued to digits split (`400g` → 400, g).
 * Words keep inner apostrophes and hyphens (`confectioners'`, `extra-virgin`).
 */
object Tokeniser {
    const val MAX_LINE = 500

    private val vulgar =
        mapOf(
            '½' to Rational.of(1, 2),
            '⅓' to Rational.of(1, 3),
            '⅔' to Rational.of(2, 3),
            '¼' to Rational.of(1, 4),
            '¾' to Rational.of(3, 4),
            '⅕' to Rational.of(1, 5),
            '⅖' to Rational.of(2, 5),
            '⅗' to Rational.of(3, 5),
            '⅘' to Rational.of(4, 5),
            '⅙' to Rational.of(1, 6),
            '⅚' to Rational.of(5, 6),
            '⅛' to Rational.of(1, 8),
            '⅜' to Rational.of(3, 8),
            '⅝' to Rational.of(5, 8),
            '⅞' to Rational.of(7, 8),
        )

    fun tokenise(line: String): List<Token> {
        val s = line
        val out = ArrayList<Token>()
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c in vulgar -> i = number(s, i, out)
                c.isLetter() -> i = word(s, i, out)
                else -> {
                    val kind =
                        when (c) {
                            ',' -> Kind.COMMA
                            ';' -> Kind.SEMI
                            ':' -> Kind.COLON
                            '(', '[' -> Kind.LPAREN
                            ')', ']' -> Kind.RPAREN
                            '-', '–', '—', '‒' -> Kind.DASH
                            '/', '⁄' -> Kind.SLASH
                            '.' -> Kind.PERIOD
                            else -> Kind.OTHER
                        }
                    out += Token(kind, c.toString(), i, i + 1)
                    i++
                }
            }
        }
        return out
    }

    private fun digits(
        s: String,
        from: Int,
    ): Int {
        var j = from
        while (j < s.length && s[j].isDigit()) j++
        return j
    }

    private fun isGroup(
        s: String,
        at: Int,
    ): Boolean = at + 3 <= s.length && (at until at + 3).all { s[it].isDigit() } && (at + 3 == s.length || !s[at + 3].isDigit())

    private fun number(
        s: String,
        start: Int,
        out: MutableList<Token>,
    ): Int {
        if (s[start] in vulgar) {
            out += Token(Kind.NUM, s[start].toString(), start, start + 1, vulgar.getValue(s[start]))
            return start + 1
        }
        var j = digits(s, start)
        // Cap absurd digit runs: they are not quantities, and Long would overflow.
        if (j - start > 9) {
            out += Token(Kind.OTHER, s.substring(start, j), start, j)
            return j
        }
        var value = Rational.of(s.substring(start, j).toLong())
        // Thousands: 1,000 (a comma then exactly three digits).
        var groups = 0
        while (groups < 2 && j < s.length && s[j] == ',' && isGroup(s, j + 1)) {
            groups++
            value = value * Rational.of(1000) + Rational.of(s.substring(j + 1, j + 4).toLong())
            j += 4
        }
        // The cap again, now that the groups are in: "999999999,999,999.9999" would overflow below.
        if (j - start - groups > 9) {
            out += Token(Kind.OTHER, s.substring(start, j), start, j)
            return j
        }
        // Decimal: 1.5, or 1,5 (one or two digits, then no more digits).
        if (j + 1 < s.length && (s[j] == '.' || s[j] == ',') && s[j + 1].isDigit()) {
            val k = digits(s, j + 1)
            val frac = k - (j + 1)
            val isComma = s[j] == ','
            if (frac in 1..4 && (!isComma || frac <= 2)) {
                var den = 1L
                repeat(frac) { den *= 10 }
                value = value + Rational.of(s.substring(j + 1, k).toLong(), den)
                j = k
            }
        } else if (j < s.length && (s[j] == '/' || s[j] == '⁄')) {
            // 1/2, also 1 / 2.
            var k = j + 1
            while (k < s.length && s[k] == ' ') k++
            val e = digits(s, k)
            if (e > k && e - k <= 6) {
                val den = s.substring(k, e).toLong()
                if (den != 0L) {
                    value = Rational.of(value.num, den)
                    j = e
                }
            }
        } else if (j < s.length && s[j] in vulgar) {
            value = value + vulgar.getValue(s[j])
            j++
        }
        // "1 / 2" written with a space before the slash.
        if (j + 2 < s.length && s[j] == ' ' && (s[j + 1] == '/' || s[j + 1] == '⁄') && value.isWhole) {
            var k = j + 2
            while (k < s.length && s[k] == ' ') k++
            val e = digits(s, k)
            if (e > k && e - k <= 6) {
                val den = s.substring(k, e).toLong()
                if (den != 0L) {
                    value = Rational.of(value.num, den)
                    j = e
                }
            }
        }
        out += Token(Kind.NUM, s.substring(start, j), start, j, value)
        return j
    }

    private fun word(
        s: String,
        start: Int,
        out: MutableList<Token>,
    ): Int {
        var j = start + 1
        while (j < s.length) {
            val c = s[j]
            val inner = (c == '\'' || c == '’' || c == '-') && j + 1 < s.length && s[j + 1].isLetter()
            val trailingApostrophe = (c == '\'' || c == '’') && (j + 1 == s.length || !s[j + 1].isLetterOrDigit())
            if (c.isLetter() || inner) {
                j++
            } else if (trailingApostrophe && s[j - 1] == 's') {
                j++ // confectioners'
                break
            } else {
                break
            }
        }
        out += Token(Kind.WORD, s.substring(start, j), start, j)
        return j
    }
}
