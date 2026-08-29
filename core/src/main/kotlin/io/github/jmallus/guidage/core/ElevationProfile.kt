package io.github.jmallus.guidage.core

/** Un point du profil : distance depuis le départ de l'itinéraire (m) et altitude (m). */
data class ProfilePoint(val distance: Double, val elevation: Double)

/**
 * Profil altimétrique d'un itinéraire, trié par distance croissante.
 */
class ElevationProfile(points: List<ProfilePoint>) {

    val points: List<ProfilePoint> = points.sortedBy { it.distance }

    val isEmpty: Boolean get() = points.isEmpty()

    /** Distance totale couverte par le profil (m). */
    val totalDistance: Double get() = points.lastOrNull()?.distance ?: 0.0

    val minElevation: Double get() = points.minOfOrNull { it.elevation } ?: 0.0

    val maxElevation: Double get() = points.maxOfOrNull { it.elevation } ?: 0.0

    /**
     * Altitude interpolée linéairement à [distance], ou null si le profil est vide.
     * Les distances hors bornes sont ramenées aux extrémités.
     */
    fun elevationAt(distance: Double): Double? {
        if (points.isEmpty()) return null
        if (points.size == 1) return points.first().elevation
        if (distance <= points.first().distance) return points.first().elevation
        if (distance >= points.last().distance) return points.last().elevation

        val index = upperBound(distance)
        val before = points[index - 1]
        val after = points[index]
        val span = after.distance - before.distance
        if (span <= 0.0) return after.elevation
        val ratio = (distance - before.distance) / span
        return before.elevation + ratio * (after.elevation - before.elevation)
    }

    /**
     * Extrait la portion de profil comprise entre [from] et [to] (m), bornes interpolées incluses.
     */
    fun slice(from: Double, to: Double): List<ProfilePoint> {
        if (points.isEmpty() || to <= from) return emptyList()
        val start = from.coerceAtLeast(points.first().distance)
        val end = to.coerceAtMost(points.last().distance)
        if (end <= start) return emptyList()

        val result = ArrayList<ProfilePoint>()
        elevationAt(start)?.let { result.add(ProfilePoint(start, it)) }
        points.forEach { point ->
            if (point.distance > start && point.distance < end) result.add(point)
        }
        elevationAt(end)?.let { result.add(ProfilePoint(end, it)) }
        return result
    }

    /** Dénivelé positif cumulé entre [from] et [to] (m). */
    fun ascentBetween(from: Double, to: Double): Double {
        val segment = slice(from, to)
        var gain = 0.0
        for (i in 1 until segment.size) {
            val delta = segment[i].elevation - segment[i - 1].elevation
            if (delta > 0) gain += delta
        }
        return gain
    }

    /** Pente moyenne (%) entre [from] et [to], null si la distance est nulle. */
    fun gradeBetween(from: Double, to: Double): Double? {
        val span = to - from
        if (span <= 0.0) return null
        val start = elevationAt(from) ?: return null
        val end = elevationAt(to) ?: return null
        return (end - start) / span * 100.0
    }

    private fun upperBound(distance: Double): Int {
        var low = 0
        var high = points.size - 1
        while (low < high) {
            val mid = (low + high) / 2
            if (points[mid].distance < distance) low = mid + 1 else high = mid
        }
        return if (low == 0) 1 else low
    }

    companion object {
        val EMPTY = ElevationProfile(emptyList())

        /**
         * Construit un profil depuis la polyligne d'altitude fournie par Karoo
         * (couples distance/altitude encodés en précision 1).
         */
        fun fromEncoded(encoded: String?): ElevationProfile? {
            if (encoded.isNullOrEmpty()) return null
            val decoded = Polyline.decode(encoded, PRECISION)
            if (decoded.isEmpty()) return null
            return ElevationProfile(decoded.map { ProfilePoint(it.first, it.second) })
        }

        private const val PRECISION = 1
    }
}
