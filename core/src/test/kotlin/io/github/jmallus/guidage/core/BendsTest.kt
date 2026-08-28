package io.github.jmallus.guidage.core

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BendsTest {

    /** Un point du tracé, en mètres est et nord depuis le départ. */
    private data class Metres(val east: Double, val north: Double)

    /**
     * Les positions correspondantes.
     *
     * À l'équateur la projection garde la même échelle sur les deux axes, ce qui rend les
     * attentes lisibles : quarante mètres de rayon restent quarante.
     */
    private fun positions(path: List<Metres>): List<GeoPoint> = path.map {
        GeoPoint(
            lat = it.north / Geo.METERS_PER_DEGREE_LATITUDE,
            lng = it.east / Geo.METERS_PER_DEGREE_LONGITUDE,
        )
    }

    private fun straight(meters: Double, from: Metres = Metres(0.0, 0.0), step: Double = 5.0): List<Metres> {
        val points = mutableListOf<Metres>()
        var travelled = 0.0
        while (travelled <= meters) {
            points += Metres(from.east, from.north + travelled)
            travelled += step
        }
        return points
    }

    /**
     * Un quart de cercle de rayon [radius], partant vers le nord depuis [from].
     *
     * Le centre est posé à l'est du départ pour un virage à droite, à l'ouest pour un
     * virage à gauche — comme le ferait une route qui tourne.
     */
    private fun quarterTurn(
        radius: Double,
        from: Metres = Metres(0.0, 0.0),
        right: Boolean = true,
        step: Double = 5.0,
    ): List<Metres> {
        val points = mutableListOf<Metres>()
        val sign = if (right) 1.0 else -1.0
        var arc = 0.0
        while (arc <= radius * Math.PI / 2) {
            val angle = arc / radius
            points += Metres(
                east = from.east + sign * radius * (1 - cos(angle)),
                north = from.north + radius * sin(angle),
            )
            arc += step
        }
        return points
    }

    @Test
    fun `une ligne droite n a pas de virage`() {
        assertEquals(emptyList<RouteBend>(), Bends.of(positions(straight(500.0))))
    }

    @Test
    fun `le bruit d un point GPS ne fait pas un virage`() {
        // Un mètre d'écart latéral d'un point à l'autre : c'est l'incertitude ordinaire du
        // GPS, et elle ne doit rien produire. C'est pour elle que le tracé est rééchantillonné.
        val bruit = straight(500.0).mapIndexed { index, p ->
            p.copy(east = if (index % 2 == 0) 1.0 else -1.0)
        }

        assertEquals(emptyList<RouteBend>(), Bends.of(positions(bruit)))
    }

    @Test
    fun `un quart de cercle rend son rayon`() {
        val bends = Bends.of(positions(quarterTurn(radius = 40.0)))

        assertEquals("un seul virage attendu, pas ${bends.size}", 1, bends.size)
        val bend = bends.single()
        assertEquals("le rayon lu vaut ${bend.radius}", 40.0, bend.radius, 8.0)
        assertEquals("le virage n'est pas à droite", 1, bend.direction)
        // L'angle balayé est mesuré au milieu du virage : les échantillons des deux bouts
        // n'ont pas de voisin des deux côtés et ne portent donc pas de rotation. Un quart de
        // cercle en rend un peu moins de la moitié, et c'est attendu.
        assertTrue("l'angle balayé vaut ${bend.sweepDegrees}", bend.sweepDegrees > 30.0)
    }

    @Test
    fun `un angle droit court est vu, et c est pour lui que le pas est court`() {
        // Vingt-cinq mètres de rayon : l'angle droit d'une route de campagne, quarante
        // mètres de bitume en tout. Un pas de mesure de vingt mètres n'en voyait rien.
        val bend = Bends.of(positions(quarterTurn(radius = 25.0))).single()

        assertEquals(25.0, bend.radius, 5.0)
    }

    @Test
    fun `une epingle rend son rayon`() {
        val trace = mutableListOf<Metres>()
        var arc = 0.0
        while (arc <= 12.0 * Math.PI) {
            val angle = arc / 12.0
            trace += Metres(12.0 * (1 - cos(angle)), 12.0 * sin(angle))
            arc += 3.0
        }

        val bend = Bends.of(positions(trace)).single()

        assertEquals(12.0, bend.radius, 3.0)
    }

    @Test
    fun `un virage a gauche se lit a gauche`() {
        val bends = Bends.of(positions(quarterTurn(radius = 40.0, right = false)))

        assertEquals(-1, bends.single().direction)
    }

    @Test
    fun `une courbe large n est pas annoncee`() {
        // Trois cents mètres de rayon se prennent sans lever les mains du guidon : les
        // annoncer noierait les vrais virages dans une liste de courbes.
        assertEquals(emptyList<RouteBend>(), Bends.of(positions(quarterTurn(radius = 300.0))))
    }

    @Test
    fun `le virage se situe la ou il est`() {
        val trace = straight(120.0) + quarterTurn(radius = 40.0, from = Metres(0.0, 120.0))

        val bend = Bends.of(positions(trace)).single()

        assertTrue("le virage est annoncé à ${bend.distanceAlongRoute} m", bend.distanceAlongRoute > 110.0)
        assertTrue("le virage est annoncé à ${bend.distanceAlongRoute} m", bend.distanceAlongRoute < 200.0)
    }

    @Test
    fun `les virages devant se comptent depuis la position`() {
        val bends = listOf(
            RouteBend(distanceAlongRoute = 500.0, radius = 20.0, direction = -1, sweepDegrees = 120.0),
            RouteBend(distanceAlongRoute = 1_500.0, radius = 60.0, direction = 1, sweepDegrees = 45.0),
            RouteBend(distanceAlongRoute = 9_000.0, radius = 15.0, direction = -1, sweepDegrees = 160.0),
        )

        val devant = Bends.ahead(bends, distanceAlongRoute = 800.0, lookahead = 3_000.0)

        assertEquals(1, devant.size)
        assertEquals(700.0, devant.single().distance, 1e-9)
    }

    @Test
    fun `le plus serre est celui de plus petit rayon`() {
        val devant = Bends.ahead(
            listOf(
                RouteBend(300.0, radius = 60.0, direction = 1, sweepDegrees = 40.0),
                RouteBend(800.0, radius = 14.0, direction = -1, sweepDegrees = 170.0),
                RouteBend(1_200.0, radius = 45.0, direction = 1, sweepDegrees = 50.0),
            ),
            distanceAlongRoute = 0.0,
            lookahead = 5_000.0,
        )

        assertEquals(800.0, Bends.sharpest(devant)!!.bend.distanceAlongRoute, 1e-9)
        assertNull(Bends.sharpest(emptyList()))
    }

    @Test
    fun `un trace trop court ne dit rien`() {
        assertEquals(
            emptyList<RouteBend>(),
            Bends.of(positions(listOf(Metres(0.0, 0.0), Metres(0.0, 10.0)))),
        )
        assertEquals(emptyList<RouteBend>(), Bends.of(emptyList()))
    }
}
