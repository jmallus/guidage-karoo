package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapZoomTest {

    @Test
    fun `les quatre portees se suivent en boucle`() {
        assertEquals(MapZoom.NEAR, MapZoom.CLOSE.next())
        assertEquals(MapZoom.MIDDLE, MapZoom.NEAR.next())
        assertEquals(MapZoom.FAR, MapZoom.MIDDLE.next())
        assertEquals(MapZoom.CLOSE, MapZoom.FAR.next())
    }

    /**
     * Le cran d'usine n'est pas le plus court, et c'est voulu.
     *
     * Cent cinquante mètres sert à se situer dans une agglomération, non à rouler : ouvrir
     * dessus obligerait à trois appuis pour retrouver la portée du reste du temps.
     */
    @Test
    fun `le cran d'usine est celui de trois cents metres`() {
        assertEquals(MapZoom.CLOSE, MapZoom.fromOrdinal(0))
        assertEquals(MapZoom.NEAR, MapZoom.DEFAULT)
        assertEquals(150.0, MapZoom.CLOSE.rangeMeters, 1e-9)
        assertEquals(300.0, MapZoom.NEAR.rangeMeters, 1e-9)
        assertEquals(500.0, MapZoom.MIDDLE.rangeMeters, 1e-9)
        assertEquals(1_000.0, MapZoom.FAR.rangeMeters, 1e-9)
        // Un réglage enregistré par une version qui en aurait davantage ne doit pas planter.
        assertEquals(MapZoom.NEAR, MapZoom.fromOrdinal(7))
        assertEquals(MapZoom.NEAR, MapZoom.fromOrdinal(-1))
    }

    @Test
    fun `les portees vont croissant`() {
        val portees = MapZoom.entries.map { it.rangeMeters }
        assertEquals("les crans ne sont pas rangés du plus court au plus long", portees.sorted(), portees)
        assertTrue("deux crans partagent une portée", portees.toSet().size == portees.size)
    }

    @Test
    fun `les chevrons portent plus loin que le cadre, mais pas proportionnellement`() {
        MapZoom.entries.forEach { zoom ->
            assertTrue(
                "à ${zoom.rangeMeters} m les chevrons doivent dépasser le cadre",
                zoom.chevronMeters > zoom.rangeMeters * 0.5,
            )
        }
        // Le cadre décuple entre le premier et le dernier cran, la longueur de chevrons non :
        // c'est une durée de route, pas une fraction de l'écran.
        assertEquals(450.0, MapZoom.NEAR.chevronMeters, 1e-9)
        assertEquals(1_300.0, MapZoom.FAR.chevronMeters, 1e-9)
        assertTrue(MapZoom.FAR.chevronMeters < MapZoom.FAR.rangeMeters * 2)
    }
}

class GraphZoomTest {

    @Test
    fun `les quatre portees se suivent en boucle`() {
        assertEquals(GraphZoom.AHEAD_10KM, GraphZoom.AHEAD_5KM.next())
        assertEquals(GraphZoom.AHEAD_20KM, GraphZoom.AHEAD_10KM.next())
        assertEquals(GraphZoom.AHEAD_50KM, GraphZoom.AHEAD_20KM.next())
        assertEquals(GraphZoom.AHEAD_5KM, GraphZoom.AHEAD_50KM.next())
    }

    @Test
    fun `les portees vont croissant et valent des kilometres ronds`() {
        val portees = GraphZoom.entries.map { it.lookaheadMeters }

        assertEquals(listOf(5_000.0, 10_000.0, 20_000.0, 50_000.0), portees)
        assertEquals("les crans ne sont pas rangés du plus court au plus long", portees.sorted(), portees)
    }

    /**
     * Le bandeau ouvre sur dix kilomètres : l'heure qui vient.
     *
     * Aucun cran ne montre plus le parcours entier. C'est ce que fait le champ « Profil à
     * venir », à l'échelle comprimée qui est faite pour ça — un bandeau qui l'affichait aussi
     * écrasait les côtes contre le fond de la fenêtre sans qu'on s'en rende compte.
     */
    @Test
    fun `le cran d'usine est celui de dix kilometres`() {
        assertEquals(GraphZoom.AHEAD_10KM, GraphZoom.DEFAULT)
        assertEquals(GraphZoom.AHEAD_10KM, GraphZoom.fromOrdinal(9))
        assertEquals(GraphZoom.AHEAD_10KM, GraphZoom.fromOrdinal(-1))
        assertTrue("un cran montre encore tout le parcours", GraphZoom.entries.all { it.lookaheadMeters > 0.0 })
    }
}
