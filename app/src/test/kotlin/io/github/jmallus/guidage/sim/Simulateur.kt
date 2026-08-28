package io.github.jmallus.guidage.sim

import android.content.Context
import android.graphics.Bitmap
import io.github.jmallus.guidage.core.ClimbProgress
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.GuidanceZoneType
import io.github.jmallus.guidage.core.LearnedPace
import io.github.jmallus.guidage.core.MapZoom
import io.github.jmallus.guidage.core.PaceLearner
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.extension.BendModels
import io.github.jmallus.guidage.extension.ContextModels
import io.github.jmallus.guidage.extension.DashboardModels
import io.github.jmallus.guidage.extension.EffortModels
import io.github.jmallus.guidage.extension.FieldModels
import io.github.jmallus.guidage.extension.SurfaceModels
import io.github.jmallus.guidage.extension.RoadSource
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.karoo.RiderLocation
import io.github.jmallus.guidage.settings.GuidageSettings
import io.github.jmallus.guidage.ui.BendRenderer
import io.github.jmallus.guidage.ui.ClimbRenderer
import io.github.jmallus.guidage.ui.DashboardModel
import io.github.jmallus.guidage.ui.DashboardRenderer
import io.github.jmallus.guidage.ui.ContextRenderer
import io.github.jmallus.guidage.ui.EffortRenderer
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.PreviewData
import io.github.jmallus.guidage.ui.ProfileRenderer
import io.github.jmallus.guidage.ui.SurfaceRenderer
import io.hammerhead.karooext.models.ViewConfig

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

    /**
     * L'allure du coureur fictif, apprise par le vrai code d'apprentissage.
     *
     * Le banc d'essai ne fabrique pas une allure toute faite : il rejoue la sortie pas à
     * pas dans [PaceLearner], comme le ferait l'appareil. C'est ce qui permet d'y voir ce
     * qu'on veut y voir — le libellé d'arrivée sans sa marge pendant les trois premières
     * minutes de sortie, puis avec, et la marge qui se resserre en approchant.
     *
     * L'apprentissage n'avance que par pas entiers : l'état ne dépend alors que du nombre
     * de pas franchis, et non du rythme auquel les images sont demandées.
     */
    private val apprentissage = PaceLearner()
    private var appriseJusqua = 0.0

    private fun allure(secondes: Double): LearnedPace {
        if (secondes < appriseJusqua) {
            apprentissage.reset()
            appriseJusqua = 0.0
        }
        while (appriseJusqua + PAS_APPRENTISSAGE <= secondes) {
            val instant = sortie.a(appriseJusqua + PAS_APPRENTISSAGE)
            apprentissage.observe(PAS_APPRENTISSAGE, instant.vitesse, instant.pente)
            appriseJusqua += PAS_APPRENTISSAGE
        }
        return apprentissage.pace
    }

    /**
     * L'état de la sortie à cet instant, tel que l'extension le recevrait du Karoo.
     *
     * Les trois champs graphiques en partent tous : ils doivent montrer le même instant, sinon
     * les comparer n'apprend rien — et c'est pour les comparer qu'ils sont côte à côte.
     */
    private fun instantane(secondes: Double): GuidanceSnapshot {
        val instant = sortie.a(secondes)
        val maintenant = departMillis + (secondes * 1_000).toLong()
        val etat = GuidanceState(
            route = PreviewData.route,
            distanceAlongRoute = instant.distance,
            distanceRemaining = instant.distanceRestante,
            currentGrade = instant.pente,
        )
        // Le point est daté de l'instant courant : l'extrapolation qui rattrape le retard du
        // GPS n'a donc rien à rattraper. C'est voulu — le simulateur montre la trajectoire
        // telle qu'elle est, non telle qu'on la devine.
        return GuidanceSnapshot(
            state = etat,
            units = Units.METRIC,
            location = RiderLocation(instant.position, instant.cap, receivedAtMillis = maintenant),
        )
    }

    private fun reglages() = GuidageSettings(guidanceZone = zone, mapZoom = portee)

    /** Le champ « Profil à venir », avec son échelle comprimée au loin. */
    fun imageProfil(
        secondes: Double,
        largeur: Int = LARGEUR,
        hauteur: Int = HAUTEUR_PROFIL,
    ): Bitmap = ProfileRenderer.render(
        largeur,
        hauteur,
        FieldModels.profile(context, instantane(secondes), reglages(), preview = false),
        FieldPalette.of(context),
    )

    /** Le champ « Prochaine côte ». */
    fun imageCote(
        secondes: Double,
        largeur: Int = LARGEUR_ANNEXE,
        hauteur: Int = HAUTEUR_COTE,
    ): Bitmap = ClimbRenderer.render(
        largeur,
        hauteur,
        FieldModels.climb(context, instantane(secondes), preview = false),
        ViewConfig.Alignment.RIGHT,
        FieldPalette.of(context),
    )

    /**
     * La côte en cours, telle que le Karoo la rapporterait.
     *
     * Le champ « Suivant la sortie » bascule dessus : sans elle il ne passerait jamais en
     * montée, et la moitié de ce qu'il sait faire resterait invisible au banc d'essai.
     */
    private fun montee(distance: Double): ClimbProgress {
        val status = Guidance.climbStatus(PreviewData.route, distance) ?: return ClimbProgress.NONE
        if (!status.onClimb) return ClimbProgress.NONE
        return ClimbProgress(
            distanceFromBottom = distance - status.climb.startDistance,
            distanceToTop = status.distanceToTop,
            elevationToTop = status.elevationToTop,
            totalElevation = status.climb.totalElevation,
            number = status.number,
            totalClimbs = status.totalClimbs,
        )
    }

    /** Les relevés de la sortie fictive, tels que le Karoo les rapporterait. */
    private fun releve(secondes: Double): RideData {
        val instant = sortie.a(secondes)
        val maintenant = departMillis + (secondes * 1_000).toLong()
        return RideData(
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
            pace = allure(secondes),
            climb = montee(instant.distance),
        )
    }

    /*
     * Les quatre champs venus des vues proposées. Ils passent par les mêmes constructions de
     * modèle que l'appareil — extraites dans `*Models` — et par les mêmes rendus.
     */

    private val virages = BendModels()
    private val contexte = ContextModels { instantSimule }
    private val revetement = SurfaceModels { position, rayon -> decor.autour(position, rayon) }

    /**
     * L'instant de la sortie fictive, vu comme une horloge.
     *
     * Le champ « Suivant la sortie » ne bascule pas d'un coup : il attend qu'un état se
     * confirme, et compte pour cela le temps écoulé entre deux images. Lui donner l'heure de
     * la machine ferait basculer le champ à la vitesse du banc d'essai, non à celle de la
     * sortie qu'il joue — à huit fois la vitesse réelle, une hystérésis de trois secondes en
     * durerait moins d'une demie.
     */
    private var instantSimule: Long = departMillis

    private fun horlogeA(secondes: Double) {
        instantSimule = departMillis + (secondes * 1_000).toLong()
    }

    /** Le champ « Budget d'effort ». */
    fun imageEffort(
        secondes: Double,
        largeur: Int = LARGEUR_ANNEXE,
        hauteur: Int = HAUTEUR_ANNEXE,
    ): Bitmap = EffortRenderer.render(
        largeur,
        hauteur,
        EffortModels.build(context, instantane(secondes).state, releve(secondes), preview = false),
        FieldPalette.of(context),
    )

    /** Le champ « Virages ». */
    fun imageVirages(
        secondes: Double,
        largeur: Int = LARGEUR_ANNEXE,
        hauteur: Int = HAUTEUR_ANNEXE,
    ): Bitmap = BendRenderer.render(
        largeur,
        hauteur,
        virages.build(context, instantane(secondes), preview = false),
        FieldPalette.of(context),
    )

    /** Le champ « Suivant la sortie ». */
    fun imageContexte(
        secondes: Double,
        largeur: Int = LARGEUR,
        hauteur: Int = HAUTEUR_PROFIL,
    ): Bitmap {
        horlogeA(secondes)
        return ContextRenderer.render(
            largeur,
            hauteur,
            contexte.build(context, instantane(secondes), releve(secondes), preview = false),
            FieldPalette.of(context),
        )
    }

    /** Le champ « Revêtement ». */
    fun imageRevetement(
        secondes: Double,
        largeur: Int = LARGEUR,
        hauteur: Int = HAUTEUR_ANNEXE,
    ): Bitmap = SurfaceRenderer.render(
        largeur,
        hauteur,
        revetement.build(context, instantane(secondes), preview = false),
        FieldPalette.of(context),
    )

    fun modele(secondes: Double): DashboardModel {
        val maintenant = departMillis + (secondes * 1_000).toLong()
        return DashboardModels.build(
            context = context,
            snapshot = instantane(secondes),
            rideData = releve(secondes),
            settings = reglages(),
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
        /** Pas d'apprentissage de l'allure (s). */
        private const val PAS_APPRENTISSAGE = 2.0

        /**
         * La largeur laissée au champ.
         *
         * Deux points de moins que les quatre cent quatre-vingts de l'écran : le système
         * garde un liseré de chaque côté.
         */
        const val LARGEUR = 478

        /**
         * La hauteur laissée au champ, bandeau d'état déduit.
         *
         * L'écran fait huit cents points, mais le Karoo en garde cent cinquante-huit en haut
         * pour l'heure et la batterie : le champ n'a jamais toute la hauteur, et il s'en faut
         * de près d'un cinquième. Sur l'appareil la question ne se pose pas —
         * `ViewConfig.viewSize` donne la place réellement allouée, et le rendu s'y ajuste.
         * Ici il faut la connaître, et le banc d'essai l'a longtemps ignorée : il montrait
         * une mise en page plus haute que la vraie, donc des rangs plus espacés et des
         * chiffres plus grands que ce qu'on lira en roulant.
         *
         * Six cent quarante-deux est un relevé, non une estimation : c'est ce que le Karoo 3
         * a répondu à un champ plein écran, lu dans la carte « Place allouée au champ » de
         * l'écran de configuration. La propriété reste, pour un autre appareil ou un autre
         * gabarit de page : `./gradlew :app:simulateur -Pguidage.hauteur=744`
         */
        val HAUTEUR: Int =
            System.getProperty(PROPRIETE_HAUTEUR)?.toIntOrNull()?.takeIf { it > 0 } ?: HAUTEUR_CHAMP

        /** La hauteur relevée sur l'appareil pour un champ occupant toute la grille. */
        private const val HAUTEUR_CHAMP = 642

        /**
         * Les tailles des champs annexes, déduites de la grille et non relevées.
         *
         * Le Karoo découpe l'écran sur une grille de soixante : un champ pleine largeur sur un
         * quart de hauteur vaut 60 × 15, une demi-largeur 30 × 15. Ces valeurs s'en déduisent
         * de la seule mesure qu'on ait — les 478 × 642 du plein écran — et ce sont donc des
         * approximations, à la différence de celle-là.
         *
         * Les relever ne coûte rien maintenant : poser ces deux champs sur une page, l'ouvrir,
         * et lire la carte « Place allouée au champ » de l'application, qui note désormais
         * chaque champ graphique séparément.
         */
        const val LARGEUR_ANNEXE = LARGEUR / 2

        /** Un champ pleine largeur sur un quart de hauteur : 60 × 15 sur la grille. */
        val HAUTEUR_PROFIL: Int = HAUTEUR / 4

        /** Un champ demi-largeur sur un quart de hauteur : 30 × 15. */
        val HAUTEUR_COTE: Int = HAUTEUR / 4

        /** La hauteur des autres champs annexes, sur le même quart de grille. */
        val HAUTEUR_ANNEXE: Int = HAUTEUR / 4

        /**
         * Le corps que le Karoo emploie lui-même pour un champ numérique de cette taille.
         *
         * Il ne sert encore à rien dans le rendu : il est noté ici parce que c'est la seule
         * référence typographique qui vienne de l'appareil, et non d'une maquette.
         */
        const val CORPS_NATIF_SP = 96

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
