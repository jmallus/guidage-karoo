package io.github.jmallus.guidage.core.map

/**
 * Découpe d'un contour par un rectangle, par l'algorithme de Sutherland et Hodgman.
 *
 * Une surface ne se coupe pas comme une ligne. Une ligne trop longue se scinde en morceaux
 * qui, mis bout à bout, redonnent la ligne ; un bois scindé en morceaux ne se remplit plus.
 * Il faut donc, pour chaque cellule de l'index, le contour du bois **limité à cette
 * cellule** — sans quoi il faudrait ranger le bois entier dans chacune des cellules qu'il
 * touche, et un bois de dix kilomètres serait recopié cent fois.
 *
 * Les morceaux voisins partagent exactement leur bord, et les remplissages se rejoignent
 * sans laisser de couture.
 */
object ClipPolygon {

    /**
     * Contour de [latitudes]/[longitudes] limité à [bounds].
     *
     * Renvoie null quand il ne reste rien, ou moins de trois points — un contour de deux
     * points n'a pas de surface.
     */
    fun clip(latitudes: IntArray, longitudes: IntArray, bounds: MapBounds): Pair<IntArray, IntArray>? {
        var points = latitudes.indices.map { latitudes[it].toDouble() to longitudes[it].toDouble() }
        if (points.size < 3) return null

        // Un demi-plan à la fois : sud, nord, ouest, est. Après les quatre passes, il ne
        // reste que ce qui est dans le rectangle.
        points = halfPlane(points) { (lat, _) -> lat >= bounds.minLatitude }
            .let { pts -> halfPlane(pts) { (lat, _) -> lat <= bounds.maxLatitude } }
            .let { pts -> halfPlane(pts) { (_, lng) -> lng >= bounds.minLongitude } }
            .let { pts -> halfPlane(pts) { (_, lng) -> lng <= bounds.maxLongitude } }
        if (points.size < 3) return null

        val clippedLatitudes = IntArray(points.size) { Math.round(points[it].first).toInt() }
        val clippedLongitudes = IntArray(points.size) { Math.round(points[it].second).toInt() }
        return clippedLatitudes to clippedLongitudes
    }

    /**
     * Garde la part du contour qui satisfait [inside], en ajoutant les points d'entrée et de
     * sortie sur la frontière.
     */
    private fun halfPlane(
        points: List<Pair<Double, Double>>,
        inside: (Pair<Double, Double>) -> Boolean,
    ): List<Pair<Double, Double>> {
        if (points.isEmpty()) return points
        val out = mutableListOf<Pair<Double, Double>>()
        var previous = points.last()
        var previousInside = inside(previous)
        for (current in points) {
            val currentInside = inside(current)
            if (currentInside != previousInside) out += crossing(previous, current, inside)
            if (currentInside) out += current
            previous = current
            previousInside = currentInside
        }
        return out
    }

    /**
     * Point où le segment [from]–[to] traverse la frontière, trouvé par dichotomie.
     *
     * La frontière n'est connue que par le prédicat ; vingt bissections suffisent à la
     * placer au millionième de degré près, soit onze centimètres, la précision même du
     * fichier.
     */
    private fun crossing(
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        inside: (Pair<Double, Double>) -> Boolean,
    ): Pair<Double, Double> {
        var low = 0.0
        var high = 1.0
        val fromInside = inside(from)
        repeat(BISECTIONS) {
            val middle = (low + high) / 2
            val point = from.first + (to.first - from.first) * middle to
                from.second + (to.second - from.second) * middle
            if (inside(point) == fromInside) low = middle else high = middle
        }
        val ratio = (low + high) / 2
        return from.first + (to.first - from.first) * ratio to
            from.second + (to.second - from.second) * ratio
    }

    private const val BISECTIONS = 24
}
