package com.robert4lyf.hypershop.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * An exact fraction. Quantities are rational so that ⅓ + ⅓ + ⅓ is 1, not
 * 0.9999…, and a tick that "fully covers" an amount really does (PLAN §3.3).
 *
 * Always normalised: the denominator is positive and gcd(num, den) == 1.
 * Serialised as "n/d" (or "n" when whole).
 */
@Serializable(with = RationalSerializer::class)
class Rational private constructor(
    val num: Long,
    val den: Long,
) : Comparable<Rational> {
    operator fun plus(o: Rational): Rational = of(num * o.den + o.num * den, den * o.den)

    operator fun minus(o: Rational): Rational = of(num * o.den - o.num * den, den * o.den)

    operator fun times(o: Rational): Rational = of(num * o.num, den * o.den)

    operator fun div(o: Rational): Rational {
        require(o.num != 0L) { "division by zero" }
        return of(num * o.den, den * o.num)
    }

    operator fun unaryMinus(): Rational = of(-num, den)

    val isZero: Boolean get() = num == 0L
    val isNegative: Boolean get() = num < 0L
    val isWhole: Boolean get() = den == 1L

    fun toDouble(): Double = num.toDouble() / den.toDouble()

    fun max(o: Rational): Rational = if (this >= o) this else o

    fun min(o: Rational): Rational = if (this <= o) this else o

    override fun compareTo(other: Rational): Int = (num * other.den).compareTo(other.num * den)

    override fun equals(other: Any?): Boolean = other is Rational && other.num == num && other.den == den

    override fun hashCode(): Int = 31 * num.hashCode() + den.hashCode()

    override fun toString(): String = if (den == 1L) "$num" else "$num/$den"

    companion object {
        val ZERO = Rational(0, 1)
        val ONE = Rational(1, 1)

        fun of(
            num: Long,
            den: Long = 1,
        ): Rational {
            require(den != 0L) { "zero denominator" }
            if (num == 0L) return ZERO
            val g = gcd(abs(num), abs(den))
            val sign = if (den < 0) -1 else 1
            return Rational(sign * num / g, sign * den / g)
        }

        fun of(n: Int): Rational = of(n.toLong(), 1)

        /**
         * The nearest fraction with a denominator from the cookery set
         * (1, 2, 3, 4, 8, 16, 100, 1000), within 1e-6; else thousandths.
         * Used for decimals from JSON (golden data, Claude) so 0.333 stays 0.333
         * but 0.3333333 becomes ⅓.
         */
        fun fromDouble(d: Double): Rational {
            require(d.isFinite()) { "not finite: $d" }
            for (den in longArrayOf(1, 2, 3, 4, 8, 16, 100, 1000)) {
                val n = (d * den).roundToLong()
                if (abs(n.toDouble() / den - d) < 1e-6) return of(n, den)
            }
            return of((d * 1000).roundToLong(), 1000)
        }

        /** Parses "3", "3/4", "-1/2". */
        fun parse(s: String): Rational {
            val parts = s.trim().split('/')
            return when (parts.size) {
                1 -> of(parts[0].trim().toLong(), 1)
                2 -> of(parts[0].trim().toLong(), parts[1].trim().toLong())
                else -> throw IllegalArgumentException("not a rational: $s")
            }
        }

        private tailrec fun gcd(
            a: Long,
            b: Long,
        ): Long = if (b == 0L) a else gcd(b, a % b)
    }
}

fun Iterable<Rational>.sum(): Rational = fold(Rational.ZERO) { acc, r -> acc + r }

object RationalSerializer : KSerializer<Rational> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Rational", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Rational,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Rational = Rational.parse(decoder.decodeString())
}
