package io.github.jmallus.guidage.ui

import io.github.jmallus.guidage.core.Drivetrain
import io.github.jmallus.guidage.core.ElevationProfile
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.LearnedPace
import io.github.jmallus.guidage.core.ProfilePoint
import io.github.jmallus.guidage.core.Route
import io.github.jmallus.guidage.core.RouteClimb
import io.github.jmallus.guidage.core.RoutePoi
import io.github.jmallus.guidage.core.ZoneRange
import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.core.map.RoadSurface
import io.github.jmallus.guidage.core.map.toMicroDegrees
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RiderLocation
import kotlin.math.cos
import kotlin.math.sin

/**
 * Itinéraire fictif utilisé lorsque le champ est affiché en mode aperçu
 * (écran d'édition des pages), afin qu'il ne reste pas vide.
 */
object PreviewData {

    const val DISTANCE_ALONG_ROUTE = 1_000.0

    /** Position et cap fictifs, pour que la minicarte ait de quoi s'orienter en aperçu. */
    val location = RiderLocation(GeoPoint(45.180, 5.720), heading = 30.0)

    /**
     * Tracé sinueux fictif partant de [location], pour que l'aperçu ressemble à un parcours
     * plutôt qu'à une ligne droite.
     */
    private val previewPath: List<GeoPoint> by lazy {
        (0..(LONGUEUR / PAS_TRACE).toInt()).map { step ->
            val meters = step * PAS_TRACE
            val wander = sin(step / 6.0) * 250.0
            GeoPoint(
                lat = location.position.lat + (meters * cos(Math.toRadians(30.0)) + wander) / 110_540.0,
                lng = location.position.lng + (meters * sin(Math.toRadians(30.0)) - wander) / 78_700.0,
            )
        }
    }

    /**
     * Longueur du parcours d'aperçu.
     *
     * Trente-deux kilomètres, et non les douze d'origine. Douze ne suffisaient pas au champ
     * « Réserve » : une traversée ne s'annonce qu'au-delà de quinze kilomètres sans
     * ravitaillement, si bien que son alerte — ce pour quoi le champ existe — ne pouvait
     * jamais s'afficher au banc d'essai. Un parcours d'aperçu qui ne peut pas montrer un état
     * du champ ne le juge pas.
     *
     * Les douze premiers kilomètres n'ont pas bougé d'un mètre : les contrôles s'y réfèrent
     * par leurs distances, et le raidillon du troisième est toujours au troisième.
     */
    private const val LONGUEUR = 32_000.0

    /** Pas du tracé fictif, en mètres. */
    private const val PAS_TRACE = 60.0

    /**
     * Les sommets du parcours d'aperçu : sa forme d'ensemble, et rien d'autre.
     *
     * Le faux plat du départ, le raidillon du troisième kilomètre, la descente du sixième, le
     * long ressaut du douzième — puis le col du vingt-cinquième, qui donne au profil œil de
     * poisson un lointain à comprimer. Les contrôles s'y réfèrent par leurs distances.
     */
    private val sommets = listOf(
        ProfilePoint(0.0, 180.0),
        ProfilePoint(1_000.0, 190.0),
        ProfilePoint(2_000.0, 210.0),
        ProfilePoint(3_000.0, 300.0),
        ProfilePoint(4_000.0, 420.0),
        ProfilePoint(5_000.0, 470.0),
        ProfilePoint(6_000.0, 430.0),
        ProfilePoint(8_000.0, 330.0),
        ProfilePoint(10_000.0, 380.0),
        ProfilePoint(12_000.0, 300.0),
        ProfilePoint(14_500.0, 260.0),
        ProfilePoint(17_000.0, 245.0),
        ProfilePoint(20_000.0, 250.0),
        ProfilePoint(23_000.0, 380.0),
        ProfilePoint(26_500.0, 610.0),
        ProfilePoint(28_000.0, 560.0),
        ProfilePoint(30_500.0, 350.0),
        ProfilePoint(32_000.0, 260.0),
    )

    /** Pas d'échantillonnage du profil, du même ordre que celui d'un relevé réel. */
    private const val PROFILE_STEP = 20.0

    /**
     * Le profil, échantillonné tous les vingt mètres.
     *
     * Il ne portait que les dix sommets ci-dessus, soit un point par kilomètre ou deux. Sur
     * les deux kilomètres que montre le bandeau de côte, cela faisait une ou deux droites :
     * une côte au cordeau, comme aucun terrain n'en présente. Un relevé d'itinéraire réel
     * porte un point tous les dix à trente mètres, et c'est cette densité-là qu'il faut au banc
     * d'essai pour montrer ce que le coureur verra.
     *
     * Entre deux sommets, le fond reste la droite qui les joint — la forme d'ensemble ne
     * bouge pas. Ce qui s'y ajoute est le relief : deux ondes de longueurs incommensurables,
     * l'une de quinze cents mètres et l'autre de six cents, qui ne se répètent donc jamais
     * ensemble. Leurs amplitudes sont choisies pour se voir sur une bande de quelques
     * dizaines de pixels — quelques mètres — sans dénaturer les pentes : chacune ne pèse que
     * deux points de pourcentage, de sorte qu'un raidillon à douze reste un raidillon.
     */
    private val profilDetaille: List<ProfilePoint> by lazy {
        val fin = sommets.last().distance
        var index = 1
        buildList {
            var distance = 0.0
            while (distance <= fin) {
                while (index < sommets.size - 1 && sommets[index].distance < distance) index++
                val avant = sommets[index - 1]
                val apres = sommets[index]
                val portee = apres.distance - avant.distance
                val part = if (portee <= 0) 0.0 else (distance - avant.distance) / portee
                val fond = avant.elevation + (apres.elevation - avant.elevation) * part
                add(ProfilePoint(distance, fond + relief(distance)))
                distance += PROFILE_STEP
            }
        }
    }

    /** Le relief posé sur la forme d'ensemble, en mètres. */
    /** Le point du tracé fictif le plus proche d'une distance donnée. */
    private fun pointA(distance: Double): GeoPoint? =
        previewPath.getOrNull((distance / PAS_TRACE).toInt())

    private fun relief(distance: Double): Double =
        5.0 * sin(distance / 300.0 + 0.6) + 1.8 * sin(distance / 95.0 + 2.3)

    val route: Route by lazy {
        Route(
            name = "Aperçu",
            totalDistance = LONGUEUR,
            profile = ElevationProfile(profilDetaille),
            climbs = listOf(
                RouteClimb(startDistance = 2_400.0, length = 2_600.0, grade = 6.5, totalElevation = 260.0),
                RouteClimb(startDistance = 8_000.0, length = 2_000.0, grade = 2.5, totalElevation = 50.0),
                RouteClimb(startDistance = 20_000.0, length = 6_500.0, grade = 5.5, totalElevation = 360.0),
            ),
            pois = listOf(
                RoutePoi(
                    id = "preview-water",
                    name = "Fontaine",
                    type = "water",
                    distanceAlongRoute = 2_000.0,
                    position = pointA(2_000.0),
                ),
                // Deux points de plus, pour que le champ « Réserve » ait une répartition à
                // montrer et non un point isolé : c'est l'espacement qu'il donne à lire.
                RoutePoi(
                    id = "preview-coffee",
                    name = "Café du Pont",
                    type = "coffee",
                    distanceAlongRoute = 4_200.0,
                    position = pointA(4_200.0),
                ),
                RoutePoi(
                    id = "preview-store",
                    name = "Épicerie",
                    type = "convenience_store",
                    distanceAlongRoute = 5_600.0,
                    position = pointA(5_600.0),
                ),
            ),
            path = previewPath,
        )
    }

    /**
     * Fond de carte fictif, dessiné autour de [location].
     *
     * L'aperçu du sélecteur de champs ne peut pas lire le vrai fond : il n'a pas de position,
     * et le coureur qui hésite à poser le champ est chez lui, pas sur son parcours. Sans
     * décor, la minicarte s'y montrait comme un trait jaune sur du vide, ce qui ne dit rien
     * de ce qu'elle donne en roulant.
     *
     * Le décor est composé pour montrer la palette entière — les classes de voies, les
     * revêtements, les quatre familles de surfaces — en un seul coup d'œil : c'est là, et
     * nulle part ailleurs, qu'on peut juger des couleurs avant de sortir.
     */
    val roads: List<RoadSegment> by lazy {
        listOf(
            // Les surfaces d'abord : la campagne au sud, le village au nord-ouest, le bois
            // au nord-est, l'étang au milieu.
            area(RoadKind.FARMLAND, listOf(-460 to -460, 460 to -460, 460 to 130, -460 to 130)),
            area(RoadKind.BUILT_UP, listOf(-460 to 150, -140 to 150, -140 to 460, -460 to 460)),
            area(RoadKind.FOREST, listOf(140 to 140, 460 to 170, 460 to 460, 190 to 460)),
            area(RoadKind.WATER, listOf(-40 to 250, 60 to 240, 90 to 320, -20 to 340)),
            // Le ruisseau qui alimente l'étang, puis en ressort.
            line(RoadKind.STREAM, listOf(-460 to 200, -180 to 230, -40 to 280)),
            line(RoadKind.STREAM, listOf(90 to 300, 260 to 350, 460 to 380)),
            // Les voies, de la plus roulante à la plus discrète.
            line(RoadKind.MOTORWAY, listOf(-460 to 430, 460 to 400)),
            line(RoadKind.PRIMARY, listOf(-460 to -30, -120 to 10, 180 to -10, 460 to 30)),
            line(RoadKind.SECONDARY, listOf(-230 to -460, -200 to -20, -180 to 230, -160 to 460)),
            line(RoadKind.RESIDENTIAL, listOf(-420 to 250, -220 to 260, -170 to 380)),
            line(RoadKind.SERVICE, listOf(-300 to 255, -290 to 130)),
            line(RoadKind.CYCLEWAY, listOf(-460 to -70, -110 to -30, 200 to -50)),
            line(RoadKind.TRACK, listOf(-100 to 0, 60 to -180, 300 to -280), RoadSurface.UNPAVED),
            line(RoadKind.PATH, listOf(180 to 0, 260 to 160, 320 to 330), RoadSurface.UNPAVED),
            line(RoadKind.FOOTWAY, listOf(-160 to 340, -60 to 300, 0 to 250)),
        )
    }

    /** Une voie, donnée en mètres à l'est et au nord de [location]. */
    private fun line(
        kind: RoadKind,
        points: List<Pair<Int, Int>>,
        surface: RoadSurface = RoadSurface.UNKNOWN,
    ) = RoadSegment(
        kind = kind,
        surface = surface,
        latitudes = points.map { latitudeOf(it.second) }.toIntArray(),
        longitudes = points.map { longitudeOf(it.first) }.toIntArray(),
    )

    /** Un contour, même convention. Il se referme de lui-même. */
    private fun area(kind: RoadKind, points: List<Pair<Int, Int>>) =
        line(kind, points, RoadSurface.UNKNOWN)

    private fun latitudeOf(metersNorth: Int): Int =
        (location.position.lat + metersNorth / METERS_PER_DEGREE_LATITUDE).toMicroDegrees()

    private fun longitudeOf(metersEast: Int): Int {
        val perDegree = METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(location.position.lat))
        return (location.position.lng + metersEast / perDegree).toMicroDegrees()
    }

    private const val METERS_PER_DEGREE_LATITUDE = 110_540.0

    /**
     * Zones fictives, pour que l'aperçu montre la coloration par zone même chez un coureur
     * qui n'a pas encore réglé les siennes.
     */
    private val previewPowerZones = listOf(
        ZoneRange(0, 120), ZoneRange(121, 165), ZoneRange(166, 200), ZoneRange(201, 230),
        ZoneRange(231, 270), ZoneRange(271, 330), ZoneRange(331, 2_000),
    )

    private val previewHeartRateZones = listOf(
        ZoneRange(0, 120), ZoneRange(121, 140), ZoneRange(141, 155),
        ZoneRange(156, 170), ZoneRange(171, 220),
    )

    /**
     * Valeurs qui défilent dans l'aperçu du sélecteur de champs.
     *
     * Elles balaient volontairement plusieurs zones d'effort : c'est ce qui permet de voir,
     * avant de poser le champ sur une page, à quoi ressemblent les aplats de couleur et le
     * passage de l'encre au noir sur les teintes claires.
     */
    fun rideSamples(nowMilliseconds: Long): List<RideData> {
        val averageSpeed = 8.6
        return listOf(
            sample(7.9, averageSpeed, 150.0, 132.0, 84.0, 1.0, 24_300.0, nowMilliseconds, 62),
            sample(10.5, averageSpeed, 188.0, 149.0, 92.0, 4.0, 23_100.0, nowMilliseconds, 58),
            sample(6.2, averageSpeed, 262.0, 166.0, 71.0, 9.0, 21_800.0, nowMilliseconds, 55),
            sample(12.8, averageSpeed, 318.0, 178.0, 98.0, -3.0, 20_400.0, nowMilliseconds, 51),
        )
    }

    /**
     * Allure supposée déjà apprise, pour que l'aperçu montre la marge d'arrivée.
     *
     * Sans elle, l'aperçu retomberait sur l'heure fournie avec l'échantillon et le libellé
     * n'afficherait jamais son « ± » — on choisirait le champ sans avoir vu ce qu'il fait.
     */
    private val previewPace = LearnedPace(
        flatSpeed = 8.6,
        climbRate = 0.18,
        flatSpread = 0.11,
        climbSpread = 0.16,
        flatSeconds = 2_400.0,
        climbSeconds = 900.0,
        flatPower = 190.0,
        climbPower = 265.0,
    )

    /**
     * Le relevé de l'aperçu du budget d'effort.
     *
     * Il ne suffit pas d'une allure : le budget veut aussi une distance restante, faute de
     * quoi l'aperçu montrerait le message d'attente au lieu du champ qu'on vient choisir.
     */
    val effortSample: RideData = RideData(
        distanceRemaining = 24_300.0,
        pace = previewPace,
    )

    private fun sample(
        speed: Double,
        averageSpeed: Double,
        power: Double,
        heartRate: Double,
        cadence: Double,
        grade: Double,
        distanceRemaining: Double,
        nowMilliseconds: Long,
        minutesToArrival: Int,
    ) = RideData(
        speed = speed,
        averageSpeed = averageSpeed,
        power = power,
        heartRate = heartRate,
        cadence = cadence,
        grade = grade,
        distance = 120_000.0 - distanceRemaining,
        distanceRemaining = distanceRemaining,
        drivetrain = Drivetrain(
            front = 2,
            frontCount = 2,
            frontTeeth = 50,
            rear = minutesToArrival % 11 + 1,
            rearCount = 11,
            rearTeeth = 17,
        ),
        arrivalTime = (nowMilliseconds + minutesToArrival * 60_000L).toDouble(),
        powerZones = previewPowerZones,
        heartRateZones = previewHeartRateZones,
        pace = previewPace,
    )
}
