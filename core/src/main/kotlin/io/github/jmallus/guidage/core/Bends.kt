package io.github.jmallus.guidage.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Un virage de l'itinéraire, tel qu'on le lit sur le tracé. */
data class RouteBend(
    /** Distance du virage depuis le départ de l'itinéraire (m). */
    val distanceAlongRoute: Double,
    /** Rayon de courbure au plus serré (m). Plus il est petit, plus il faut ralentir. */
    val radius: Double,
    /** Sens du virage : -1 à gauche, +1 à droite. */
    val direction: Int,
    /** Angle total balayé (degrés), qui distingue une épingle d'une longue courbe. */
    val sweepDegrees: Double,
)

/** Un virage vu depuis la position courante. */
data class BendStatus(
    val bend: RouteBend,
    /** Distance qui reste jusqu'à lui (m). */
    val distance: Double,
)

/**
 * Lit les virages d'un tracé.
 *
 * En descente, le profil altimétrique ne dit rien : la pente est connue, elle est négative,
 * et ce n'est pas elle qui décide de la vitesse. Ce qui décide, c'est la géométrie — et
 * elle est déjà là, dans la polyligne que le Karoo fournit.
 *
 * Tout le travail est dans le bruit. Un point GPS vaut à quelques mètres près, et l'angle
 * entre deux points voisins de dix mètres est presque entièrement du bruit : calculé cru, il
 * ferait voir des virages sur une ligne droite. On rééchantillonne donc le tracé à pas
 * constant, puis on mesure la rotation du cap sur cette longueur-là.
 *
 * Le choix de ce pas est le seul réglage qui compte, et il se paie des deux côtés. Trop
 * court, le bruit domine l'angle mesuré. Trop long, un virage plus court que le pas devient
 * invisible faute d'échantillons — un angle droit de vingt-cinq mètres de rayon tient dans
 * quarante mètres de bitume, et un pas de vingt mètres n'en voyait rien. Quinze mètres
 * laissent deux mètres d'erreur latérale peser moins de huit degrés, et suffisent à voir un
 * virage de quarante mètres. Le prix en est qu'au-delà de quatre-vingt-dix mètres de rayon,
 * la rotation mesurée retombe sous le bruit : ces courbes-là ne sont pas annoncées, ce qui
 * tombe bien, elles se prennent sans lever les mains du guidon.
 */
object Bends {

    /** Pas de rééchantillonnage (m), et base sur laquelle la rotation se mesure. */
    const val STEP_METERS = 15.0

    /**
     * Rayon au-delà duquel un virage n'en est plus un (m).
     *
     * Quatre-vingt-dix mètres se prennent sans lever les mains du guidon, et c'est aussi là
     * que la mesure s'arrête : sur quinze mètres de base, une telle courbe tourne de neuf
     * degrés et demi, à peine plus que ce qu'un point GPS mal placé produirait.
     */
    const val MAX_RADIUS_METERS = 90.0

    /** Rotation minimale pour qu'un virage compte (degrés). */
    const val MIN_SWEEP_DEGREES = 25.0

    /**
     * Les virages du tracé, dans l'ordre du parcours.
     *
     * @param path le tracé, tel que l'itinéraire le porte.
     */
    fun of(
        path: List<GeoPoint>,
        stepMeters: Double = STEP_METERS,
        maxRadius: Double = MAX_RADIUS_METERS,
        minSweep: Double = MIN_SWEEP_DEGREES,
    ): List<RouteBend> {
        val samples = resample(path, stepMeters)
        if (samples.size < 3) return emptyList()

        val bends = mutableListOf<RouteBend>()
        var index = 1
        while (index < samples.size - 1) {
            val turn = turnDegrees(samples, index)
            if (abs(turn) < ROTATION_FLOOR_DEGREES) {
                index++
                continue
            }

            // Un virage n'est pas un point mais une suite de pas qui tournent du même côté.
            val sign = if (turn > 0) 1 else -1
            var sweep = 0.0
            var sharpest = 0.0
            var sharpestIndex = index
            var cursor = index
            while (cursor < samples.size - 1) {
                val step = turnDegrees(samples, cursor)
                if (abs(step) < ROTATION_FLOOR_DEGREES || (if (step > 0) 1 else -1) != sign) break
                sweep += abs(step)
                if (abs(step) > sharpest) {
                    sharpest = abs(step)
                    sharpestIndex = cursor
                }
                cursor++
            }

            if (sweep >= minSweep && sharpest > 0.0) {
                // Rayon au plus serré : la longueur d'un pas divisée par l'angle qu'il balaie.
                val radius = stepMeters / Math.toRadians(sharpest)
                if (radius <= maxRadius) {
                    bends += RouteBend(
                        distanceAlongRoute = samples[sharpestIndex].distance,
                        radius = radius,
                        direction = sign,
                        sweepDegrees = sweep,
                    )
                }
            }
            index = maxOf(cursor, index + 1)
        }
        return bends
    }

    /** Les virages devant le coureur, jusqu'à [lookahead] mètres. */
    fun ahead(
        bends: List<RouteBend>,
        distanceAlongRoute: Double,
        lookahead: Double,
    ): List<BendStatus> = bends
        .asSequence()
        .filter { it.distanceAlongRoute > distanceAlongRoute }
        .filter { it.distanceAlongRoute - distanceAlongRoute <= lookahead }
        .map { BendStatus(it, it.distanceAlongRoute - distanceAlongRoute) }
        .toList()

    /** Le plus serré d'une liste, celui dont la distance vaut d'être écrite. */
    fun sharpest(bends: List<BendStatus>): BendStatus? = bends.minByOrNull { it.bend.radius }

    /** Un point du tracé rééchantillonné, avec sa distance depuis le départ. */
    private data class Sample(val x: Double, val y: Double, val distance: Double)

    /**
     * Rééchantillonne le tracé à pas constant, dans un repère métrique local.
     *
     * L'origine est le premier point : sur un itinéraire de cent kilomètres, la projection
     * équirectangulaire s'écarte de quelques mètres aux extrémités, ce qui ne change rien à
     * un angle mesuré sur vingt.
     */
    private fun resample(path: List<GeoPoint>, stepMeters: Double): List<Sample> {
        if (path.size < 2 || stepMeters <= 0.0) return emptyList()
        val origin = path.first()
        val plane = path.map { Geo.project(origin, it) }

        val samples = mutableListOf(Sample(plane.first().x, plane.first().y, 0.0))
        var travelled = 0.0
        var nextSampleAt = stepMeters
        for (index in 1 until plane.size) {
            val from = plane[index - 1]
            val to = plane[index]
            val segment = hypot(to.x - from.x, to.y - from.y)
            if (segment <= 0.0) continue

            while (nextSampleAt <= travelled + segment) {
                val ratio = (nextSampleAt - travelled) / segment
                samples += Sample(
                    x = from.x + (to.x - from.x) * ratio,
                    y = from.y + (to.y - from.y) * ratio,
                    distance = nextSampleAt,
                )
                nextSampleAt += stepMeters
            }
            travelled += segment
        }
        return samples
    }

    /**
     * Rotation du cap au point [index], en degrés. Positif à droite, négatif à gauche.
     */
    private fun turnDegrees(samples: List<Sample>, index: Int): Double {
        val previous = samples[index - 1]
        val current = samples[index]
        val next = samples[index + 1]
        val firstHeading = atan2(current.x - previous.x, current.y - previous.y)
        val secondHeading = atan2(next.x - current.x, next.y - current.y)
        var delta = Math.toDegrees(secondHeading - firstHeading)
        while (delta > 180.0) delta -= 360.0
        while (delta < -180.0) delta += 360.0
        return delta
    }

    /**
     * Rotation en deçà de laquelle on tient le pas pour droit.
     *
     * C'est le seuil de bruit : sur quinze mètres, deux mètres d'écart latéral — l'ordinaire
     * d'un point GPS mal placé — font sept degrés et demi. Neuf laisse la marge.
     */
    private const val ROTATION_FLOOR_DEGREES = 9.0
}
