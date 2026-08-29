package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {

    @Test
    fun `decode reference polyline from Google documentation`() {
        val decoded = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)

        assertEquals(3, decoded.size)
        assertEquals(38.5, decoded[0].first, 1e-5)
        assertEquals(-120.2, decoded[0].second, 1e-5)
        assertEquals(40.7, decoded[1].first, 1e-5)
        assertEquals(-120.95, decoded[1].second, 1e-5)
        assertEquals(43.252, decoded[2].first, 1e-5)
        assertEquals(-126.453, decoded[2].second, 1e-5)
    }

    @Test
    fun `decode empty string`() {
        assertTrue(Polyline.decode("", 5).isEmpty())
    }

    @Test
    fun `round trip on precision 1 pairs`() {
        // Couples (distance, altitude) tels que fournis par Karoo pour le profil.
        val expected = listOf(
            Pair(0.0, 100.0),
            Pair(500.0, 130.5),
            Pair(1000.0, 90.2),
            Pair(2500.4, 340.0),
        )
        val encoded = encode(expected, 1)

        val decoded = Polyline.decode(encoded, 1)

        assertEquals(expected.size, decoded.size)
        expected.forEachIndexed { index, point ->
            assertEquals(point.first, decoded[index].first, 0.11)
            assertEquals(point.second, decoded[index].second, 0.11)
        }
    }

    companion object {
        /** Encodeur de référence, utilisé uniquement par les tests. */
        fun encode(points: List<Pair<Double, Double>>, precision: Int): String {
            val factor = Math.pow(10.0, precision.toDouble())
            val builder = StringBuilder()
            var previousFirst = 0L
            var previousSecond = 0L
            points.forEach { (first, second) ->
                val scaledFirst = Math.round(first * factor)
                val scaledSecond = Math.round(second * factor)
                encodeValue(scaledFirst - previousFirst, builder)
                encodeValue(scaledSecond - previousSecond, builder)
                previousFirst = scaledFirst
                previousSecond = scaledSecond
            }
            return builder.toString()
        }

        private fun encodeValue(value: Long, builder: StringBuilder) {
            var shifted = if (value < 0) (value shl 1).inv() else value shl 1
            while (shifted >= 0x20) {
                builder.append(((0x20 or (shifted and 0x1f).toInt()) + 63).toChar())
                shifted = shifted shr 5
            }
            builder.append((shifted.toInt() + 63).toChar())
        }
    }
}
