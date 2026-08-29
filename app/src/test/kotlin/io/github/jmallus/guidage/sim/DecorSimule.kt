package io.github.jmallus.guidage.sim

import io.github.jmallus.guidage.core.Geo
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.core.map.RoadSurface
import io.github.jmallus.guidage.core.map.toMicroDegrees
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/**
 * Un fond de carte engendré, qui couvre tout le parcours du simulateur.
 *
 * Le décor de l'aperçu tient dans un carré de neuf cents mètres autour de son point de
 * départ : passé la première minute, le coureur simulé en sort et roule sur du noir. Or c'est
 * précisément le fond qui donne son sens au couloir — sans voies qui s'éteignent en s'écartant
 * du tracé, il n'y a rien à juger.
 *
 * Le décor est donc calculé à la demande, maille par maille, à partir d'un bruit déterministe :
 * la même maille rend toujours les mêmes voies. C'est indispensable — un fond qui se
 * redistribuerait à chaque image serait illisible — et cela évite de tenir en mémoire une
 * carte entière.
 *
 * Ce n'est pas une carte de la Normandie : les vraies voies ne sont ni régulières ni
 * également réparties. C'est un décor de contrôle, fait pour exercer toutes les classes de
 * voies et toutes les familles de surfaces à toutes les portées.
 */
class DecorSimule(private val origine: GeoPoint, trace: List<GeoPoint> = emptyList()) {

    /**
     * La voie que l'itinéraire emprunte, découpée en portions de classes différentes.
     *
     * Sans elle, le décor ne rencontre le tracé que par hasard, aux croisements : le champ
     * « Revêtement » apparie alors une poignée d'échantillons sur cent et conclut « aucune
     * voie reconnue », ce qui est vrai du décor mais faux de tout parcours réel — un
     * itinéraire suit toujours quelque chose. La voie est donc posée **sur** le tracé, et le
     * découpage rejoue une sortie de gravel ordinaire : du bitume, un long chemin, une voie
     * verte, du bitume encore.
     */
    private val voieSuivie: List<PortionSuivie> = decouper(trace)

    /** Une portion de la voie suivie et son emprise, calculée une fois pour toutes. */
    private class PortionSuivie(
        val voie: RoadSegment,
        val ouest: Double,
        val est: Double,
        val sud: Double,
        val nord: Double,
    )

    /** Les voies visibles dans un rayon de [rayonMetres] autour de [centre]. */
    fun autour(centre: GeoPoint, rayonMetres: Double): List<RoadSegment> {
        val plan = Geo.project(origine, centre)
        val premiereColonne = floor((plan.x - rayonMetres) / MAILLE).toInt()
        val derniereColonne = ceil((plan.x + rayonMetres) / MAILLE).toInt()
        val premiereLigne = floor((plan.y - rayonMetres) / MAILLE).toInt()
        val derniereLigne = ceil((plan.y + rayonMetres) / MAILLE).toInt()

        val voies = ArrayList<RoadSegment>()

        // Les surfaces d'abord : le rendu les dessine dans son propre ordre, mais les tenir
        // groupées ici rend le décor plus facile à relire.
        for (colonne in premiereColonne until derniereColonne) {
            for (ligne in premiereLigne until derniereLigne) {
                voies += surfaceDe(colonne, ligne)
                voies += chemins(colonne, ligne)
            }
        }

        // Puis les voies, chacune continue d'une maille à l'autre : leur sinuosité ne dépend
        // que de la ligne qu'elles suivent, jamais de la maille où on les regarde.
        for (colonne in premiereColonne..derniereColonne) {
            voies += routeNordSud(colonne, premiereLigne, derniereLigne)
        }
        for (ligne in premiereLigne..derniereLigne) {
            voies += routeEstOuest(ligne, premiereColonne, derniereColonne)
        }

        // La voie suivie en dernier : à égale distance, c'est elle qui doit l'emporter, et
        // l'appariement du champ « Revêtement » retient le plus proche à distance égale.
        voieSuivie.forEach { portion ->
            val dedans = portion.ouest <= plan.x + rayonMetres && portion.est >= plan.x - rayonMetres &&
                portion.sud <= plan.y + rayonMetres && portion.nord >= plan.y - rayonMetres
            if (dedans) voies += portion.voie
        }
        return voies
    }

    /* ------------------------------------------------------------- voie suivie */

    /**
     * Découpe le tracé en portions, chacune d'une classe.
     *
     * Les bornes sont écrites plutôt que tirées au sort : on veut voir à l'écran une bascule
     * proche — pour juger l'annonce « CHEMIN DANS 400 m » — et une longue portion de chemin,
     * pour juger la barre. Un tirage ne le garantirait pas.
     */
    private fun decouper(trace: List<GeoPoint>): List<PortionSuivie> {
        if (trace.size < 2) return emptyList()
        val portions = ArrayList<PortionSuivie>(BORNES.size + 1)
        var distance = 0.0
        var courante = ArrayList<GeoPoint>().apply { add(trace.first()) }
        var rang = 0
        for (index in 1 until trace.size) {
            courante += trace[index]
            distance += Geo.distance(trace[index - 1], trace[index])
            val borne = BORNES.getOrNull(rang) ?: continue
            if (distance >= borne.first) {
                portions += portionSuivie(courante, borne.second)
                // La portion suivante repart du dernier point, sans quoi l'appariement
                // trouverait un trou d'une longueur de pas à chaque changement de classe.
                courante = ArrayList<GeoPoint>().apply { add(trace[index]) }
                rang++
            }
        }
        if (courante.size >= 2) portions += portionSuivie(courante, CLASSE_FINALE)
        return portions
    }

    private fun portionSuivie(points: List<GeoPoint>, kind: RoadKind): PortionSuivie {
        val plan = points.map { Geo.project(origine, it) }
        return PortionSuivie(
            voie = RoadSegment(
                kind = kind,
                surface = if (kind.isTrail) RoadSurface.UNPAVED else RoadSurface.PAVED,
                latitudes = points.map { it.lat.toMicroDegrees() }.toIntArray(),
                longitudes = points.map { it.lng.toMicroDegrees() }.toIntArray(),
            ),
            ouest = plan.minOf { it.x },
            est = plan.maxOf { it.x },
            sud = plan.minOf { it.y },
            nord = plan.maxOf { it.y },
        )
    }

    /* ------------------------------------------------------------------- voies */

    private fun routeNordSud(colonne: Int, premiereLigne: Int, derniereLigne: Int): RoadSegment {
        val points = (premiereLigne..derniereLigne).map { ligne ->
            val x = colonne * MAILLE + SINUOSITE * (bruit(colonne, ligne, SEL_SINUOSITE) - 0.5)
            x to ligne * MAILLE
        }
        return voie(classeDe(colonne, SEL_CLASSE_NORD_SUD), points)
    }

    private fun routeEstOuest(ligne: Int, premiereColonne: Int, derniereColonne: Int): RoadSegment {
        val points = (premiereColonne..derniereColonne).map { colonne ->
            val y = ligne * MAILLE + SINUOSITE * (bruit(colonne, ligne, SEL_SINUOSITE + 1) - 0.5)
            colonne * MAILLE to y
        }
        return voie(classeDe(ligne, SEL_CLASSE_EST_OUEST), points)
    }

    /**
     * La classe d'une voie, tirée de son rang.
     *
     * Une départementale toutes les cinq lignes, une communale toutes les deux, le reste en
     * voies de desserte : à peu près la hiérarchie d'une campagne, et de quoi voir à l'écran
     * que le rang se lit à la seule épaisseur.
     */
    private fun classeDe(rang: Int, sel: Int): RoadKind {
        val tirage = bruit(rang, sel, SEL_CLASSE)
        return when {
            Math.floorMod(rang, 7) == 0 -> RoadKind.PRIMARY
            Math.floorMod(rang, 5) == 0 -> RoadKind.SECONDARY
            Math.floorMod(rang, 3) == 0 -> RoadKind.TERTIARY
            tirage < 0.35 -> RoadKind.RESIDENTIAL
            else -> RoadKind.UNCLASSIFIED
        }
    }

    /** Chemins et sentiers en travers de la maille : l'ordinaire du gravel. */
    private fun chemins(colonne: Int, ligne: Int): List<RoadSegment> {
        val tirage = bruit(colonne, ligne, SEL_CHEMIN)
        if (tirage > 0.55) return emptyList()
        val kind = if (tirage < 0.25) RoadKind.TRACK else RoadKind.PATH
        val x = colonne * MAILLE
        val y = ligne * MAILLE
        return listOf(
            voie(
                kind,
                listOf(
                    x to y,
                    x + MAILLE * 0.45 to y + MAILLE * 0.30,
                    x + MAILLE to y + MAILLE,
                ),
                RoadSurface.UNPAVED,
            ),
        )
    }

    /* ---------------------------------------------------------------- surfaces */

    private fun surfaceDe(colonne: Int, ligne: Int): List<RoadSegment> {
        val tirage = bruit(colonne, ligne, SEL_SURFACE)
        val kind = when {
            tirage < 0.42 -> RoadKind.FARMLAND
            tirage < 0.70 -> RoadKind.FOREST
            tirage < 0.82 -> RoadKind.BUILT_UP
            else -> return emptyList()
        }
        // La surface est rentrée dans sa maille : jointive, elle pavait l'écran d'un seul
        // aplat et l'on ne distinguait plus les familles les unes des autres.
        val marge = MAILLE * (0.10 + 0.12 * bruit(colonne, ligne, SEL_MARGE))
        val x = colonne * MAILLE + marge
        val y = ligne * MAILLE + marge
        val cote = MAILLE - 2 * marge
        val contour = listOf(
            x to y,
            x + cote to y,
            x + cote to y + cote,
            x to y + cote,
        )
        val surface = voie(kind, contour)

        // Un étang de loin en loin, et le ruisseau qui le traverse. Son tirage est
        // indépendant de celui de la surface, sans quoi il ne tomberait que sur une famille.
        if (bruit(colonne, ligne, SEL_EAU) < 0.10) {
            val etang = voie(
                RoadKind.WATER,
                listOf(
                    x + cote * 0.2 to y + cote * 0.3,
                    x + cote * 0.6 to y + cote * 0.2,
                    x + cote * 0.7 to y + cote * 0.6,
                    x + cote * 0.3 to y + cote * 0.7,
                ),
            )
            val ruisseau = voie(
                RoadKind.STREAM,
                listOf(x to y + cote * 0.5, x + cote * 0.3 to y + cote * 0.55, x + cote to y + cote * 0.4),
            )
            return listOf(surface, etang, ruisseau)
        }
        return listOf(surface)
    }

    /* ------------------------------------------------------------- conversions */

    /** Une voie donnée en mètres à l'est et au nord de [origine]. */
    private fun voie(
        kind: RoadKind,
        points: List<Pair<Double, Double>>,
        surface: RoadSurface = RoadSurface.UNKNOWN,
    ) = RoadSegment(
        kind = kind,
        surface = surface,
        latitudes = points.map { latitudeDe(it.second) }.toIntArray(),
        longitudes = points.map { longitudeDe(it.first) }.toIntArray(),
    )

    private fun latitudeDe(metresNord: Double): Int =
        (origine.lat + metresNord / Geo.METERS_PER_DEGREE_LATITUDE).toMicroDegrees()

    private fun longitudeDe(metresEst: Double): Int {
        val parDegre = Geo.METERS_PER_DEGREE_LONGITUDE * cos(Math.toRadians(origine.lat))
        return (origine.lng + metresEst / parDegre).toMicroDegrees()
    }

    /**
     * Bruit déterministe entre 0 et 1.
     *
     * Un générateur aléatoire ordinaire ne conviendrait pas : le décor est recalculé à chaque
     * image, et il doit rendre exactement les mêmes voies d'une image à l'autre. Ici, la même
     * maille et le même sel rendent toujours la même valeur.
     */
    private fun bruit(colonne: Int, ligne: Int, sel: Int): Double {
        var melange = colonne * 374_761_393 + ligne * 668_265_263 + sel * 1_274_126_177
        melange = (melange xor (melange shr 13)) * 1_274_126_177
        melange = melange xor (melange shr 16)
        return (melange and 0x7fff_ffff) / 0x7fff_ffff.toDouble()
    }

    private companion object {
        /**
         * Là où la voie suivie change de classe, et ce qu'elle devient.
         *
         * Écrit pour exercer le champ : une bascule à trois kilomètres et demi — assez près
         * pour que l'annonce s'affiche au départ —, un long chemin, une voie verte, et de
         * quoi alterner jusqu'à l'arrivée. Au-delà de la dernière borne, [CLASSE_FINALE].
         */
        val BORNES = listOf(
            3_500.0 to RoadKind.SECONDARY,
            7_200.0 to RoadKind.TRACK,
            10_000.0 to RoadKind.TERTIARY,
            12_400.0 to RoadKind.CYCLEWAY,
            17_500.0 to RoadKind.SECONDARY,
            21_000.0 to RoadKind.TRACK,
            25_000.0 to RoadKind.TERTIARY,
            27_500.0 to RoadKind.TRACK,
            29_500.0 to RoadKind.CYCLEWAY,
        )

        val CLASSE_FINALE = RoadKind.SECONDARY

        /** Côté d'une maille du décor (m) : l'écart habituel entre deux routes de campagne. */
        const val MAILLE = 420.0

        /** Amplitude du serpentement d'une voie d'une maille à l'autre (m). */
        const val SINUOSITE = 90.0

        const val SEL_SINUOSITE = 1
        const val SEL_CLASSE = 2
        const val SEL_CLASSE_NORD_SUD = 3
        const val SEL_CLASSE_EST_OUEST = 4
        const val SEL_CHEMIN = 5
        const val SEL_SURFACE = 6
        const val SEL_MARGE = 7
        const val SEL_EAU = 8
    }
}
