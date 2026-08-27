package io.github.jmallus.guidage.sim

import android.content.Context
import android.graphics.Bitmap
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.GuidanceZoneType
import io.github.jmallus.guidage.core.MapZoom
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.extension.DashboardModels
import io.github.jmallus.guidage.extension.RoadSource
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RiderLocation
import io.github.jmallus.guidage.settings.GuidageSettings
import io.github.jmallus.guidage.ui.DashboardModel
import io.github.jmallus.guidage.ui.DashboardRenderer
import io.github.jmallus.guidage.ui.PreviewData

/**
 * Le tableau de bord de l'extension, joué sur une sortie simulée.
 *
 * Il ne redessine rien : il assemble l'état d'une sortie fictive puis appelle le **vrai**
 * constructeur de modèle et le **vrai** rendu, ceux-là mêmes que le champ Karoo exécute sur
 * l'appareil. Ce qu'on voit ici n'est donc pas une seconde écriture de l'affichage — le
 * défaut connu des planches, portées en JavaScript — mais le code qui partira dans l'APK.
 *
 * Ce qu'il ne simule pas : la chaîne karoo-ext elle-même (souscriptions, aplatissement des
 * flux, cadence de rafraîchissement du système) et la carte hors ligne réelle, remplacée par
 * un décor engendré. Tout le reste — mise en page, couleurs, zones, couloir, chevrons,
 * bandeau de côte — est celui de l'appareil.
 */
class Simulateur(
    private val context: Context,
    /** Instant de départ de la sortie fictive, dont se déduit l'heure d'arrivée affichée. */
    private val departMillis: Long = DEPART_PAR_DEFAUT,
) {
    val sortie = SortieSimulee(PreviewData.route)

    private val decor = DecorSimule(PreviewData.location.position)

    /**
     * Zones d'effort, empruntées aux relevés de l'aperçu.
     *
     * Sans zones réglées, les aplats de couleur disparaissent et le numéro de zone à gauche
     * de la fréquence cardiaque ne s'écrit pas : le simulateur ne montrerait alors qu'une
     * moitié de ce que voit un coureur équipé.
     */
    private val zones = PreviewData.rideSamples(departMillis).first()

    /** Portée de la minicarte, que l'appui sur le champ fait tourner sur l'appareil. */
    var portee: MapZoom = MapZoom.NEAR

    /** Carte ou profil, comme le réglage de l'extension. */
    var zone: GuidanceZoneType = GuidanceZoneType.MAP

    /** Pour voir passer le tracé au rouge, comme lorsque le Karoo décroche de l'itinéraire. */
    var horsItineraire: Boolean = false

    private val source = object : RoadSource {
        override fun roads(position: GeoPoint?, radiusMeters: Double): List<RoadSegment> =
            position?.let { decor.autour(it, radiusMeters) }.orEmpty()

        /** Le décor est toujours là : il n'y a jamais rien à expliquer au coureur. */
        override fun notice(context: Context, position: GeoPoint?): String? = null
    }

    fun modele(secondes: Double): DashboardModel {
        val instant = sortie.a(secondes)
        val maintenant = departMillis + (secondes * 1_000).toLong()

        val releve = RideData(
            speed = instant.vitesse,
            averageSpeed = instant.vitesseMoyenne,
            power = instant.puissance,
            heartRate = instant.cardiaque,
            cadence = instant.cadence,
            grade = instant.pente,
            distance = instant.distance,
            distanceRemaining = instant.distanceRestante,
            arrivalTime = arrivee(instant, maintenant),
            drivetrain = instant.transmission,
            onRoute = !horsItineraire,
            powerZones = zones.powerZones,
            heartRateZones = zones.heartRateZones,
        )

        val etat = GuidanceState(
            route = PreviewData.route,
            distanceAlongRoute = instant.distance,
            distanceRemaining = instant.distanceRestante,
            currentGrade = instant.pente,
        )

        // Le point est daté de l'instant courant : l'extrapolation qui rattrape le retard du
        // GPS n'a donc rien à rattraper. C'est voulu — le simulateur montre la trajectoire
        // telle qu'elle est, non telle qu'on la devine.
        val instantane = GuidanceSnapshot(
            state = etat,
            units = Units.METRIC,
            location = RiderLocation(instant.position, instant.cap, receivedAtMillis = maintenant),
        )

        return DashboardModels.build(
            context = context,
            snapshot = instantane,
            rideData = releve,
            settings = GuidageSettings(guidanceZone = zone, mapZoom = portee),
            preview = false,
            roadSource = source,
            nowMillis = maintenant,
        )
    }

    fun image(secondes: Double, largeur: Int = LARGEUR, hauteur: Int = HAUTEUR): Bitmap =
        DashboardRenderer.render(context, largeur, hauteur, modele(secondes))

    /** Heure d'arrivée estimée : ce qui reste, à la moyenne tenue jusque-là. */
    private fun arrivee(instant: InstantSortie, maintenant: Long): Double {
        val moyenne = instant.vitesseMoyenne.coerceAtLeast(1.0)
        return maintenant + instant.distanceRestante / moyenne * 1_000.0
    }

    companion object {
        /** La largeur de l'écran du Karoo 3, que le champ occupe entièrement. */
        const val LARGEUR = 480

        /**
         * La hauteur laissée au champ, bandeau d'état déduit.
         *
         * L'écran fait huit cents points, mais le Karoo en garde une bande en haut pour
         * l'heure et la batterie : le champ n'a jamais toute la hauteur. Sur l'appareil, la
         * question ne se pose pas — `ViewConfig.viewSize` donne la place réellement allouée,
         * et le rendu s'y ajuste. Ici il faut la connaître, et le banc d'essai l'a longtemps
         * ignorée : il montrait une mise en page plus haute que la vraie, donc des rangs plus
         * espacés et des chiffres plus grands que ce qu'on lira en roulant.
         *
         * La valeur se règle sans recompiler, le temps de la relever sur l'appareil :
         * `./gradlew :app:simulateur -Pguidage.hauteur=744`
         */
        val HAUTEUR: Int =
            System.getProperty(PROPRIETE_HAUTEUR)?.toIntOrNull()?.takeIf { it > 0 } ?: HAUTEUR_ECRAN

        /** Hauteur totale de l'écran, faute de mieux tant que la bande n'est pas mesurée. */
        private const val HAUTEUR_ECRAN = 800

        const val PROPRIETE_HAUTEUR = "guidage.hauteur"

        /**
         * Un mardi de septembre à neuf heures moins le quart.
         *
         * L'instant est fixe et non « maintenant » : l'heure d'arrivée affichée fait partie de
         * ce qu'on juge, et une image de contrôle qui change d'heure à chaque exécution ne se
         * compare plus à la précédente.
         */
        const val DEPART_PAR_DEFAUT = 1_757_487_600_000L
    }
}
