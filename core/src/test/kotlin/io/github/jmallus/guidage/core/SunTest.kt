package io.github.jmallus.guidage.core

import java.time.Instant
import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le coucher du soleil, calculé sans rien demander à personne.
 *
 * Les valeurs de référence sont celles des éphémérides ; l'algorithme de la NOAA est donné
 * pour une ou deux minutes, et les contrôles laissent cette marge. Ce n'est pas de
 * l'indulgence : un coureur qui se demande s'il arrive avant la nuit ne joue pas à la minute,
 * et l'incertitude de son heure d'arrivée est dix fois plus large.
 */
class SunTest {

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun minutesApart(a: Long, b: Long) = abs(a - b) / 60_000.0

    /** Caen, un soir de septembre : le soleil se couche vers 18:46 UTC, 20:46 heure d'été. */
    @Test
    fun `le coucher a Caen debut septembre`() {
        val times = Sun.next(GeoPoint(49.18, -0.37), millis("2026-09-01T15:58:00Z"))
        assertNotNull("un coucher est attendu", times)
        assertTrue(
            "coucher à ${Instant.ofEpochMilli(times!!.sunsetMillis)}, attendu vers 18:46 UTC",
            minutesApart(times.sunsetMillis, millis("2026-09-01T18:46:00Z")) <= 5.0,
        )
    }

    /** La nuit civile suit le coucher d'une grosse demi-heure sous nos latitudes. */
    @Test
    fun `la nuit civile suit le coucher`() {
        val times = Sun.next(GeoPoint(49.18, -0.37), millis("2026-09-01T15:58:00Z"))!!
        val ecart = (times.duskMillis!! - times.sunsetMillis) / 60_000.0
        assertTrue("nuit civile $ecart min après le coucher, attendu entre 25 et 45", ecart in 25.0..45.0)
    }

    /** À l'équateur, à l'équinoxe, le soleil se couche vers 18 h locales quelle que soit l'année. */
    @Test
    fun `l'equateur a l'equinoxe`() {
        val times = Sun.next(GeoPoint(0.0, 0.0), millis("2026-03-20T12:00:00Z"))!!
        assertTrue(
            "coucher à ${Instant.ofEpochMilli(times.sunsetMillis)}, attendu vers 18:07 UTC",
            minutesApart(times.sunsetMillis, millis("2026-03-20T18:07:00Z")) <= 8.0,
        )
    }

    /** Une fois le soleil couché, c'est celui du lendemain que l'on veut — jamais un passé. */
    @Test
    fun `apres le coucher on obtient celui du lendemain`() {
        val now = millis("2026-09-01T22:00:00Z")
        val times = Sun.next(GeoPoint(49.18, -0.37), now)!!
        assertTrue("le coucher rendu est dans le passé", times.sunsetMillis > now)
        assertTrue(
            "le coucher rendu est à plus d'un jour",
            times.sunsetMillis - now < 24 * 3_600_000L,
        )
    }

    /** Au Svalbard en juin, le soleil ne se couche pas : rien à annoncer, et surtout pas une heure. */
    @Test
    fun `pas de coucher sous le soleil de minuit`() {
        assertNull(Sun.next(GeoPoint(78.2, 15.6), millis("2026-06-21T12:00:00Z")))
    }

    /** L'ordre des deux instants ne dépend ni du lieu ni de la saison. */
    @Test
    fun `le coucher precede toujours la nuit`() {
        for ((lat, lng, iso) in listOf(
            Triple(49.18, -0.37, "2026-01-15T10:00:00Z"),
            Triple(-33.9, 151.2, "2026-07-01T02:00:00Z"),
            Triple(35.7, 139.7, "2026-11-11T00:00:00Z"),
        )) {
            val times = Sun.next(GeoPoint(lat, lng), millis(iso))!!
            assertTrue("$lat,$lng : nuit avant coucher", times.duskMillis!! > times.sunsetMillis)
        }
    }

    /**
     * À Tampere en juin le soleil se couche, mais ne descend jamais à 6° sous l'horizon : le
     * crépuscule civil dure toute la nuit. (Saint-Pétersbourg, un degré et demi plus au sud,
     * connaît encore une nuit civile de quelques minutes.)
     */
    @Test
    fun `les nuits blanches ont un coucher sans nuit`() {
        val times = Sun.next(GeoPoint(61.50, 23.76), millis("2026-06-21T10:00:00Z"))
        assertNotNull("le soleil se couche bien à 61° nord", times)
        assertNull("la nuit civile ne vient pas", times!!.duskMillis)
    }
}
