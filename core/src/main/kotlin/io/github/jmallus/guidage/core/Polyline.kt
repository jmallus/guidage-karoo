package io.github.jmallus.guidage.core

import kotlin.math.pow

/**
 * Décodeur de polylignes encodées Google.
 *
 * Karoo fournit :
 *  - le tracé de l'itinéraire en précision 5 (lat, lng)
 *  - le profil altimétrique en précision 1, chaque point valant (distance en m, altitude en m)
 */
object Polyline {

    /**
     * Décode [encoded] en une liste de couples (première valeur, seconde valeur).
     *
     * @param precision nombre de décimales utilisé à l'encodage (5 pour lat/lng, 1 pour le profil).
     */
    fun decode(encoded: String, precision: Int): List<Pair<Double, Double>> {
        if (encoded.isEmpty()) return emptyList()

        val factor = 10.0.pow(precision)
        val result = ArrayList<Pair<Double, Double>>(encoded.length / 4 + 1)
        var index = 0
        var first = 0
        var second = 0

        while (index < encoded.length) {
            val deltaFirst = decodeValue(encoded, index) ?: break
            index = deltaFirst.nextIndex
            val deltaSecond = decodeValue(encoded, index) ?: break
            index = deltaSecond.nextIndex

            first += deltaFirst.value
            second += deltaSecond.value
            result.add(Pair(first / factor, second / factor))
        }
        return result
    }

    private class Decoded(val value: Int, val nextIndex: Int)

    private fun decodeValue(encoded: String, startIndex: Int): Decoded? {
        var index = startIndex
        var shift = 0
        var result = 0
        var byte: Int
        do {
            if (index >= encoded.length) return null
            byte = encoded[index++].code - 63
            result = result or ((byte and 0x1f) shl shift)
            shift += 5
        } while (byte >= 0x20)
        val value = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return Decoded(value, index)
    }
}
