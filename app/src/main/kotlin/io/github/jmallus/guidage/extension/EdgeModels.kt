package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.core.Zones
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.settings.GuidageSettings
import io.github.jmallus.guidage.ui.EdgeCase
import io.github.jmallus.guidage.ui.EdgeModel
import io.github.jmallus.guidage.ui.PreviewData
import kotlin.math.roundToInt

/**
 * Ce qu'affiche le Tableau Edge, repris du champ « Écran Edge » du coureur sur son Garmin.
 *
 * Extrait du champ pour la raison habituelle : le simulateur doit montrer ce que montrera
 * l'appareil, par la même construction.
 */
object EdgeModels {

    fun build(
        context: Context,
        snapshot: GuidanceSnapshot,
        rideData: RideData,
        settings: GuidageSettings,
        preview: Boolean,
        roadSource: RoadSource,
        nowMillis: Long = System.currentTimeMillis(),
    ): EdgeModel {
        val units = snapshot.units
        val state = if (preview) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }

        // Dans une côte, les six cases du bas cèdent la place au profil de montée — mais
        // seulement quand le ClimbPro du Karoo l'annonce. Notre propre repérage des côtes de
        // l'itinéraire, qui suffit au tableau de guidage, faisait basculer l'écran sur des
        // bosses que le Karoo ne compte pas : le coureur perdait ses six cases sans que rien
        // sur l'appareil ne dise qu'une montée commençait.
        val profil = if (rideData.climb.onClimb) {
            DashboardModels.profileBand(context, snapshot, settings, preview, rideData, nowMillis)
        } else {
            null
        }

        val puissance = rideData.power
        val coeur = rideData.heartRate
        // L'aperçu montre un virage type : sans lui, la case resterait sur « -- » dans le
        // sélecteur de champs, et le coureur ne verrait pas ce qu'elle donnera en roulant.
        val virage = if (preview) {
            VIRAGE_APERCU
        } else {
            GuidageModels.prochaineManoeuvre(state, snapshot.location, rideData.speed, roadSource, nowMillis)
                ?.let { it.carrefour.virage to it.distance }
        }
        val distanceVirage = virage?.let { Format.distance(it.second.coerceAtLeast(0.0), units) }
        return EdgeModel(
            vitesse = rideData.speed?.let { Format.speed(it, units) } ?: VIDE,
            vitesseMoyenne = rideData.averageSpeed?.let { Format.speed(it, units) } ?: VIDE,
            uniteVitesse = Format.speedUnit(units),
            puissance = EdgeCase(
                libelle = context.getString(R.string.edge_label_power),
                valeur = puissance?.roundToInt()?.toString() ?: VIDE,
                fond = puissance?.let { Zones.powerColor(it, rideData.powerZones) },
                badge = puissance?.let { badge(it, rideData.powerZones) },
            ),
            puissanceMoyenne = rideData.level.averagePower?.roundToInt()?.toString() ?: VIDE,
            coeur = EdgeCase(
                libelle = context.getString(R.string.edge_label_heart_rate),
                valeur = coeur?.roundToInt()?.toString() ?: VIDE,
                unite = "bpm",
                fond = coeur?.let { Zones.heartRateColor(it, rideData.heartRateZones) },
                badge = coeur?.let { badge(it, rideData.heartRateZones) },
            ),
            cadence = EdgeCase(
                libelle = context.getString(R.string.edge_label_cadence),
                valeur = rideData.cadence?.roundToInt()?.toString() ?: VIDE,
                unite = "rpm",
            ),
            bas = listOf(
                EdgeCase(
                    libelle = context.getString(R.string.edge_label_distance),
                    valeur = rideData.distance?.let { Format.longDistanceValue(it, units) } ?: VIDE,
                    unite = Format.longDistanceUnit(units),
                ),
                // Le D+ restant et non le D+ fait : le coureur l'a demandé à la place de
                // celui de l'Edge, qui redit ce que le Karoo enregistre. Celui du Karoo vaut
                // zéro sur certains itinéraires d'un bout à l'autre de la sortie : le profil
                // prend alors le relais, comme dans la case de bilan du dénivelé.
                EdgeCase(
                    libelle = context.getString(R.string.edge_label_elevation_remaining),
                    valeur = LevelModels.restantJusquALArrivee(rideData.level.elevationRemaining, state)
                        ?.let { denivele(it, units) } ?: VIDE,
                    unite = uniteDenivele(units),
                ),
                EdgeCase(
                    libelle = context.getString(R.string.edge_label_grade),
                    valeur = rideData.grade?.let { String.format(java.util.Locale.getDefault(), "%.1f", it) } ?: VIDE,
                    unite = "%",
                    fond = rideData.grade?.let { Zones.gradeColor(it) },
                    aDroite = true,
                ),
                EdgeCase(
                    libelle = context.getString(R.string.edge_label_remaining),
                    valeur = rideData.distanceRemaining?.let { Format.longDistanceValue(it, units) } ?: VIDE,
                    unite = Format.longDistanceUnit(units),
                ),
                // À la place de la température : le coureur préfère savoir où tourner. Le Karoo
                // ne donne pas ses instructions aux extensions ; le virage est celui que le
                // tableau de guidage déduit du tracé et du fond de carte, à moins d'un
                // kilomètre. Au-delà, ou hors de la zone couverte par la carte, « -- ».
                EdgeCase(
                    libelle = context.getString(R.string.edge_label_turn),
                    valeur = distanceVirage?.substringBeforeLast(' ') ?: VIDE,
                    unite = distanceVirage?.substringAfterLast(' '),
                    virage = virage?.first,
                ),
                // À la place du vent de l'Edge, que le Karoo ne publie pas.
                EdgeCase(
                    libelle = context.getString(R.string.edge_label_arrival),
                    valeur = rideData.arrivalTime?.takeIf { it > 0.0 }?.let { Format.clock(it) } ?: VIDE,
                ),
            ),
            profilCote = profil,
        )
    }

    /** « Z3 » : le numéro de zone, que l'Edge écrit en haut à droite de la case. */
    private fun badge(valeur: Double, zones: List<io.github.jmallus.guidage.core.ZoneRange>): String? =
        Zones.zoneOf(valeur, zones).takeIf { it > 0 }?.let { "Z$it" }

    private fun denivele(metres: Double, units: Units): String = when (units) {
        Units.METRIC -> metres.roundToInt().toString()
        Units.IMPERIAL -> (metres / METRES_PAR_PIED).roundToInt().toString()
    }

    private fun uniteDenivele(units: Units): String = if (units == Units.IMPERIAL) "ft" else "m"

    private const val VIDE = "--"

    /** Le virage de l'aperçu : à droite, à 350 m. */
    private val VIRAGE_APERCU = 90.0 to 350.0
    private const val METRES_PAR_PIED = 0.3048
}
