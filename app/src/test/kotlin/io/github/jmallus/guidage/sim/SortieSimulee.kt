package io.github.jmallus.guidage.sim

import io.github.jmallus.guidage.core.Drivetrain
import io.github.jmallus.guidage.core.Geo
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.Route
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Le coureur simulé : ce qu'il pèse, ce qu'il pousse, ce que bat son cœur.
 *
 * Les valeurs par défaut sont celles d'un cyclotouriste entraîné sur un vélo de route
 * chargé. Elles ne cherchent pas la vérité physiologique : il s'agit de produire des
 * chiffres qui bougent comme les vrais, pour juger de l'affichage.
 */
data class ProfilCoureur(
    /** Masse totale, coureur et machine (kg). */
    val masse: Double = 82.0,
    /** Puissance tenue en montée et sur le plat (W). */
    val puissance: Double = 205.0,
    /** Surface frontale effective, SCx (m²). */
    val penetration: Double = 0.32,
    /** Coefficient de résistance au roulement. */
    val roulement: Double = 0.005,
    /** Vitesse maximale consentie en descente (m/s) : au-delà, on freine. */
    val vitesseMaximale: Double = 15.0,
    /** Vitesse minimale : en deçà, on met pied à terre, ce qu'on ne simule pas (m/s). */
    val vitesseMinimale: Double = 1.4,
    val cardiaqueRepos: Double = 58.0,
    val cardiaqueMaximale: Double = 182.0,
    /** Cadence recherchée au moment de changer de vitesse (tr/min). */
    val cadenceChoisie: Double = 84.0,
    /** Plateaux, dans l'ordre où le groupe les numérote : le n° 1 est le petit. */
    val plateaux: List<Int> = listOf(34, 50),
    /**
     * Pignons, dans l'ordre où le groupe les numérote : le n° 1 est le **grand**, celui
     * qu'on prend pour monter. La liste descend donc, à rebours des plateaux.
     */
    val pignons: List<Int> = listOf(32, 28, 24, 21, 19, 17, 15, 14, 13, 12, 11),
)

/** Un instant de la sortie simulée, prêt à être versé dans le modèle du tableau de bord. */
data class InstantSortie(
    val secondes: Double,
    /** Distance depuis le départ, le long de l'itinéraire (m). */
    val distance: Double,
    val position: GeoPoint,
    /** Cap en degrés, 0 au nord. */
    val cap: Double,
    /** Vitesse instantanée (m/s). */
    val vitesse: Double,
    /** Vitesse moyenne depuis le départ (m/s). */
    val vitesseMoyenne: Double,
    /** Pente (%). */
    val pente: Double,
    val puissance: Double,
    val cardiaque: Double,
    val cadence: Double,
    val transmission: Drivetrain,
    /** Distance restant à parcourir jusqu'à l'arrivée (m). */
    val distanceRestante: Double,
)

/**
 * Une sortie entière, calculée d'avance, que le simulateur relit à la seconde qu'il veut.
 *
 * Elle est tabulée par pas de [PAS_METRES] plutôt qu'intégrée à la volée : la vitesse dépend
 * de la pente, donc de la distance, et une table permet de sauter n'importe où dans la sortie
 * — la frise du simulateur en dépend — sans rejouer ce qui précède.
 *
 * Le trajet suit la géométrie du tracé, mais les distances rendues sont celles de
 * l'itinéraire : le profil altimétrique, les côtes et les points d'intérêt sont repérés sur
 * `totalDistance`, qui ne coïncide pas exactement avec la longueur du polygone. Les deux sont
 * donc mis à l'échelle l'un de l'autre, faute de quoi le repère du bandeau de côte dériverait
 * du coureur au fil des kilomètres.
 */
class SortieSimulee(
    private val route: Route,
    private val profil: ProfilCoureur = ProfilCoureur(),
) {
    private val points: List<GeoPoint> = route.path
    private val cumulPolygone: DoubleArray = cumuler(points)
    private val longueurPolygone: Double = cumulPolygone.lastOrNull() ?: 0.0

    /** Rapport de la distance d'itinéraire à la distance géométrique du tracé. */
    private val echelle: Double =
        if (longueurPolygone > 0.0 && route.totalDistance > 0.0) {
            route.totalDistance / longueurPolygone
        } else {
            1.0
        }

    /**
     * Distance totale, telle que l'itinéraire la compte (m).
     *
     * Déclarée avant la table : celle-ci s'en sert pour savoir où s'arrêter, et un champ lu
     * avant sa propre initialisation vaudrait zéro sans que rien ne s'en plaigne.
     */
    val distanceTotale: Double = route.totalDistance

    private val etapes: List<Etape> = tabuler()

    /** Durée totale de la sortie (s). */
    val duree: Double = etapes.lastOrNull()?.secondes ?: 0.0

    fun a(secondes: Double): InstantSortie {
        require(etapes.size >= 2) { "un itinéraire d'au moins deux points est nécessaire" }
        val borne = secondes.coerceIn(0.0, duree)
        val index = chercher(borne)
        val avant = etapes[index - 1]
        val apres = etapes[index]
        val span = apres.secondes - avant.secondes
        val part = if (span <= 0.0) 0.0 else (borne - avant.secondes) / span

        val distance = avant.distance + part * (apres.distance - avant.distance)
        val vitesse = avant.vitesse + part * (apres.vitesse - avant.vitesse)
        val pente = avant.pente + part * (apres.pente - avant.pente)
        val cardiaque = avant.cardiaque + part * (apres.cardiaque - avant.cardiaque)
        val puissance = avant.puissance + part * (apres.puissance - avant.puissance)

        val surPolygone = distance / echelle
        val transmission = transmissionDe(vitesse)
        return InstantSortie(
            secondes = borne,
            distance = distance,
            position = positionSur(surPolygone),
            cap = capSur(surPolygone),
            vitesse = vitesse,
            vitesseMoyenne = if (borne > 1.0) distance / borne else vitesse,
            pente = pente,
            puissance = puissance,
            cardiaque = cardiaque,
            cadence = cadenceDe(vitesse, transmission),
            transmission = transmission,
            distanceRestante = (distanceTotale - distance).coerceAtLeast(0.0),
        )
    }

    /* ---------------------------------------------------------------- géométrie */

    private fun cumuler(points: List<GeoPoint>): DoubleArray {
        if (points.size < 2) return DoubleArray(points.size)
        val cumul = DoubleArray(points.size)
        for (index in 1 until points.size) {
            cumul[index] = cumul[index - 1] + Geo.distance(points[index - 1], points[index])
        }
        return cumul
    }

    private fun segmentA(distance: Double): Int {
        var bas = 1
        var haut = cumulPolygone.lastIndex
        while (bas < haut) {
            val milieu = (bas + haut) / 2
            if (cumulPolygone[milieu] < distance) bas = milieu + 1 else haut = milieu
        }
        return bas
    }

    private fun positionSur(distance: Double): GeoPoint {
        if (points.size < 2) return points.firstOrNull() ?: GeoPoint(0.0, 0.0)
        val borne = distance.coerceIn(0.0, longueurPolygone)
        val index = segmentA(borne)
        val avant = points[index - 1]
        val apres = points[index]
        val span = cumulPolygone[index] - cumulPolygone[index - 1]
        if (span <= 0.0) return apres
        val part = (borne - cumulPolygone[index - 1]) / span
        return GeoPoint(
            lat = avant.lat + part * (apres.lat - avant.lat),
            lng = avant.lng + part * (apres.lng - avant.lng),
        )
    }

    /**
     * Cap lissé sur [LISSAGE_CAP] mètres.
     *
     * Le cap d'un segment à l'autre saute d'un coup ; la carte, orientée cap en haut, ferait
     * alors un quart de tour d'une image à la suivante. Un vrai coureur — et son GPS — tourne
     * progressivement, et c'est ce qu'il faut donner à voir.
     */
    private fun capSur(distance: Double): Double {
        val depuis = positionSur((distance - LISSAGE_CAP).coerceAtLeast(0.0))
        val vers = positionSur((distance + LISSAGE_CAP).coerceAtMost(longueurPolygone))
        val plan = Geo.project(depuis, vers)
        val degres = Math.toDegrees(atan2(plan.x, plan.y))
        return (degres + 360.0) % 360.0
    }

    /* ------------------------------------------------------------------ effort */

    private data class Etape(
        val distance: Double,
        val secondes: Double,
        val vitesse: Double,
        val pente: Double,
        val puissance: Double,
        val cardiaque: Double,
    )

    private fun tabuler(): List<Etape> {
        if (points.size < 2 || distanceTotale <= 0.0) return emptyList()
        val etapes = ArrayList<Etape>((distanceTotale / PAS_METRES).toInt() + 2)
        var distance = 0.0
        var secondes = 0.0
        var cardiaque = profil.cardiaqueRepos + 20.0
        while (true) {
            val pente = penteA(distance)
            val vitesse = vitesseA(pente)
            val puissance = puissanceA(pente, vitesse)
            etapes += Etape(distance, secondes, vitesse, pente, puissance, cardiaque)
            if (distance >= distanceTotale) break

            val pas = minOf(PAS_METRES, distanceTotale - distance)
            val duree = pas / vitesse
            cardiaque = cardiaqueApres(cardiaque, puissance, duree)
            distance += pas
            secondes += duree
        }
        return etapes
    }

    private fun chercher(secondes: Double): Int {
        var bas = 1
        var haut = etapes.lastIndex
        while (bas < haut) {
            val milieu = (bas + haut) / 2
            if (etapes[milieu].secondes < secondes) bas = milieu + 1 else haut = milieu
        }
        return bas
    }

    /** Pente lue sur le profil de l'itinéraire, sur [LISSAGE_PENTE] mètres de part et d'autre. */
    private fun penteA(distance: Double): Double {
        val profilAltimetrique = route.profile ?: return 0.0
        val avant = profilAltimetrique.elevationAt((distance - LISSAGE_PENTE).coerceAtLeast(0.0))
        val apres = profilAltimetrique.elevationAt(
            (distance + LISSAGE_PENTE).coerceAtMost(distanceTotale),
        )
        if (avant == null || apres == null) return 0.0
        val course = (distance + LISSAGE_PENTE).coerceAtMost(distanceTotale) -
            (distance - LISSAGE_PENTE).coerceAtLeast(0.0)
        if (course <= 0.0) return 0.0
        return (apres - avant) / course * 100.0
    }

    /**
     * Puissance que le coureur veut tenir à cette pente.
     *
     * Elle ne peut pas être constante. Un coureur ne pousse pas dans une descente ce qu'il
     * pousse dans un mur : il se relève dès que la pente le porte, et il en met un peu plus
     * quand elle se dresse. La tenir constante serait plus simple, mais alors la puissance
     * affichée ne bougerait jamais, la fréquence cardiaque non plus, et les aplats de zone —
     * qui sont précisément ce qu'on vient juger — resteraient de la même couleur du départ à
     * l'arrivée. Le simulateur ne montrerait qu'une moitié de l'écran.
     */
    private fun puissanceVoulue(pente: Double): Double {
        val facteur = if (pente > 0) {
            val part = (pente / PENTE_PLEIN_EFFORT).coerceAtMost(1.0)
            EFFORT_PLAT + (EFFORT_COTE - EFFORT_PLAT) * part
        } else {
            val part = (pente / PENTE_ROUE_LIBRE).coerceIn(0.0, 1.0)
            EFFORT_PLAT + (EFFORT_DESCENTE - EFFORT_PLAT) * part
        }
        return profil.puissance * facteur
    }

    /**
     * Vitesse d'équilibre à une pente donnée.
     *
     * On résout par dichotomie la vitesse à laquelle la puissance voulue égale la somme des
     * résistances : pesanteur, roulement, air. C'est le modèle habituel, et il suffit
     * largement ici — l'inertie et le vent sont ignorés, l'affichage ne les montre pas.
     */
    private fun vitesseA(pente: Double): Double {
        val angle = atan(pente / 100.0)
        val voulue = puissanceVoulue(pente)
        var bas = 0.1
        var haut = 30.0
        repeat(40) {
            val milieu = (bas + haut) / 2
            if (resistance(angle, milieu) < voulue) bas = milieu else haut = milieu
        }
        return ((bas + haut) / 2).coerceIn(profil.vitesseMinimale, profil.vitesseMaximale)
    }

    private fun resistance(angle: Double, vitesse: Double): Double {
        val pesanteur = profil.masse * PESANTEUR * sin(angle) * vitesse
        val roulement = profil.roulement * profil.masse * PESANTEUR * cos(angle) * vitesse
        val air = 0.5 * DENSITE_AIR * profil.penetration * vitesse * vitesse * vitesse
        return pesanteur + roulement + air
    }

    /**
     * Puissance réellement produite, une fois la vitesse arrêtée.
     *
     * Elle vaut celle du coureur tant qu'il pédale ; en descente, la vitesse est bornée par
     * le freinage et non par l'effort, et la puissance retombe alors à ce que la résistance
     * demande — voire à rien du tout, roue libre.
     */
    private fun puissanceA(pente: Double, vitesse: Double): Double {
        val demande = resistance(atan(pente / 100.0), vitesse)
        return demande.coerceIn(0.0, puissanceVoulue(pente))
    }

    /**
     * Fréquence cardiaque, qui rejoint sa cible avec le retard qu'on lui connaît.
     *
     * Sans ce retard, le cœur suivrait la pente au mètre près : il monterait et redescendrait
     * dans chaque faux plat, ce qu'aucun coureur ne reconnaîtrait. La constante de temps est
     * de [TEMPS_CARDIAQUE] secondes, ce qui est de l'ordre de ce qu'on observe.
     */
    private fun cardiaqueApres(courante: Double, puissance: Double, duree: Double): Double {
        val part = (puissance / profil.puissance).coerceIn(0.0, 1.3)
        val cible = profil.cardiaqueRepos +
            (profil.cardiaqueMaximale - profil.cardiaqueRepos) * (0.35 + 0.5 * part)
        val poids = 1.0 - exp(-duree / TEMPS_CARDIAQUE)
        return courante + (cible - courante) * poids
    }

    /* ------------------------------------------------------------ transmission */

    /** Le rapport qui approche le mieux la cadence choisie à cette vitesse. */
    private fun transmissionDe(vitesse: Double): Drivetrain {
        var meilleurPlateau = 0
        var meilleurPignon = 0
        var meilleurEcart = Double.MAX_VALUE
        for (plateau in profil.plateaux.indices) {
            for (pignon in profil.pignons.indices) {
                val cadence = cadenceDe(vitesse, profil.plateaux[plateau], profil.pignons[pignon])
                val ecart = abs(cadence - profil.cadenceChoisie)
                if (ecart < meilleurEcart) {
                    meilleurEcart = ecart
                    meilleurPlateau = plateau
                    meilleurPignon = pignon
                }
            }
        }
        return Drivetrain(
            front = meilleurPlateau + 1,
            frontCount = profil.plateaux.size,
            frontTeeth = profil.plateaux[meilleurPlateau],
            // Le rang du pignon se compte depuis le grand, celui qu'on prend pour monter :
            // c'est la numérotation des groupes, et celle que le schéma du tableau de bord
            // dessine — barre la plus haute à gauche, plus grand nombre de dents. La liste
            // est rangée dans ce sens-là, les deux coïncident donc.
            rear = meilleurPignon + 1,
            rearCount = profil.pignons.size,
            rearTeeth = profil.pignons[meilleurPignon],
        )
    }

    private fun cadenceDe(vitesse: Double, transmission: Drivetrain): Double =
        cadenceDe(vitesse, transmission.frontTeeth ?: 34, transmission.rearTeeth ?: 17)

    private fun cadenceDe(vitesse: Double, plateau: Int, pignon: Int): Double {
        val developpement = DEVELOPPEMENT_ROUE * plateau / pignon
        return vitesse / developpement * 60.0
    }

    private companion object {
        /** Pas de tabulation de la sortie (m). */
        const val PAS_METRES = 10.0

        /** Demi-fenêtre de lissage de la pente (m). */
        const val LISSAGE_PENTE = 60.0

        /** Demi-fenêtre de lissage du cap (m). */
        const val LISSAGE_CAP = 25.0

        /** Constante de temps de la réponse cardiaque (s). */
        const val TEMPS_CARDIAQUE = 25.0

        /** Part de la puissance de référence tenue sur le plat, en côte, et en descente. */
        const val EFFORT_PLAT = 0.85
        const val EFFORT_COTE = 1.15
        const val EFFORT_DESCENTE = 0.30

        /** Pente à laquelle le coureur donne tout, et celle à partir de laquelle il se relève (%). */
        const val PENTE_PLEIN_EFFORT = 8.0
        const val PENTE_ROUE_LIBRE = -4.0

        const val PESANTEUR = 9.81
        const val DENSITE_AIR = 1.225

        /** Circonférence d'une roue de 700×28 (m), pour convertir la vitesse en cadence. */
        const val DEVELOPPEMENT_ROUE = 2.136
    }
}
