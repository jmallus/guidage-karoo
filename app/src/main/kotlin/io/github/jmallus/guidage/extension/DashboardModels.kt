package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.GuidanceZoneType
import io.github.jmallus.guidage.core.MapZoom
import io.github.jmallus.guidage.core.ProfileWindow
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.core.Zones
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.settings.GuidageSettings
import io.github.jmallus.guidage.ui.DashboardModel
import io.github.jmallus.guidage.ui.DrivetrainModel
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.GraphPoi
import io.github.jmallus.guidage.ui.GuidanceZone
import io.github.jmallus.guidage.ui.MapModel
import io.github.jmallus.guidage.ui.MapPoi
import io.github.jmallus.guidage.ui.ProfileFieldModel
import io.github.jmallus.guidage.ui.PreviewData
import io.github.jmallus.guidage.ui.RouteGraphModel
import io.github.jmallus.guidage.ui.Tile
import kotlin.math.roundToInt

/**
 * Le fond de carte, vu du constructeur de modèle.
 *
 * Le tableau de bord n'a pas à savoir d'où viennent les voies : de la carte hors ligne
 * installée sur l'appareil, du décor de l'aperçu, ou de ce que le simulateur lui donne. Cette
 * interface est ce qui permet au simulateur d'exécuter le vrai code d'affichage sans embarquer
 * quarante méga-octets de Normandie.
 */
interface RoadSource {

    /** Les voies autour de [position], dans un rayon de [radiusMeters]. */
    fun roads(position: GeoPoint?, radiusMeters: Double): List<RoadSegment>

    /**
     * Pourquoi il n'y en a aucune, à porter discrètement sur la carte vide.
     *
     * Null quand l'absence de voie ne demande rien au coureur — l'aperçu et le simulateur
     * ont leur décor, et n'ont donc rien à expliquer.
     */
    fun notice(context: Context, position: GeoPoint?): String?
}

/**
 * Ce que le tableau de bord affiche, construit à partir de l'état de la sortie.
 *
 * Extrait du champ lui-même pour qu'une seule et même construction serve à l'extension et au
 * simulateur de bureau : ce que montre le simulateur est alors ce que montrera l'appareil, au
 * lieu d'en être une deuxième écriture qui dérive.
 */
object DashboardModels {

    fun build(
        context: Context,
        snapshot: GuidanceSnapshot,
        rideData: RideData,
        settings: GuidageSettings,
        preview: Boolean,
        roadSource: RoadSource,
        nowMillis: Long = System.currentTimeMillis(),
    ): DashboardModel {
        val state = if (preview) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }

        val units = snapshot.units
        // La bande du soir ne prend le pied que si elle a une frise à montrer : sans position
        // ni coucher, les deux cases d'origine valent mieux qu'un message d'attente.
        val night = NightModels.build(context, snapshot, rideData, preview, nowMillis)
            .takeIf { it.timeline != null }
        return DashboardModel(
            guidance = when (settings.guidanceZone) {
                GuidanceZoneType.MAP -> GuidanceZone.Map(
                    mapModel(context, snapshot, state, preview, rideData, settings.mapZoom, roadSource, nowMillis),
                )
                GuidanceZoneType.PROFILE -> GuidanceZone.Profile(profileModel(context, state, settings))
            },
            topTiles = effortTiles(context, units, rideData),
            drivetrain = drivetrainModel(context, rideData),
            heartRateTile = heartRateTile(context, rideData),
            midTiles = midTiles(context, units, rideData),
            footerTiles = footerTiles(context, rideData, state, nowMillis, withArrival = night == null),
            night = night,
            profileBand = profileBand(context, snapshot, settings, preview),
            palette = FieldPalette.of(context),
        )
    }

    /**
     * Bandeau du bas : le champ « Profil à venir », tel quel.
     *
     * Il ne dépend pas de la présence d'une côte. Un bandeau qui apparaît au pied d'une bosse
     * et disparaît au sommet oblige à réapprendre la mise en page de l'écran chaque fois qu'il
     * surgit, et se dérobe précisément quand on voudrait savoir si le faux plat qu'on subit
     * en est un.
     *
     * Il portait deux kilomètres calés sur le coureur, à échelle régulière. Ce cadrage
     * répondait à « qu'est-ce que je monte », jamais à « qu'est-ce qui reste » — et la
     * première question a déjà ses réponses ailleurs sur l'écran : la pente dans son rang, la
     * distance au sommet dans le champ de côte. Le bandeau montre donc tout le parcours
     * restant, sur l'échelle comprimée au loin, les côtes y étant surlignées comme dans le
     * champ homonyme.
     *
     * C'est le modèle de ce champ, sans rien de recopié : les deux montrent la même chose et
     * doivent continuer de le faire.
     */
    private fun profileBand(
        context: Context,
        snapshot: GuidanceSnapshot,
        settings: GuidageSettings,
        preview: Boolean,
    ): ProfileFieldModel? =
        FieldModels.profile(context, snapshot, settings, preview).takeIf { !it.window.isEmpty }
    private fun mapModel(
        context: Context,
        snapshot: GuidanceSnapshot,
        state: GuidanceState,
        preview: Boolean,
        rideData: RideData,
        zoom: MapZoom,
        roadSource: RoadSource,
        nowMillis: Long,
    ): MapModel {
        val route = state.route
        val location = if (preview) PreviewData.location else snapshot.location
        // Le point rapporté par le Karoo a déjà quelques secondes ; on le prolonge à la
        // vitesse courante, sans quoi le coureur se voit derrière lui-même à l'approche
        // du carrefour, là précisément où il regarde la carte.
        val position = location?.extrapolated(nowMillis, rideData.speed)
        // On lit un peu au-delà du cadre : en cap en haut, la fenêtre tourne avec le coureur
        // et ses coins balaient plus loin que la portée annoncée.
        val roads = if (preview) {
            PreviewData.roads
        } else {
            roadSource.roads(position, zoom.rangeMeters * ROADS_RADIUS_FACTOR)
        }
        return MapModel(
            roads = roads,
            roadsMessage = if (roads.isEmpty() && !preview) roadSource.notice(context, position) else null,
            path = route?.path.orEmpty(),
            position = position,
            heading = location?.heading,
            pois = route?.pois.orEmpty().mapNotNull { poi ->
                poi.position?.let { MapPoi(it, PoiLabels.label(context, poi)) }
            },
            rangeMeters = zoom.rangeMeters,
            chevronRangeMeters = zoom.chevronMeters,
            offRoute = rideData.onRoute == false,
            emptyMessage = context.getString(
                if (route == null) R.string.field_no_route else R.string.field_waiting_for_position,
            ),
        )
    }

    private fun profileModel(
        context: Context,
        state: GuidanceState,
        settings: GuidageSettings,
    ): RouteGraphModel {
        val route = state.route
        val along = state.distanceAlongRoute
        if (route == null || along == null) {
            return RouteGraphModel(
                window = ProfileWindow(emptyList(), 0.0, 0.0, 0.0, 0.0),
                position = 0.0,
                emptyMessage = context.getString(R.string.field_no_route),
                colorByGrade = settings.colorByGrade,
            )
        }

        val zoom = settings.graphZoom
        val window = Guidance.routeGraphWindow(route, along, zoom.lookaheadMeters)
        return RouteGraphModel(
            window = window,
            position = along,
            climbs = route.climbs,
            pois = route.pois.map { GraphPoi(it.distanceAlongRoute, PoiLabels.label(context, it)) },
            zoomLabel = zoomLabel(context, settings),
            colorByGrade = settings.colorByGrade,
        )
    }

    private fun zoomLabel(context: Context, settings: GuidageSettings): String {
        val lookahead = settings.graphZoom.lookaheadMeters
            ?: return context.getString(R.string.dashboard_zoom_whole_route)
        return "${(lookahead / 1_000).toInt()} km"
    }

    /**
     * Bandeau du haut : l'effort instantané, vitesse, cadence, puissance.
     *
     * Vitesse et puissance reçoivent un aplat de fond qui les situe d'un coup d'œil : vert
     * ou rouge selon la moyenne de la sortie pour la première, couleur de zone Karoo pour
     * la seconde. La cadence n'a pas de zones et reste sur fond noir.
     */
    private fun effortTiles(context: Context, units: Units, rideData: RideData): List<Tile> = listOf(
        Tile(
            label = context.getString(R.string.dashboard_label_speed),
            value = rideData.speed?.let { Format.speed(it, units) } ?: PLACEHOLDER,
            background = rideData.speed?.let { Zones.speedColor(it, rideData.averageSpeed) },
            icon = R.drawable.ic_speed,
        ).splitDecimal(),
        Tile(
            label = context.getString(R.string.dashboard_label_cadence),
            value = rideData.cadence?.toInt()?.toString() ?: PLACEHOLDER,
            icon = R.drawable.ic_cadence,
        ),
        Tile(
            label = context.getString(R.string.dashboard_label_power),
            value = rideData.power?.toInt()?.toString() ?: PLACEHOLDER,
            background = rideData.power?.let { Zones.powerColor(it, rideData.powerZones) },
            icon = R.drawable.ic_power,
        ),
    )

    /**
     * Case de gauche sous la transmission : la fréquence cardiaque, à sa couleur de zone.
     *
     * Le numéro de zone est écrit à gauche de la valeur. L'aplat le disait déjà, mais de
     * mémoire seulement : le chiffre le nomme. Il ne s'écrit que si les zones sont réglées
     * dans le Karoo — sans elles, `zoneOf` rend zéro, qui ne veut rien dire.
     */
    private fun heartRateTile(context: Context, rideData: RideData): Tile = Tile(
        label = context.getString(R.string.dashboard_label_heart_rate),
        value = rideData.heartRate?.toInt()?.toString() ?: PLACEHOLDER,
        leading = rideData.heartRate
            ?.let { Zones.zoneOf(it, rideData.heartRateZones) }
            ?.takeIf { it > 0 }
            ?.toString(),
        background = rideData.heartRate?.let { Zones.heartRateColor(it, rideData.heartRateZones) },
        icon = R.drawable.ic_heart_rate,
    )

    /** La transmission, telle que la rapporte le groupe — vide s'il ne rapporte rien. */
    private fun drivetrainModel(context: Context, rideData: RideData) = DrivetrainModel(
        label = context.getString(R.string.dashboard_label_gears),
        front = rideData.drivetrain.front,
        frontCount = rideData.drivetrain.frontCount,
        frontTeeth = rideData.drivetrain.frontTeeth,
        rear = rideData.drivetrain.rear,
        rearCount = rideData.drivetrain.rearCount,
        rearTeeth = rideData.drivetrain.rearTeeth,
        icon = R.drawable.ic_gears,
    )

    /**
     * Rang sous la carte : distance parcourue, distance restante, pente instantanée.
     *
     * La distance restante y a rejoint la parcourue : les deux se lisent ensemble — où j'en
     * suis, ce qui reste — et le rang du bas, qu'elle occupait, est allé tout entier à la
     * bande du soir.
     */
    private fun midTiles(context: Context, units: Units, rideData: RideData): List<Tile> = listOf(
        Tile(
            label = context.getString(R.string.dashboard_label_distance),
            value = rideData.distance?.let { remainingValue(it, units) } ?: PLACEHOLDER,
            icon = R.drawable.ic_distance,
        ).splitDecimal(),
        Tile(
            label = context.getString(
                R.string.dashboard_label_remaining,
                remainingUnit(units).uppercase(),
            ),
            value = rideData.distanceRemaining?.let { remainingValue(it, units) } ?: PLACEHOLDER,
            icon = R.drawable.ic_distance_remaining,
        ).splitDecimal(),
        Tile(
            label = context.getString(R.string.dashboard_label_grade),
            value = rideData.grade?.roundToInt()?.toString() ?: PLACEHOLDER,
            suffix = "%",
            icon = R.drawable.ic_grade,
        ),
    )

    /**
     * Ligne du bas : l'heure d'arrivée, seulement si la bande « Avant la nuit » ne la porte
     * pas déjà sur sa frise. Vide sinon — la bande prend tout le rang.
     */
    private fun footerTiles(
        context: Context,
        rideData: RideData,
        state: GuidanceState,
        nowMillis: Long,
        withArrival: Boolean,
    ): List<Tile> {
        if (!withArrival) return emptyList()

        val estimate = FieldModels.arrival(state, rideData)
        // À défaut d'allure apprise, l'heure du Karoo : elle vaut mieux qu'un tiret.
        val arrival = estimate
            ?.let { nowMillis + (it.seconds * 1_000).toLong() }
            ?.toDouble()
            ?: rideData.arrivalTime
        // La marge ne s'écrit qu'à partir de la minute. En dessous, elle dirait « ± 0 »,
        // ce qui promet une précision que rien ne garantit — et le libellé, partagé avec
        // celui d'à côté, rétrécit les deux quand il s'allonge.
        val margin = estimate
            ?.let { (it.marginSeconds / 60.0).roundToInt() }
            ?.takeIf { it >= 1 }

        return listOf(
            Tile(
                label = margin
                    ?.let { context.getString(R.string.dashboard_label_arrival_margin, it) }
                    ?: context.getString(R.string.dashboard_label_arrival),
                value = arrival?.let { Format.clock(it) } ?: PLACEHOLDER,
                icon = R.drawable.ic_arrival,
            ),
        )
    }

    /**
     * Détache la décimale, écrite ensuite en exposant et sans séparateur.
     *
     * « 38,5 » devient « 38 » et « 5 » : les chiffres qui portent l'information gardent leur
     * pleine hauteur, et la virgule cesse d'occuper la largeur d'un chiffre pour ne rien
     * dire — la décimale se reconnaît déjà à sa taille et à sa position.
     */
    private fun Tile.splitDecimal(): Tile {
        val separator = value.indexOfLast { it == '.' || it == ',' }
        if (separator < 0 || separator == value.lastIndex) return this
        return copy(value = value.substring(0, separator), decimal = value.substring(separator + 1))
    }

    /**
     * Distance restante sans son unité. Au-delà de 100, la décimale est abandonnée :
     * « 123 » tient dans la case là où « 123,4 » obligerait à rapetisser les chiffres.
     */
    private fun remainingValue(meters: Double, units: Units): String {
        val value = when (units) {
            Units.METRIC -> meters / 1_000
            Units.IMPERIAL -> meters / METERS_PER_MILE
        }
        return if (value >= 100) {
            value.roundToInt().toString()
        } else {
            Format.longDistance(meters, units).substringBefore(' ')
        }
    }

    private fun remainingUnit(units: Units): String = when (units) {
        Units.METRIC -> "km"
        Units.IMPERIAL -> "mi"
    }

    private const val PLACEHOLDER = "--"
    private const val METERS_PER_MILE = 1609.344

    /** Rayon de lecture du fond de carte, en multiples de la portée affichée. */
    private const val ROADS_RADIUS_FACTOR = 1.6
}
