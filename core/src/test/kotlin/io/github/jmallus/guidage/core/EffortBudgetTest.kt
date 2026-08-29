package io.github.jmallus.guidage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffortBudgetTest {

    private val allure = LearnedPace(
        flatSpeed = 8.0,
        climbRate = 0.2,
        flatPower = 200.0,
        climbPower = 280.0,
    )

    @Test
    fun `le plat coute son temps fois sa puissance`() {
        val terrain = RemainingTerrain(ascent = 0.0, climbDistance = 0.0, easyDistance = 20_000.0)

        // 20 km à 8 m/s font 2 500 s ; à 200 W, cinq cents kilojoules.
        assertEquals(500.0, EffortBudget.estimate(allure, terrain)!!.kilojoules, 1e-9)
    }

    @Test
    fun `la montee se paie a son propre tarif`() {
        val terrain = RemainingTerrain(ascent = 500.0, climbDistance = 5_000.0, easyDistance = 20_000.0)

        // 500 m de dénivelé à 0,2 m/s font 2 500 s ; à 280 W, sept cents kilojoules.
        assertEquals(1_200.0, EffortBudget.estimate(allure, terrain)!!.kilojoules, 1e-9)
    }

    @Test
    fun `le detail nomme les cotes dans l ordre ou on les prendra`() {
        val terrain = RemainingTerrain(ascent = 500.0, climbDistance = 5_000.0, easyDistance = 20_000.0)
        val cotes = listOf(
            RemainingClimb(number = 4, ascent = 300.0, grade = 5.1),
            RemainingClimb(number = 5, ascent = 200.0, grade = 9.4),
        )

        val items = EffortBudget.estimate(allure, terrain, cotes)!!.items

        assertEquals(3, items.size)
        assertNull("le premier poste est le roulant", items.first().climbNumber)
        assertEquals(500.0, items[0].kilojoules, 1e-9)
        assertEquals(4, items[1].climbNumber)
        assertEquals(420.0, items[1].kilojoules, 1e-9)
        assertEquals(5, items[2].climbNumber)
        assertEquals(280.0, items[2].kilojoules, 1e-9)
    }

    @Test
    fun `le denivele hors cotes nommees garde son poste`() {
        val terrain = RemainingTerrain(ascent = 500.0, climbDistance = 5_000.0, easyDistance = 20_000.0)
        val cotes = listOf(RemainingClimb(number = 4, ascent = 300.0, grade = 5.1))

        val estimate = EffortBudget.estimate(allure, terrain, cotes)!!

        // Les deux cents mètres de faux plats montants ne font pas une bosse, mais ils
        // coûtent : ils forment un poste sans nom plutôt que de disparaître.
        assertEquals(1_200.0, estimate.kilojoules, 1e-9)
        assertEquals(3, estimate.items.size)
        assertNull(estimate.items.last().climbNumber)
        assertEquals(280.0, estimate.items.last().kilojoules, 1e-9)
    }

    @Test
    fun `sans capteur de puissance rien n est annonce`() {
        val terrain = RemainingTerrain(ascent = 0.0, climbDistance = 0.0, easyDistance = 20_000.0)
        val sansWatts = LearnedPace(flatSpeed = 8.0, climbRate = 0.2)

        assertNull(EffortBudget.estimate(sansWatts, terrain))
    }

    @Test
    fun `sans puissance en cote une cote reste sans reponse`() {
        val terrain = RemainingTerrain(ascent = 600.0, climbDistance = 5_000.0, easyDistance = 5_000.0)
        val platSeulement = LearnedPace(flatSpeed = 8.0, flatPower = 200.0, climbRate = 0.2)

        assertNull(EffortBudget.estimate(platSeulement, terrain))
    }

    @Test
    fun `une bosse negligeable se roule au tarif du plat`() {
        val terrain = RemainingTerrain(ascent = 20.0, climbDistance = 400.0, easyDistance = 7_600.0)
        val platSeulement = LearnedPace(flatSpeed = 8.0, flatPower = 200.0)

        // 8 km à 8 m/s font 1 000 s ; à 200 W, deux cents kilojoules.
        assertEquals(200.0, EffortBudget.estimate(platSeulement, terrain)!!.kilojoules, 1e-9)
    }

    @Test
    fun `a l arrivee il n y a plus rien a payer`() {
        assertNull(EffortBudget.estimate(allure, RemainingTerrain.NONE))
    }

    /* ------------------------------------------------------- les côtes qui restent */

    private val route = Route(
        name = "Deux bosses",
        totalDistance = 20_000.0,
        profile = ElevationProfile(
            listOf(
                ProfilePoint(0.0, 100.0),
                ProfilePoint(4_000.0, 100.0),
                ProfilePoint(6_000.0, 300.0),
                ProfilePoint(10_000.0, 200.0),
                ProfilePoint(12_000.0, 400.0),
                ProfilePoint(20_000.0, 150.0),
            ),
        ),
        climbs = listOf(
            RouteClimb(startDistance = 4_000.0, length = 2_000.0, grade = 10.0, totalElevation = 200.0),
            RouteClimb(startDistance = 10_000.0, length = 2_000.0, grade = 10.0, totalElevation = 200.0),
        ),
    )

    @Test
    fun `les cotes restantes gardent leur rang`() {
        val restantes = EffortBudget.remainingClimbs(route, distanceAlongRoute = 0.0)

        assertEquals(listOf(1, 2), restantes.map { it.number })
        assertEquals(200.0, restantes[0].ascent, 1e-9)
        assertEquals(200.0, restantes[1].ascent, 1e-9)
    }

    @Test
    fun `une cote commencee ne compte que ce qui reste a monter`() {
        // À mi-côte, cent mètres de dénivelé sont derrière.
        val restantes = EffortBudget.remainingClimbs(route, distanceAlongRoute = 5_000.0)

        assertEquals(listOf(1, 2), restantes.map { it.number })
        assertEquals(100.0, restantes[0].ascent, 1e-9)
    }

    @Test
    fun `une cote passee sort du budget mais les rangs ne bougent pas`() {
        val restantes = EffortBudget.remainingClimbs(route, distanceAlongRoute = 8_000.0)

        assertEquals("le rang de la seconde côte reste le sien", listOf(2), restantes.map { it.number })
        assertTrue(restantes.single().ascent > 0.0)
    }

    @Test
    fun `sans profil on ne detaille rien`() {
        assertEquals(emptyList<RemainingClimb>(), EffortBudget.remainingClimbs(route.copy(profile = null), 0.0))
    }

    /* ------------------------------------------------- dépensé contre restant */

    private val budget = EffortEstimate(kilojoules = 400.0, items = emptyList())

    @Test
    fun `la part d'effort se compte sur le total des deux moities`() {
        val progres = EffortBudget.progress(
            spentKilojoules = 600.0,
            estimate = budget,
            distanceAlongRoute = 50_000.0,
            totalDistance = 100_000.0,
        )!!

        assertEquals(1_000.0, progres.totalKilojoules, 1e-9)
        assertEquals(0.6, progres.effortFraction!!, 1e-9)
        assertEquals(0.5, progres.distanceFraction!!, 1e-9)
    }

    @Test
    fun `l'avance est positive quand le plus dur est derriere`() {
        val progres = EffortBudget.progress(600.0, budget, 50_000.0, 100_000.0)!!

        assertEquals("dix points d'avance de l'effort sur la distance", 0.1, progres.lead!!, 1e-9)
    }

    @Test
    fun `l'avance est negative quand ce qui reste coute plus cher`() {
        val progres = EffortBudget.progress(300.0, budget, 60_000.0, 100_000.0)!!

        assertTrue("l'effort est en retard sur les kilomètres", progres.lead!! < 0.0)
    }

    @Test
    fun `sans depense mesuree il n'y a pas de comparaison`() {
        assertNull(EffortBudget.progress(null, budget, 0.0, 100_000.0))
    }

    @Test
    fun `sans budget il n'y a pas de comparaison`() {
        assertNull(EffortBudget.progress(600.0, null, 0.0, 100_000.0))
    }

    @Test
    fun `hors itineraire la part de distance manque mais le reste tient`() {
        val progres = EffortBudget.progress(600.0, budget, null, null)!!

        assertNull(progres.distanceFraction)
        assertNull("sans distance, aucune avance à annoncer", progres.lead)
        assertEquals(0.6, progres.effortFraction!!, 1e-9)
    }

    @Test
    fun `au depart rien n'est depense et la part d'effort est nulle`() {
        val progres = EffortBudget.progress(0.0, budget, 0.0, 100_000.0)!!

        assertEquals(0.0, progres.effortFraction!!, 1e-9)
        assertEquals(0.0, progres.lead!!, 1e-9)
    }
}
