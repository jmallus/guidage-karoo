package io.github.jmallus.guidage.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ClipPolygonTest {

    private val cell = MapBounds(
        minLatitude = 0,
        minLongitude = 0,
        maxLatitude = 100,
        maxLongitude = 100,
    )

    /** Aire du contour, en unités de micro-degrés carrés — suffisant pour comparer. */
    private fun area(points: Pair<IntArray, IntArray>): Double {
        val (latitudes, longitudes) = points
        var sum = 0.0
        for (index in latitudes.indices) {
            val next = (index + 1) % latitudes.size
            sum += longitudes[index].toDouble() * latitudes[next] -
                longitudes[next].toDouble() * latitudes[index]
        }
        return abs(sum) / 2
    }

    @Test
    fun `un contour entierement dedans est rendu tel quel`() {
        val latitudes = intArrayOf(10, 10, 90, 90)
        val longitudes = intArrayOf(10, 90, 90, 10)

        val clipped = ClipPolygon.clip(latitudes, longitudes, cell)

        assertNotNull(clipped)
        assertEquals(6_400.0, area(clipped!!), 1.0)
    }

    @Test
    fun `un contour a cheval est ramene a la cellule`() {
        // Un carré de 0 à 200 : la moitié seulement tombe dans la cellule.
        val latitudes = intArrayOf(0, 0, 200, 200)
        val longitudes = intArrayOf(0, 200, 200, 0)

        val clipped = ClipPolygon.clip(latitudes, longitudes, cell)!!

        assertEquals(10_000.0, area(clipped), 2.0)
        assertTrue(clipped.first.all { it in 0..100 })
        assertTrue(clipped.second.all { it in 0..100 })
    }

    @Test
    fun `un contour hors cellule ne laisse rien`() {
        val latitudes = intArrayOf(500, 500, 600, 600)
        val longitudes = intArrayOf(500, 600, 600, 500)

        assertNull(ClipPolygon.clip(latitudes, longitudes, cell))
    }

    @Test
    fun `les morceaux de deux cellules voisines refont le contour`() {
        val latitudes = intArrayOf(20, 20, 80, 80)
        val longitudes = intArrayOf(20, 180, 180, 20)
        val right = MapBounds(minLatitude = 0, minLongitude = 100, maxLatitude = 100, maxLongitude = 200)

        val left = ClipPolygon.clip(latitudes, longitudes, cell)!!
        val other = ClipPolygon.clip(latitudes, longitudes, right)!!

        // 60 × 160 au total, réparti sans recouvrement ni trou entre les deux cellules.
        assertEquals(9_600.0, area(left) + area(other), 2.0)
    }

    @Test
    fun `un contour de deux points n a pas de surface`() {
        assertNull(ClipPolygon.clip(intArrayOf(10, 20), intArrayOf(10, 20), cell))
    }
}
