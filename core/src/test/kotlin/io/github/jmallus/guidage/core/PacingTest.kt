package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingTest {

    /** Un profil à quatre segments : plat, côte à 10 %, descente douce, plat. */
    private val profile = ElevationProfile(
        listOf(
            ProfilePoint(0.0, 100.0),
            ProfilePoint(1_000.0, 100.0),
            ProfilePoint(2_000.0, 200.0),
            ProfilePoint(3_000.0, 180.0),
            ProfilePoint(4_000.0, 180.0),
        ),
    )

    private fun learner(
        value: Double,
        grade: Double,
        seconds: Double,
        step: Double = 2.0,
        into: PaceLearner = PaceLearner(),
    ): PaceLearner {
        var elapsed = 0.0
        while (elapsed < seconds) {
            into.observe(step, value, grade)
            elapsed += step
        }
        return into
    }

    // --- Ce que demande le terrain ----------------------------------------------------------

    @Test
    fun `le terrain se decoupe en montee et en roulant`() {
        val terrain = Pacing.terrain(profile, from = 0.0, remainingDistance = 4_000.0)

        assertEquals(100.0, terrain.ascent, 1e-9)
        assertEquals(1_000.0, terrain.climbDistance, 1e-9)
        assertEquals(3_000.0, terrain.easyDistance, 1e-9)
        assertEquals(4_000.0, terrain.totalDistance, 1e-9)
    }

    @Test
    fun `le decoupage part de la position et non du depart`() {
        val terrain = Pacing.terrain(profile, from = 500.0, remainingDistance = 2_000.0)

        assertEquals(100.0, terrain.ascent, 1e-9)
        assertEquals(1_000.0, terrain.climbDistance, 1e-9)
        assertEquals(1_000.0, terrain.easyDistance, 1e-9)
    }

    @Test
    fun `ce qui depasse le profil est compte roulant`() {
        // Le profil s'arrête à 4 km ; il reste 3 km depuis le troisième.
        val terrain = Pacing.terrain(profile, from = 3_000.0, remainingDistance = 3_000.0)

        assertEquals(0.0, terrain.ascent, 1e-9)
        assertEquals(0.0, terrain.climbDistance, 1e-9)
        assertEquals(3_000.0, terrain.easyDistance, 1e-9)
    }

    @Test
    fun `sans profil tout est roulant`() {
        val terrain = Pacing.terrain(null, from = 0.0, remainingDistance = 12_000.0)

        assertEquals(0.0, terrain.ascent, 1e-9)
        assertEquals(12_000.0, terrain.easyDistance, 1e-9)
    }

    // --- Ce que le coureur apprend ----------------------------------------------------------

    @Test
    fun `une allure constante est apprise telle quelle`() {
        val pace = learner(value = 8.0, grade = 0.0, seconds = 600.0).pace

        assertEquals(8.0, pace.flatSpeed!!, 1e-9)
        assertEquals(0.0, pace.flatSpread, 1e-9)
        assertEquals(600.0, pace.flatSeconds, 1e-9)
        assertNull(pace.climbRate)
    }

    @Test
    fun `en cote c est la vitesse ascensionnelle qui se mesure`() {
        // 2,5 m/s à 8 % : deux cents millimètres d'altitude par seconde, soit 720 m/h.
        val pace = learner(value = 2.5, grade = 8.0, seconds = 300.0).pace

        assertEquals(0.2, pace.climbRate!!, 1e-9)
        assertEquals(300.0, pace.climbSeconds, 1e-9)
        assertNull(pace.flatSpeed)
    }

    @Test
    fun `une allure trop peu observee n est pas rendue`() {
        val pace = learner(value = 8.0, grade = 0.0, seconds = 120.0).pace

        assertNull(pace.flatSpeed)
        assertEquals(120.0, pace.flatSeconds, 1e-9)
    }

    @Test
    fun `l arret n apprend rien`() {
        val learner = PaceLearner()
        repeat(300) { learner.observe(2.0, speedMetersPerSecond = 0.0, gradePercent = 0.0) }

        assertEquals(0.0, learner.pace.flatSeconds, 1e-9)
        assertNull(learner.pace.flatSpeed)
    }

    @Test
    fun `une reprise apres une longue pause est ecartee`() {
        val learner = PaceLearner()
        // Le champ n'est plus abonné pendant un quart d'heure : le relevé suivant porte un
        // intervalle qui ne décrit rien de ce qui s'est passé entre-temps.
        learner.observe(deltaSeconds = 900.0, speedMetersPerSecond = 8.0, gradePercent = 0.0)

        assertEquals(0.0, learner.pace.flatSeconds, 1e-9)
    }

    @Test
    fun `une allure irreguliere se voit a sa dispersion`() {
        val learner = PaceLearner()
        var elapsed = 0.0
        var fast = true
        while (elapsed < 2_400.0) {
            learner.observe(2.0, if (fast) 8.0 else 6.0, 0.0)
            fast = !fast
            elapsed += 2.0
        }
        val pace = learner.pace

        assertTrue("moyenne entre les deux vitesses", pace.flatSpeed!! in 6.8..7.3)
        assertTrue("dispersion non nulle", pace.flatSpread in 0.10..0.20)
    }

    @Test
    fun `le retour au depart oublie tout`() {
        val learner = learner(value = 8.0, grade = 0.0, seconds = 600.0)
        learner.reset()

        assertNull(learner.pace.flatSpeed)
        assertEquals(0.0, learner.pace.flatSeconds, 1e-9)
    }

    // --- Ce qu'on en déduit -----------------------------------------------------------------

    @Test
    fun `l arrivee separe le temps de plat et celui de montee`() {
        val pace = LearnedPace(flatSpeed = 8.0, climbRate = 0.2)
        val terrain = RemainingTerrain(ascent = 500.0, climbDistance = 5_000.0, easyDistance = 20_000.0)

        val estimate = Pacing.arrival(pace, terrain)!!

        // 20 km à 8 m/s, plus 500 m de dénivelé à 0,2 m/s : deux fois 2 500 secondes.
        assertEquals(5_000.0, estimate.seconds, 1e-9)
        // Sans dispersion mesurée, la marge tombe sur son plancher.
        assertEquals(5_000.0 * Pacing.MINIMUM_SPREAD, estimate.marginSeconds, 1e-9)
    }

    @Test
    fun `la distance des cotes n est pas comptee deux fois`() {
        val pace = LearnedPace(flatSpeed = 8.0, climbRate = 0.2)
        val avecCote = RemainingTerrain(ascent = 500.0, climbDistance = 5_000.0, easyDistance = 20_000.0)
        val memeDistanceSansCote = RemainingTerrain(ascent = 0.0, climbDistance = 0.0, easyDistance = 25_000.0)

        // 25 km de plat font 3 125 s ; les mêmes 25 km dont 500 m de D+ en font 5 000. Si la
        // distance des côtes entrait dans les deux postes, on trouverait 5 625.
        assertEquals(3_125.0, Pacing.arrival(pace, memeDistanceSansCote)!!.seconds, 1e-9)
        assertEquals(5_000.0, Pacing.arrival(pace, avecCote)!!.seconds, 1e-9)
    }

    @Test
    fun `une bosse negligeable se roule a l allure du plat`() {
        val pace = LearnedPace(flatSpeed = 8.0)
        val terrain = RemainingTerrain(ascent = 20.0, climbDistance = 400.0, easyDistance = 7_600.0)

        // Vingt mètres de dénivelé ne valent pas qu'on réclame une vitesse ascensionnelle :
        // les 8 km s'estiment au plat, et l'absence de climbRate n'empêche rien.
        assertEquals(1_000.0, Pacing.arrival(pace, terrain)!!.seconds, 1e-9)
    }

    @Test
    fun `la marge suit la dispersion des deux allures`() {
        val pace = LearnedPace(
            flatSpeed = 8.0,
            climbRate = 0.2,
            flatSpread = 0.10,
            climbSpread = 0.20,
        )
        val terrain = RemainingTerrain(ascent = 500.0, climbDistance = 5_000.0, easyDistance = 20_000.0)

        val estimate = Pacing.arrival(pace, terrain)!!

        // Moitié du temps au plat, moitié en côte : la marge est la moyenne des deux.
        assertEquals(750.0, estimate.marginSeconds, 1e-9)
    }

    @Test
    fun `la marge se resserre a mesure qu on approche`() {
        val pace = LearnedPace(flatSpeed = 8.0, flatSpread = 0.12)
        val loin = Pacing.arrival(pace, RemainingTerrain(0.0, 0.0, 40_000.0))!!
        val pres = Pacing.arrival(pace, RemainingTerrain(0.0, 0.0, 2_000.0))!!

        assertTrue(pres.marginSeconds < loin.marginSeconds)
    }

    @Test
    fun `sans allure connue on ne dit rien`() {
        val terrain = RemainingTerrain(ascent = 0.0, climbDistance = 0.0, easyDistance = 10_000.0)

        assertNull(Pacing.arrival(LearnedPace.UNKNOWN, terrain))
    }

    @Test
    fun `sans vitesse ascensionnelle une cote reste sans reponse`() {
        // Le plat est connu, la côte non : plutôt que d'appliquer l'un à l'autre, on rend la
        // main — c'est exactement l'erreur que le champ existe pour corriger.
        val pace = LearnedPace(flatSpeed = 8.0)
        val terrain = RemainingTerrain(ascent = 600.0, climbDistance = 5_000.0, easyDistance = 5_000.0)

        assertNull(Pacing.arrival(pace, terrain))
    }

    @Test
    fun `a l arrivee il n y a plus rien a estimer`() {
        val pace = LearnedPace(flatSpeed = 8.0, climbRate = 0.2)

        assertNull(Pacing.arrival(pace, RemainingTerrain.NONE))
    }

    @Test
    fun `le terrain et l allure s enchainent`() {
        val pace = learner(value = 8.0, grade = 0.0, seconds = 600.0)
            .also { learner(value = 2.5, grade = 8.0, seconds = 300.0, into = it) }
            .pace
        val terrain = Pacing.terrain(profile, from = 0.0, remainingDistance = 4_000.0)

        val estimate = Pacing.arrival(pace, terrain)!!

        assertNotNull(pace.flatSpeed)
        // 3 km roulants à 8 m/s, plus 100 m de D+ à 0,2 m/s.
        assertEquals(3_000.0 / 8.0 + 100.0 / 0.2, estimate.seconds, 1e-9)
    }
}
