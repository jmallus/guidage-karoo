package io.github.jmallus.guidage.core

import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadSegment
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * Un carrefour de l'itinéraire : là où d'autres voies quittent le tracé.
 *
 * Les angles sont relatifs au cap d'arrivée, en degrés de −180 à 180 : 0 tout droit, positif
 * à droite, négatif à gauche.
 */
data class Carrefour(
    /** Distance depuis le départ du tracé géométrique (m). */
    val distanceAlongRoute: Double,
    /** L'angle que prend l'itinéraire en sortant du carrefour. */
    val virage: Double,
    /** Les angles des autres voies, de gauche à droite. */
    val branches: List<Double>,
) {
    /** Le côté où tourne l'itinéraire : −1 à gauche, +1 à droite. */
    val cote: Int get() = if (virage >= 0) 1 else -1

    /**
     * Vrai quand il faut faire quelque chose à ce carrefour.
     *
     * Tourner franchement, ou ne pas prendre la voie la plus droite : une route principale
     * qui s'infléchit à droite pendant qu'un chemin file tout droit demande d'y penser,
     * autant qu'un vrai virage à angle droit.
     */
    val manoeuvre: Boolean
        get() = branches.isNotEmpty() &&
            (abs(virage) >= Carrefours.VIRAGE_FRANC || branches.any { abs(it) < abs(virage) - Carrefours.ECART_PLUS_DROIT })

    /** Vrai quand une des autres voies part du côté de [cote]. */
    fun ouvreDuCote(cote: Int): Boolean =
        branches.any { it * cote > 0 && abs(it) in Carrefours.VIRAGE_FRANC..(180.0 - Carrefours.VIRAGE_FRANC) }
}

/** La prochaine manœuvre, vue du coureur. */
data class ManoeuvreAVenir(
    val carrefour: Carrefour,
    /** Distance qui reste jusqu'au carrefour (m). */
    val distance: Double,
    /**
     * Le rang de la voie à prendre de son côté : 2 pour « la deuxième à droite ». Il compte
     * les carrefours d'avant qui ouvrent du même côté, ceux qu'il ne faut **pas** prendre.
     */
    val rang: Int,
    /** Ces carrefours-là, à leur distance du coureur (m) : le schéma les montre en chemin. */
    val voiesAvant: List<Pair<Double, List<Double>>>,
)

/**
 * Les carrefours de l'itinéraire, retrouvés sur le fond de carte.
 *
 * Le Karoo ne donne aux extensions aucune instruction de navigation : il donne le tracé, et
 * c'est tout. Les carrefours se déduisent donc en croisant ce tracé avec les voies du fond de
 * carte hors ligne : une voie qui longe le tracé est celle qu'on roule, une voie qui s'en
 * écarte est une branche du carrefour où elle le quitte. L'angle que prend le tracé à cet
 * endroit dit ensuite s'il faut tourner, et de quel côté.
 *
 * Le fond de carte et le tracé ne se superposent jamais exactement — l'un vient
 * d'OpenStreetMap simplifié, l'autre du routeur du Karoo. Une voie est donc « sur le tracé »
 * à [PRES] mètres près, et une branche ne compte que si elle s'en écarte vraiment, de
 * [ELOIGNE] mètres au moins : en deçà, c'est un défaut d'alignement et non un carrefour.
 */
object Carrefours {

    /** Une voie à moins de cette distance du tracé est celle qu'on roule (m). */
    const val PRES = 12.0

    /** Une branche doit s'écarter d'autant du carrefour pour compter (m). */
    const val ELOIGNE = 30.0

    /** Angle à partir duquel on tourne franchement (degrés). */
    const val VIRAGE_FRANC = 30.0

    /** De combien une autre voie doit être plus droite que l'itinéraire pour qu'on hésite (degrés). */
    const val ECART_PLUS_DROIT = 15.0

    /** Deux départs de voie à moins de cette distance le long du tracé font un même carrefour (m). */
    private const val REGROUPEMENT = 25.0

    /** Longueur de tracé sur laquelle se lisent les caps d'arrivée et de départ (m). */
    private const val BRAS = 30.0

    /** Recul du bras d'arrivée sur le point de départ des branches (m). */
    private const val RETRAIT = 15.0

    /** Pas de rééchantillonnage du tracé et des voies (m). */
    private const val PAS = 5.0

    /** Côté des mailles de l'index du tracé (m). */
    private const val MAILLE = 20.0

    /** Deux branches plus proches que cet angle sont la même (degrés). */
    private const val MEME_BRANCHE = 20.0

    /** Les carrefours entre [from] et [to] le long du tracé (m), dans l'ordre du parcours. */
    fun entre(path: List<GeoPoint>, roads: List<RoadSegment>, from: Double, to: Double): List<Carrefour> {
        // Un peu de marge de chaque côté : une voie qui quitte le tracé juste avant la
        // fenêtre semblerait sinon s'écarter d'un tracé qui n'existe plus.
        val debut = (from - BRAS * 2 - RETRAIT).coerceAtLeast(0.0)
        val portion = Geo.pathBetween(path, debut, to + BRAS * 2)
        if (portion.size < 2) return emptyList()
        val origine = portion.first()
        val trace = Trace(echantillonner(portion.map { Geo.project(origine, it) }, debut))

        val departs = mutableListOf<Depart>()
        for (road in roads) {
            if (road.kind.isArea || road.kind == RoadKind.STREAM) continue
            val plan = (road.latitudes.indices).map {
                Geo.project(origine, GeoPoint(road.latitudes[it] / 1e6, road.longitudes[it] / 1e6))
            }
            if (!trace.croise(plan)) continue
            val points = densifier(plan)
            departs += trace.departs(points)
            departs += trace.departs(points.asReversed())
        }

        return regrouper(departs.filter { it.along in from..to })
            .mapNotNull { groupe -> carrefour(trace, groupe) }
    }

    /**
     * La prochaine manœuvre à moins de [portee] mètres devant [distanceAlongRoute], ou null.
     *
     * @param distanceAlongRoute la position sur le tracé géométrique, et non l'abscisse du
     * Karoo : les deux divergent au fil de la sortie.
     */
    fun prochaine(
        path: List<GeoPoint>,
        roads: List<RoadSegment>,
        distanceAlongRoute: Double,
        portee: Double,
    ): ManoeuvreAVenir? {
        // Le carrefour qu'on franchit compte encore quelques mètres : la marque est en
        // retard sur le coureur, et le schéma ne doit pas s'éteindre avant qu'il ait tourné.
        val carrefours = entre(path, roads, distanceAlongRoute - DEPASSE, distanceAlongRoute + portee)
        return parmi(carrefours, distanceAlongRoute, portee)
    }

    /**
     * La même, cherchée dans des carrefours déjà trouvés.
     *
     * Le champ se redessine chaque seconde, et croiser le tracé avec les voies coûte plus que
     * le dessin : il garde les carrefours d'un kilomètre de route et ne les recherche qu'après
     * avoir avancé. Ceux qu'il garde peuvent commencer derrière le coureur, ils sont écartés ici.
     */
    fun parmi(carrefours: List<Carrefour>, distanceAlongRoute: Double, portee: Double): ManoeuvreAVenir? {
        val devant = carrefours.filter {
            it.distanceAlongRoute >= distanceAlongRoute - DEPASSE &&
                it.distanceAlongRoute <= distanceAlongRoute + portee
        }
        val index = devant.indexOfFirst { it.manoeuvre }
        if (index < 0) return null
        val cible = devant[index]
        val avant = devant.subList(0, index).filter {
            it.distanceAlongRoute > distanceAlongRoute && it.ouvreDuCote(cible.cote)
        }
        return ManoeuvreAVenir(
            carrefour = cible,
            distance = cible.distanceAlongRoute - distanceAlongRoute,
            rang = avant.size + 1,
            voiesAvant = avant.map { (it.distanceAlongRoute - distanceAlongRoute) to it.branches },
        )
    }

    /** Distance au-delà du carrefour pendant laquelle on le montre encore (m). */
    const val DEPASSE = 15.0

    /** Un point où une voie quitte le tracé, et le cap qu'elle prend. */
    private data class Depart(val along: Double, val cap: Double)

    private data class Echantillon(val x: Double, val y: Double, val along: Double)

    /** Le tracé rééchantillonné, avec un index par mailles pour trouver vite le plus proche. */
    private class Trace(val echantillons: List<Echantillon>) {
        private val mailles = HashMap<Long, MutableList<Echantillon>>()
        private val ouest = echantillons.minOf { it.x } - ELOIGNE
        private val est = echantillons.maxOf { it.x } + ELOIGNE
        private val sud = echantillons.minOf { it.y } - ELOIGNE
        private val nord = echantillons.maxOf { it.y } + ELOIGNE

        init {
            for (e in echantillons) mailles.getOrPut(cle(cellule(e.x), cellule(e.y))) { mutableListOf() } += e
        }

        /**
         * Vrai quand l'emprise de la voie touche celle du tracé : les autres ne coûtent rien.
         * L'emprise, et non les sommets — une voie droite n'en a que deux, loin de part et
         * d'autre du tracé qu'elle traverse.
         */
        fun croise(points: List<PlanePoint>): Boolean =
            points.maxOf { it.x } >= ouest && points.minOf { it.x } <= est &&
                points.maxOf { it.y } >= sud && points.minOf { it.y } <= nord

        /** L'échantillon le plus proche à moins de [PRES], ou null. */
        fun proche(x: Double, y: Double): Echantillon? {
            val cx = cellule(x)
            val cy = cellule(y)
            var meilleur: Echantillon? = null
            var ecart = PRES
            for (i in cx - 1..cx + 1) for (j in cy - 1..cy + 1) {
                for (e in mailles[cle(i, j)] ?: continue) {
                    val d = hypot(e.x - x, e.y - y)
                    if (d <= ecart) {
                        ecart = d
                        meilleur = e
                    }
                }
            }
            return meilleur
        }

        /**
         * Les endroits où la voie, parcourue dans l'ordre de [points], quitte le tracé.
         *
         * Le départ est le dernier point encore sur le tracé ; la branche, le cap vers le
         * premier point qui s'en tient à [ELOIGNE] mètres. Une voie qui revient sur le tracé
         * avant cela le longe de près et n'en part pas.
         */
        fun departs(points: List<PlanePoint>): List<Depart> {
            val resultat = mutableListOf<Depart>()
            var i = 0
            while (i < points.size - 1) {
                val ici = proche(points[i].x, points[i].y)
                if (ici == null || proche(points[i + 1].x, points[i + 1].y) != null) {
                    i++
                    continue
                }
                var j = i + 1
                var revient = false
                while (j < points.size && hypot(points[j].x - points[i].x, points[j].y - points[i].y) < ELOIGNE) {
                    if (proche(points[j].x, points[j].y) != null) {
                        revient = true
                        break
                    }
                    j++
                }
                if (!revient && j < points.size) {
                    resultat += Depart(ici.along, cap(points[i], points[j]))
                }
                i = j
            }
            return resultat
        }

        /** Le point du tracé à [along], ou le plus proche des échantillons. */
        fun a(along: Double): Echantillon =
            echantillons.minBy { abs(it.along - along) }

        private fun cellule(v: Double): Int = floor(v / MAILLE).toInt()

        private fun cle(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
    }

    private fun carrefour(trace: Trace, groupe: List<Depart>): Carrefour? {
        val along = groupe.map { it.along }.average()
        // Le cap d'arrivée se lit un peu avant le point de départ des branches : une voie
        // qui s'écarte en biais ne quitte le tracé, à [PRES] près, qu'une vingtaine de mètres
        // après l'embranchement, et le bras d'arrivée mordrait sinon sur le virage.
        val avant = trace.a(along - BRAS - RETRAIT)
        val entree = trace.a(along - RETRAIT)
        val centre = trace.a(along)
        val apres = trace.a(along + BRAS)
        if (entree.along - avant.along < BRAS / 2 || apres.along - centre.along < BRAS / 2) return null
        val arrivee = cap(avant.x, avant.y, entree.x, entree.y)
        val virage = relatif(cap(centre.x, centre.y, apres.x, apres.y), arrivee)
        val branches = mutableListOf<Double>()
        for (depart in groupe) {
            val angle = relatif(depart.cap, arrivee)
            // La voie qu'on prend en sortant, et celle par où l'on arrive, ne sont pas des
            // branches : ce sont les deux bras de l'itinéraire.
            if (ecart(angle, virage) < MEME_BRANCHE * 1.5 || ecart(angle, 180.0) < MEME_BRANCHE) continue
            if (branches.any { ecart(it, angle) < MEME_BRANCHE }) continue
            branches += angle
        }
        if (branches.isEmpty()) return null
        return Carrefour(along, virage, branches.sorted())
    }

    private fun regrouper(departs: List<Depart>): List<List<Depart>> {
        val groupes = mutableListOf<MutableList<Depart>>()
        for (depart in departs.sortedBy { it.along }) {
            val dernier = groupes.lastOrNull()
            if (dernier != null && depart.along - dernier.first().along <= REGROUPEMENT) {
                dernier += depart
            } else {
                groupes += mutableListOf(depart)
            }
        }
        return groupes
    }

    private fun echantillonner(plan: List<PlanePoint>, debut: Double): List<Echantillon> {
        val resultat = mutableListOf(Echantillon(plan.first().x, plan.first().y, debut))
        var parcouru = debut
        for (k in 1 until plan.size) {
            val a = plan[k - 1]
            val b = plan[k]
            val longueur = hypot(b.x - a.x, b.y - a.y)
            if (longueur <= 0.0) continue
            val pas = max(1, ceil(longueur / PAS).toInt())
            for (s in 1..pas) {
                val t = s.toDouble() / pas
                resultat += Echantillon(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, parcouru + longueur * t)
            }
            parcouru += longueur
        }
        return resultat
    }

    private fun densifier(plan: List<PlanePoint>): List<PlanePoint> {
        if (plan.size < 2) return plan
        val resultat = mutableListOf(plan.first())
        for (k in 1 until plan.size) {
            val a = plan[k - 1]
            val b = plan[k]
            val pas = max(1, ceil(hypot(b.x - a.x, b.y - a.y) / PAS).toInt())
            for (s in 1..pas) {
                val t = s.toDouble() / pas
                resultat += PlanePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
        }
        return resultat
    }

    private fun cap(a: PlanePoint, b: PlanePoint): Double = cap(a.x, a.y, b.x, b.y)

    private fun cap(ax: Double, ay: Double, bx: Double, by: Double): Double =
        (Math.toDegrees(atan2(bx - ax, by - ay)) + 360.0) % 360.0

    /** [cap] vu depuis [reference], de −180 à 180. */
    private fun relatif(cap: Double, reference: Double): Double {
        val d = ((cap - reference) % 360.0 + 360.0) % 360.0
        return if (d > 180.0) d - 360.0 else d
    }

    private fun ecart(a: Double, b: Double): Double = abs(relatif(a, b))
}
