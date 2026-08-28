package io.github.jmallus.guidage.core

import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.core.map.RoadSurface
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfacesTest {

    /** Une position à [east] mètres à l'est et [north] au nord du départ. */
    private fun at(east: Double, north: Double) = GeoPoint(
        lat = north / Geo.METERS_PER_DEGREE_LATITUDE,
        lng = east / Geo.METERS_PER_DEGREE_LONGITUDE,
    )

    /** Le tracé : deux kilomètres plein nord. */
    private fun path(meters: Double = 2_000.0, step: Double = 20.0): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        var travelled = 0.0
        while (travelled <= meters) {
            points += at(0.0, travelled)
            travelled += step
        }
        return points
    }

    /**
     * Une voie parallèle au tracé, décalée de [offset] mètres, entre deux distances.
     *
     * Le décalage est ce que le fond de carte et le tracé ont d'écart en pratique : la trace
     * enregistrée passe rarement sur la ligne médiane de la voie.
     */
    private fun way(
        fromNorth: Double,
        toNorth: Double,
        kind: RoadKind,
        surface: RoadSurface = RoadSurface.UNKNOWN,
        offset: Double = 5.0,
        step: Double = 50.0,
    ): RoadSegment {
        val lats = mutableListOf<Int>()
        val lngs = mutableListOf<Int>()
        var north = fromNorth
        while (north <= toNorth) {
            val point = at(offset, north)
            lats += (point.lat * 1_000_000).roundToInt()
            lngs += (point.lng * 1_000_000).roundToInt()
            north += step
        }
        return RoadSegment(kind, surface, lats.toIntArray(), lngs.toIntArray())
    }

    @Test
    fun `sans fond de carte on ne se prononce pas`() {
        val runs = Surfaces.ahead(path(), emptyList(), distanceAlongRoute = 0.0, lookahead = 1_000.0)

        assertEquals(1, runs.size)
        assertEquals(SurfaceClass.UNKNOWN, runs.single().surface)
    }

    @Test
    fun `la bascule du bitume au chemin est vue`() {
        val segments = listOf(
            way(0.0, 1_000.0, RoadKind.SECONDARY),
            way(1_000.0, 2_000.0, RoadKind.TRACK),
        )

        val runs = Surfaces.ahead(path(), segments, distanceAlongRoute = 0.0, lookahead = 2_000.0)

        assertEquals("portions : ${runs.map { it.surface }}", 2, runs.size)
        assertEquals(SurfaceClass.ROAD, runs[0].surface)
        assertEquals(SurfaceClass.TRAIL, runs[1].surface)
        assertEquals("la bascule est mal située : ${runs[1].fromDistance}", 1_000.0, runs[1].fromDistance, 80.0)
    }

    @Test
    fun `une route non revetue vaut un chemin`() {
        val segments = listOf(way(0.0, 2_000.0, RoadKind.UNCLASSIFIED, RoadSurface.UNPAVED))

        val runs = Surfaces.ahead(path(), segments, distanceAlongRoute = 0.0, lookahead = 2_000.0)

        assertEquals(SurfaceClass.TRAIL, runs.single().surface)
    }

    @Test
    fun `la voie verte garde son nom`() {
        val segments = listOf(way(0.0, 2_000.0, RoadKind.CYCLEWAY))

        assertEquals(SurfaceClass.CYCLEWAY, Surfaces.ahead(path(), segments, 0.0, 2_000.0).single().surface)
    }

    @Test
    fun `un carrefour ne fait pas un changement de revetement`() {
        // Un chemin qui coupe la route : sans regroupement, il ferait apparaître cinquante
        // mètres de chemin au milieu d'une départementale.
        val segments = listOf(
            way(0.0, 2_000.0, RoadKind.SECONDARY),
            way(950.0, 1_000.0, RoadKind.TRACK, offset = 2.0, step = 10.0),
        )

        val runs = Surfaces.ahead(path(), segments, distanceAlongRoute = 0.0, lookahead = 2_000.0)

        assertTrue("le carrefour a fait ${runs.size} portions", runs.size <= 2)
        assertEquals(SurfaceClass.ROAD, runs.first().surface)
        assertTrue("la route ne fait que ${runs.first().length} m", runs.first().length > 800.0)
    }

    @Test
    fun `une voie trop loin n est pas celle qu on suit`() {
        // Cinquante mètres : c'est la parallèle de l'autre côté du champ, pas notre route.
        val segments = listOf(way(0.0, 2_000.0, RoadKind.SECONDARY, offset = 50.0))

        val runs = Surfaces.ahead(path(), segments, distanceAlongRoute = 0.0, lookahead = 1_000.0)

        assertEquals(SurfaceClass.UNKNOWN, runs.single().surface)
    }

    @Test
    fun `les nœuds espaces ne font pas perdre la voie`() {
        // Deux cents mètres entre deux nœuds : sans densification, le point le plus proche
        // du tracé se trouverait à cent mètres, hors tolérance.
        val segments = listOf(way(0.0, 2_000.0, RoadKind.SECONDARY, step = 200.0))

        val runs = Surfaces.ahead(path(), segments, distanceAlongRoute = 0.0, lookahead = 1_000.0)

        assertEquals(SurfaceClass.ROAD, runs.single().surface)
    }

    @Test
    fun `le calcul part de la position et non du depart`() {
        val segments = listOf(
            way(0.0, 1_000.0, RoadKind.SECONDARY),
            way(1_000.0, 2_000.0, RoadKind.TRACK),
        )

        val runs = Surfaces.ahead(path(), segments, distanceAlongRoute = 1_200.0, lookahead = 800.0)

        assertEquals(SurfaceClass.TRAIL, runs.single().surface)
        assertTrue("la portion commence à ${runs.single().fromDistance}", runs.single().fromDistance >= 1_200.0)
    }

    @Test
    fun `les surfaces du fond ne sont pas des voies`() {
        // Un bois dont le contour longe le tracé ne fait pas rouler dans le bois.
        val segments = listOf(way(0.0, 2_000.0, RoadKind.FOREST))

        assertEquals(
            SurfaceClass.UNKNOWN,
            Surfaces.ahead(path(), segments, 0.0, 1_000.0).single().surface,
        )
    }

    @Test
    fun `un trace trop court ne dit rien`() {
        assertEquals(emptyList<SurfaceRun>(), Surfaces.ahead(emptyList(), emptyList(), 0.0, 1_000.0))
        assertEquals(emptyList<SurfaceRun>(), Surfaces.ahead(path(), emptyList(), 0.0, 0.0))
    }
}
