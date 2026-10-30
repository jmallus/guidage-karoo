package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.BendStatus
import io.github.jmallus.guidage.core.Bends
import io.github.jmallus.guidage.core.Carrefour
import io.github.jmallus.guidage.core.Carrefours
import io.github.jmallus.guidage.core.ClimbStatus
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.Geo
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.ManoeuvreAVenir
import io.github.jmallus.guidage.core.PorteeCarte
import io.github.jmallus.guidage.core.ProfileWindow
import io.github.jmallus.guidage.core.Route
import io.github.jmallus.guidage.core.RouteBend
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.core.Zones
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.settings.GuidageSettings
import io.github.jmallus.guidage.ui.BandeauGuidage
import io.github.jmallus.guidage.ui.CarteVirage
import io.github.jmallus.guidage.ui.CentreGuidage
import io.github.jmallus.guidage.ui.DonneeGuidage
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.GuidageModel
import io.github.jmallus.guidage.ui.GuidageRenderer
import io.github.jmallus.guidage.ui.PreviewData
import io.github.jmallus.guidage.ui.Suite
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ce que montre le tableau de guidage, construit à partir de l'état de la sortie.
 *
 * Comme pour le tableau de bord, la même construction sert au champ et au simulateur : ce que
 * montre celui-ci est ce que montrera l'appareil.
 *
 * Le milieu de l'écran ne montre qu'une chose, la plus urgente — un carrefour à moins de
 * [APPROCHE] mètres, sinon une descente qui tourne serré, sinon la côte qu'on monte, sinon la
 * carte. L'ordre est celui du danger : rater un carrefour fait faire demi-tour, rater un virage
 * en descente fait tomber, et la côte se monte de toute façon.
 */
object GuidageModels {

    fun build(
        context: Context,
        snapshot: GuidanceSnapshot,
        rideData: RideData,
        settings: GuidageSettings,
        preview: Boolean,
        roadSource: RoadSource,
        nowMillis: Long = System.currentTimeMillis(),
    ): GuidageModel {
        val state = if (preview) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }
        val units = snapshot.units
        val route = state.route
        val location = if (preview) PreviewData.location else snapshot.location
        val suivie = location?.suivie(route, state.distanceAlongRoute, nowMillis, rideData.speed)
        val position = suivie?.point

        // Les voies se lisent une fois, assez loin pour la recherche des carrefours comme pour
        // la carte : le dépôt ne garde qu'une région, et deux rayons la feraient relire sans fin.
        val roads = if (preview) PreviewData.roads else roadSource.roads(position, RAYON_VOIES)
        val luesUneFois = object : RoadSource {
            override fun roads(position: GeoPoint?, radiusMeters: Double): List<RoadSegment> = roads
            override fun notice(context: Context, position: GeoPoint?): String? = roadSource.notice(context, position)
        }

        // Les carrefours et les virages se comptent sur le tracé géométrique, pas sur
        // l'abscisse du Karoo : les deux divergent au fil de la sortie.
        val surTrace = surTrace(route, position, state.distanceAlongRoute)
        val manoeuvre = if (route != null && surTrace != null) manoeuvre(route, roads, surTrace) else null
        val grade = state.currentGrade ?: rideData.grade
        val descente = route != null && surTrace != null && grade != null && grade <= PENTE_DESCENTE
        val virages = if (descente) virages(route!!, surTrace!!) else emptyList()
        val annoncee = state.distanceAlongRoute
        val cote = if (route != null && annoncee != null) Guidance.climbStatus(route, annoncee, rideData.climb) else null

        val suite = manoeuvre?.let { Suite(it.carrefour.virage, context.getString(R.string.route_next_in, Format.distance(it.distance, units))) }
        val centre = when {
            manoeuvre != null && manoeuvre.distance <= APPROCHE -> carrefour(context, manoeuvre, units)
            virages.isNotEmpty() -> CentreGuidage.Descente(
                titre = context.getString(R.string.route_descent_title),
                cartes = virages.map { carteVirage(context, it, units) },
                note = context.getString(R.string.route_descent_note),
            )
            cote?.onClimb == true -> CentreGuidage.Montee(
                profil = DashboardModels.profileBand(context, snapshot, settings, preview, rideData, nowMillis).let { p ->
                    // Le kilométrage au compteur au-dessus du coureur, comme sur le bandeau du
                    // tableau de bord à portée fixée : celui-ci ne l'écrit pas dans une côte.
                    p.copy(positionLabel = p.positionLabel ?: (rideData.distance ?: p.positionDistance)?.let { Format.longDistanceValue(it, units) })
                },
                suite = suite,
                titre = Format.distance(cote.distanceToTop, units) + " · +" + Format.elevation(cote.elevationToTop, units),
                rang = "${cote.number}/${cote.totalClimbs}",
                pois = route!!.pois.map { it.distanceAlongRoute to PoiLabels.label(context, it) },
            )
            else -> CentreGuidage.Carte(
                carte = DashboardModels.mapModel(
                    context, snapshot, state, preview, rideData,
                    PorteeCarte.of(settings.mapZoom), luesUneFois, nowMillis,
                ),
                suite = suite,
            )
        }

        val bandeau = bandeau(state, rideData, settings, units)
        return GuidageModel(
            donnees = donnees(context, units, rideData, if (descente) grade else null),
            centre = centre,
            // En côte, le profil est déjà au milieu de l'écran, en grand : le bandeau du bas
            // le redirait en petit. Il cède la place à ce que le profil ne dit pas en chiffres.
            bandeau = if (centre is CentreGuidage.Montee && cote != null) {
                bandeau.copy(
                    cote = chiffresDeCote(
                        context, cote, grade, rideData, units,
                        restant = annoncee?.let { route?.profile?.ascentBetween(it, route.totalDistance) },
                    ),
                )
            } else {
                bandeau
            },
            palette = FieldPalette.of(context),
        )
    }

    /**
     * Vitesse, puissance et cœur, chacun à la couleur de sa zone. En descente, la puissance
     * ne dit plus rien — on ne pédale pas — et cède sa place à la pente.
     */
    private fun donnees(context: Context, units: Units, rideData: RideData, pente: Double?): List<DonneeGuidage> = listOf(
        DonneeGuidage(
            context.getString(R.string.route_label_speed),
            rideData.speed?.let { Format.speed(it, units) } ?: PLACEHOLDER,
            rideData.speed?.let { Zones.speedColor(it, rideData.averageSpeed) },
        ),
        if (pente != null) {
            DonneeGuidage(context.getString(R.string.route_label_grade), Format.shortGrade(pente))
        } else {
            DonneeGuidage(
                context.getString(R.string.route_label_power),
                rideData.power?.toInt()?.toString() ?: PLACEHOLDER,
                rideData.power?.let { Zones.powerColor(it, rideData.powerZones) },
            )
        },
        DonneeGuidage(
            context.getString(R.string.dashboard_label_heart_rate),
            rideData.heartRate?.toInt()?.toString() ?: PLACEHOLDER,
            rideData.heartRate?.let { Zones.heartRateColor(it, rideData.heartRateZones) },
        ),
    )

    /** Pente, VAM, distance jusqu'au sommet, et dénivelé restant jusqu'à l'arrivée. */
    private fun chiffresDeCote(
        context: Context,
        cote: ClimbStatus,
        pente: Double?,
        rideData: RideData,
        units: Units,
        restant: Double?,
    ): List<DonneeGuidage> = listOf(
        DonneeGuidage(
            context.getString(R.string.route_label_grade),
            pente?.let { Format.grade(it) } ?: PLACEHOLDER,
            pente?.let { Zones.gradeColor(it) },
        ),
        DonneeGuidage(
            context.getString(R.string.route_label_vam, Format.elevation(0.0, units).substringAfter(' ') + "/h"),
            rideData.verticalSpeed?.let { Format.elevation(it * 3_600.0, units).substringBefore(' ') } ?: PLACEHOLDER,
        ),
        DonneeGuidage(context.getString(R.string.route_label_to_top), Format.distance(cote.distanceToTop, units)),
        DonneeGuidage(
            context.getString(R.string.route_label_elevation_remaining),
            // Le D+ que le Karoo compte lui-même jusqu'à l'arrivée ; à défaut, celui du profil.
            (rideData.level.elevationRemaining ?: restant)?.let { "+" + Format.elevation(it, units) } ?: PLACEHOLDER,
        ),
    )

    private fun carrefour(context: Context, m: ManoeuvreAVenir, units: Units): CentreGuidage.Carrefour {
        val c = m.carrefour
        val cote = context.getString(if (c.cote > 0) R.string.route_side_right else R.string.route_side_left)
        val titre = when {
            m.rang >= 2 -> context.getString(R.string.route_turn_rank, m.rang, cote)
            abs(c.virage) >= EPINGLE -> context.getString(R.string.route_turn_sharp, cote)
            abs(c.virage) < FRANC -> context.getString(R.string.route_turn_slight, cote)
            else -> context.getString(R.string.route_turn, cote)
        }
        return CentreGuidage.Carrefour(
            titre = titre,
            distance = Format.distance(m.distance.coerceAtLeast(0.0), units),
            virage = c.virage,
            branches = c.branches,
            voiesAvant = m.voiesAvant.map { (d, angles) -> part(d) to angles },
            progression = part(m.distance),
            metres = m.distance,
            repere = c.distanceAlongRoute,
        )
    }

    /** Part de l'approche déjà faite quand il reste [distance] mètres. */
    private fun part(distance: Double): Float = (1.0 - distance / APPROCHE).coerceIn(0.0, 1.0).toFloat()

    /**
     * Un virage sur sa carte, avec une vitesse indicative.
     *
     * La vitesse est celle à laquelle l'accélération latérale atteint [ACCELERATION_LATERALE]
     * sur le rayon du tracé : v = √(a·R). Elle ne sait rien de l'adhérence ni du dévers, et le
     * rayon vient d'un tracé simplifié — la note sous les cartes le dit.
     */
    private fun carteVirage(context: Context, statut: BendStatus, units: Units): CarteVirage {
        val rayon = statut.bend.radius
        val metres = rayon.toInt()
        val vitesse = Format.speed(sqrt(ACCELERATION_LATERALE * rayon), units).substringBefore(',').substringBefore('.')
        return CarteVirage(
            direction = statut.bend.direction,
            distance = Format.distance(statut.distance, units),
            detail = when {
                rayon < 15.0 -> context.getString(R.string.route_bend_hairpin, metres)
                rayon < 30.0 -> context.getString(R.string.route_bend_tight, metres)
                else -> context.getString(R.string.route_bend_radius, metres)
            },
            vitesse = context.getString(R.string.route_bend_speed, vitesse, Format.speedUnit(units)),
            couleur = when {
                rayon < 15.0 -> GuidageRenderer.CARTE_ROUGE
                rayon < 30.0 -> GuidageRenderer.CARTE_AMBRE
                else -> GuidageRenderer.CARTE_VERTE
            },
        )
    }

    /**
     * Le bandeau du bas : la portée réglée du profil, à échelle régulière, et ce qui reste de
     * la journée en kilomètres et en dénivelé.
     */
    private fun bandeau(state: GuidanceState, rideData: RideData, settings: GuidageSettings, units: Units): BandeauGuidage {
        val route = state.route
        val along = state.distanceAlongRoute
        if (route == null || along == null) {
            return BandeauGuidage(ProfileWindow(emptyList(), 0.0, 0.0, 0.0, 0.0), null)
        }
        val fenetre = Guidance.profileWindow(route, along, settings.graphZoom.lookaheadMeters)
        val reste = rideData.distanceRemaining ?: state.distanceRemaining
        val montee = route.profile?.ascentBetween(along, route.totalDistance)
        val texte = listOfNotNull(
            reste?.let { Format.longDistance(it, units) },
            montee?.takeIf { it >= 1.0 }?.let { "+" + Format.elevation(it, units) },
        ).joinToString(" · ").ifEmpty { null }
        return BandeauGuidage(fenetre, texte)
    }

    private fun surTrace(route: Route?, position: GeoPoint?, along: Double?): Double? {
        if (route == null || position == null || route.path.size < 2) return null
        return Geo.anchorOnPath(route.path, position, along, ANCRAGE)?.distanceAlongPath
    }

    /**
     * La prochaine manœuvre, sur des carrefours gardés d'une seconde à l'autre.
     *
     * Les carrefours d'un kilomètre de route sont cherchés en une fois, et ne le sont de
     * nouveau que lorsque le coureur a avancé de [RECHERCHE_PAS] mètres, que l'itinéraire a
     * changé, ou que le fond de carte a été relu : entre deux, il suffit d'y chercher le
     * premier devant soi.
     */
    private fun manoeuvre(route: Route, roads: List<RoadSegment>, along: Double): ManoeuvreAVenir? {
        val gardes = synchronized(this) {
            val g = carrefoursGardes
            if (g != null && g.path === route.path && g.roads === roads && along in g.depuis..g.depuis + RECHERCHE_PAS) {
                g.carrefours
            } else {
                val depuis = along - Carrefours.DEPASSE
                val trouves = Carrefours.entre(route.path, roads, depuis, depuis + PORTEE_SUITE + RECHERCHE_PAS + Carrefours.DEPASSE)
                carrefoursGardes = CarrefoursGardes(route.path, roads, depuis, trouves)
                trouves
            }
        }
        return Carrefours.parmi(gardes, along, PORTEE_SUITE)
    }

    /** Les virages serrés des [PORTEE_VIRAGES] mètres qui viennent, du tracé entier calculé une fois. */
    private fun virages(route: Route, along: Double): List<BendStatus> {
        val tous = synchronized(this) {
            val g = viragesGardes
            if (g != null && g.first === route.path) {
                g.second
            } else {
                Bends.of(route.path).also { viragesGardes = route.path to it }
            }
        }
        return Bends.ahead(tous, along, PORTEE_VIRAGES)
            .filter { it.bend.radius < RAYON_SERRE }
            .take(3)
    }

    private class CarrefoursGardes(
        val path: List<GeoPoint>,
        val roads: List<RoadSegment>,
        val depuis: Double,
        val carrefours: List<Carrefour>,
    )

    private var carrefoursGardes: CarrefoursGardes? = null
    private var viragesGardes: Pair<List<GeoPoint>, List<RouteBend>>? = null

    private const val PLACEHOLDER = "--"

    /** Distance à laquelle le carrefour prend le milieu de l'écran (m). */
    const val APPROCHE = 300.0

    /** Jusqu'où l'on annonce la manœuvre suivante en étiquette (m). */
    private const val PORTEE_SUITE = 1_000.0

    /** De combien le coureur avance avant qu'on recherche les carrefours (m). */
    private const val RECHERCHE_PAS = 200.0

    /** Rayon de lecture du fond de carte (m) : la portée de l'étiquette, et un peu plus. */
    private const val RAYON_VOIES = 1_300.0

    /** Pente sous laquelle on est en descente (%). */
    private const val PENTE_DESCENTE = -4.0

    /** Rayon sous lequel un virage de descente mérite sa carte (m) — celui où l'on lève du gaz. */
    private const val RAYON_SERRE = 60.0

    /** Jusqu'où l'on regarde les virages en descente (m). */
    private const val PORTEE_VIRAGES = 600.0

    /**
     * Accélération latérale des vitesses indicatives (m/s²), un peu plus d'un tiers de g :
     * une allure prudente sur route sèche, loin de la limite d'adhérence d'un pneu de route.
     */
    private const val ACCELERATION_LATERALE = 3.5

    /** Angles au-delà desquels on tourne franchement, puis en épingle (degrés). */
    private const val FRANC = 45.0
    private const val EPINGLE = 135.0

    /** Écart en deçà duquel deux passages du tracé se départagent par la distance du Karoo (m). */
    private const val ANCRAGE = 25.0
}
