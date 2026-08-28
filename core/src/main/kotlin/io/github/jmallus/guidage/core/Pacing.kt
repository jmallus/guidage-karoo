package io.github.jmallus.guidage.core

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Allure du coureur, apprise pendant la sortie.
 *
 * Deux vitesses, pas une. Le Karoo extrapole l'heure d'arrivée depuis la moyenne de la
 * sortie, ce qui revient à supposer qu'un col se monte à la vitesse d'un faux plat : sur un
 * parcours qui garde ses côtes pour la fin, l'heure annoncée recule de minute en minute, et
 * le chiffre n'apprend plus rien à celui qui le regarde.
 *
 * On mesure donc séparément ce qui se tient à peu près : la vitesse sur terrain roulant, en
 * mètres par seconde, et la vitesse ascensionnelle en montée, en mètres d'altitude par
 * seconde. Appliquées au terrain qui reste — que le profil décrit — elles donnent une durée
 * qui tient compte du relief au lieu de le moyenner.
 *
 * Les deux valeurs sont nulles tant qu'elles n'ont pas été assez observées : mieux vaut
 * rendre la main au Karoo que d'annoncer une heure tirée de trente secondes de roulage.
 */
data class LearnedPace(
    /** Vitesse sur terrain roulant (m/s), null tant qu'elle n'est pas assez observée. */
    val flatSpeed: Double? = null,
    /** Vitesse ascensionnelle en montée (m d'altitude par seconde), null de même. */
    val climbRate: Double? = null,
    /** Dispersion des vitesses observées, rapportée à leur moyenne (sans unité). */
    val flatSpread: Double = 0.0,
    val climbSpread: Double = 0.0,
    /** Temps d'observation cumulé (s) derrière chacune des deux vitesses. */
    val flatSeconds: Double = 0.0,
    val climbSeconds: Double = 0.0,
    /**
     * Puissance tenue dans chacun des deux régimes (W), quand un capteur la rapporte.
     *
     * Elle s'apprend au même endroit et de la même façon que les vitesses, parce qu'elle
     * répond à la même question sous un autre angle : ce que coûte ce qui reste. On ne la
     * déduit pas d'une masse et d'une physique supposée — on mesure ce que le coureur fait.
     */
    val flatPower: Double? = null,
    val climbPower: Double? = null,
) {
    companion object {
        val UNKNOWN = LearnedPace()
    }
}

/**
 * Ce que le terrain restant demande, séparé en deux postes.
 *
 * La distance des montées n'entre pas dans [easyDistance] : le temps qu'elles coûtent se
 * déduit de leur dénivelé, et le compter deux fois — une fois en mètres d'altitude, une
 * fois en mètres parcourus — allongerait l'arrivée d'autant.
 */
data class RemainingTerrain(
    /** Dénivelé positif restant (m). */
    val ascent: Double,
    /** Distance parcourue en montée (m). */
    val climbDistance: Double,
    /** Distance restante hors montées : plat, faux plats et descentes (m). */
    val easyDistance: Double,
) {
    val totalDistance: Double get() = climbDistance + easyDistance

    companion object {
        val NONE = RemainingTerrain(0.0, 0.0, 0.0)
    }
}

/** Durée restante estimée et la marge qu'on lui reconnaît. */
data class ArrivalEstimate(
    /** Temps restant jusqu'à l'arrivée (s). */
    val seconds: Double,
    /** Incertitude de part et d'autre (s). */
    val marginSeconds: Double,
)

/**
 * Apprend l'allure du coureur au fil de la sortie.
 *
 * Chaque relevé est rangé dans l'un des deux paniers selon la pente du moment, puis fondu
 * dans une moyenne glissante pondérée par le temps : un échantillon vaut d'autant plus
 * qu'il couvre une durée longue, et l'ensemble oublie doucement le début de la sortie.
 * L'oubli est voulu — l'allure de la sixième heure n'est pas celle de la première, et c'est
 * la première qui ferait mentir l'arrivée.
 */
class PaceLearner(private val tauSeconds: Double = TAU_SECONDS) {

    private val flat = Ewma()
    private val climb = Ewma()
    private val flatWatts = Ewma()
    private val climbWatts = Ewma()

    /**
     * Range un relevé.
     *
     * @param deltaSeconds temps écoulé depuis le relevé précédent. Un intervalle nul,
     * négatif ou trop long est écarté : le second signale une reprise après une pause, où
     * l'on ne sait pas ce qui s'est passé entre-temps.
     * @param speedMetersPerSecond vitesse instantanée, ou null si le capteur se tait.
     * @param gradePercent pente mesurée, ou null de même.
     */
    fun observe(
        deltaSeconds: Double,
        speedMetersPerSecond: Double?,
        gradePercent: Double?,
        powerWatts: Double? = null,
    ) {
        if (deltaSeconds <= 0.0 || deltaSeconds > MAX_SAMPLE_SECONDS) return
        val speed = speedMetersPerSecond ?: return
        val grade = gradePercent ?: return
        // À l'arrêt, on n'apprend rien qu'on veuille appliquer au terrain qui reste : un feu
        // rouge n'est pas une allure. Le temps perdu, lui, n'est pas rattrapable de toute
        // façon — aucune estimation ne peut prévoir les feux à venir.
        if (speed < MOVING_SPEED) return

        val weight = 1.0 - exp(-deltaSeconds / tauSeconds)
        if (grade >= CLIMB_GRADE) {
            climb.add(speed * grade / 100.0, weight, deltaSeconds)
            powerWatts?.let { climbWatts.add(it, weight, deltaSeconds) }
        } else {
            flat.add(speed, weight, deltaSeconds)
            powerWatts?.let { flatWatts.add(it, weight, deltaSeconds) }
        }
    }

    /** Oublie tout : nouvelle sortie, ou retour au départ sur le banc d'essai. */
    fun reset() {
        flat.reset()
        climb.reset()
        flatWatts.reset()
        climbWatts.reset()
    }

    val pace: LearnedPace
        get() = LearnedPace(
            flatSpeed = flat.mean.takeIf { flat.seconds >= FLAT_WARMUP_SECONDS && it > 0.0 },
            climbRate = climb.mean.takeIf { climb.seconds >= CLIMB_WARMUP_SECONDS && it > 0.0 },
            flatSpread = flat.spread,
            climbSpread = climb.spread,
            flatSeconds = flat.seconds,
            climbSeconds = climb.seconds,
            flatPower = flatWatts.mean.takeIf { flatWatts.seconds >= FLAT_WARMUP_SECONDS && it > 0.0 },
            climbPower = climbWatts.mean.takeIf { climbWatts.seconds >= CLIMB_WARMUP_SECONDS && it > 0.0 },
        )

    /** Moyenne et variance glissantes, du même poids l'une que l'autre. */
    private class Ewma {
        var mean = 0.0
            private set
        var seconds = 0.0
            private set

        private var variance = 0.0
        private var started = false

        fun add(value: Double, weight: Double, deltaSeconds: Double) {
            seconds += deltaSeconds
            if (!started) {
                mean = value
                started = true
                return
            }
            val deviation = value - mean
            mean += weight * deviation
            variance += weight * (deviation * deviation - variance)
        }

        fun reset() {
            mean = 0.0
            variance = 0.0
            seconds = 0.0
            started = false
        }

        /** Écart-type rapporté à la moyenne : c'est lui qui donnera sa largeur à la marge. */
        val spread: Double get() = if (mean > 0.0) sqrt(variance.coerceAtLeast(0.0)) / mean else 0.0
    }

    companion object {
        /** Au-delà de cette pente, on est en montée et c'est le dénivelé qui compte. */
        const val CLIMB_GRADE = 3.0

        /** En deçà de cette vitesse, le coureur est à l'arrêt (m/s). */
        const val MOVING_SPEED = 1.5

        /** Intervalle au-delà duquel un relevé est écarté (s). */
        const val MAX_SAMPLE_SECONDS = 10.0

        /** Constante de temps de l'oubli (s) : un quart d'heure. */
        const val TAU_SECONDS = 900.0

        /** Temps d'observation avant d'oser une vitesse de plat (s). */
        const val FLAT_WARMUP_SECONDS = 180.0

        /**
         * Temps d'observation avant d'oser une vitesse ascensionnelle (s).
         *
         * Plus court que pour le plat : les montées sont rares, et attendre trois minutes
         * de côte reviendrait à ne rien annoncer avant le premier col.
         */
        const val CLIMB_WARMUP_SECONDS = 120.0
    }
}

/**
 * Heure d'arrivée déduite de l'allure apprise et du terrain qui reste.
 *
 * Les fonctions sont pures : elles ne dépendent que du profil et de ce qu'on a mesuré.
 */
object Pacing {

    /** En deçà, le dénivelé restant ne pèse pas et la montée n'est pas traitée à part (m). */
    const val NEGLIGIBLE_ASCENT = 30.0

    /**
     * Marge minimale, en part du temps restant.
     *
     * Une allure parfaitement régulière donnerait une dispersion nulle et donc une arrivée
     * annoncée à la seconde près, ce qui serait faux pour d'autres raisons : le vent, les
     * feux, l'arrêt à la fontaine. Six pour cent d'un temps restant, c'est le plancher.
     */
    const val MINIMUM_SPREAD = 0.06

    /** Au-delà, la fourchette ne dit plus rien d'utile : on cesse de l'élargir. */
    const val MAXIMUM_SPREAD = 0.30

    /**
     * Découpe le terrain restant en dénivelé, distance de montée et distance roulante.
     *
     * Sans profil — navigation vers un point, itinéraire sans altitude — tout est rangé en
     * roulant : l'estimation vaudra alors ce que vaut la vitesse de plat, ce qui reste
     * honnête tant qu'on ne prétend pas connaître un relief qu'on ignore.
     */
    fun terrain(profile: ElevationProfile?, from: Double, remainingDistance: Double): RemainingTerrain {
        if (remainingDistance <= 0.0) return RemainingTerrain.NONE
        if (profile == null || profile.isEmpty) {
            return RemainingTerrain(0.0, 0.0, remainingDistance)
        }

        val to = from + remainingDistance
        val segment = profile.slice(from, to)
        var ascent = 0.0
        var climbDistance = 0.0
        var easyDistance = 0.0
        for (i in 1 until segment.size) {
            val run = segment[i].distance - segment[i - 1].distance
            if (run <= 0.0) continue
            val rise = segment[i].elevation - segment[i - 1].elevation
            if (rise / run * 100.0 >= PaceLearner.CLIMB_GRADE) {
                ascent += rise
                climbDistance += run
            } else {
                easyDistance += run
            }
        }

        // Le profil peut s'arrêter avant l'arrivée — il est plus court que l'itinéraire en
        // navigation vers un point. Ce qui dépasse est compté roulant plutôt qu'oublié :
        // une arrivée qui ignore ses dix derniers kilomètres serait pire qu'approximative.
        val covered = climbDistance + easyDistance
        return RemainingTerrain(
            ascent = ascent,
            climbDistance = climbDistance,
            easyDistance = easyDistance + (remainingDistance - covered).coerceAtLeast(0.0),
        )
    }

    /**
     * Temps restant et marge, ou null tant que l'allure n'est pas assez connue.
     *
     * Rendre null n'est pas un échec : c'est ce qui laisse l'affichage retomber sur l'heure
     * du Karoo au lieu d'en inventer une. Il faut la vitesse de plat dès qu'il reste du
     * terrain roulant, et la vitesse ascensionnelle dès que le dénivelé restant pèse.
     */
    fun arrival(pace: LearnedPace, terrain: RemainingTerrain): ArrivalEstimate? {
        if (terrain.totalDistance <= 0.0) return null

        val climbs = terrain.ascent > NEGLIGIBLE_ASCENT
        // Une bosse qui ne pèse pas se parcourt à l'allure du plat : la traiter à part
        // demanderait une vitesse ascensionnelle pour trente mètres de dénivelé.
        val easyDistance = if (climbs) terrain.easyDistance else terrain.totalDistance

        val flatSpeed = pace.flatSpeed
        if (easyDistance > 0.0 && (flatSpeed == null || flatSpeed <= 0.0)) return null
        val climbRate = pace.climbRate
        if (climbs && (climbRate == null || climbRate <= 0.0)) return null

        val flatSeconds = if (easyDistance > 0.0) easyDistance / flatSpeed!! else 0.0
        val climbSeconds = if (climbs) terrain.ascent / climbRate!! else 0.0
        val seconds = flatSeconds + climbSeconds
        if (seconds <= 0.0) return null

        // La marge est celle des deux allures, chacune pesant ce qu'elle occupe du temps
        // restant : une fin de parcours en côte se juge à la régularité en côte.
        val flatShare = flatSeconds / seconds
        val climbShare = climbSeconds / seconds
        val spread = (flatShare * pace.flatSpread + climbShare * pace.climbSpread)
            .coerceIn(MINIMUM_SPREAD, MAXIMUM_SPREAD)

        return ArrivalEstimate(seconds = seconds, marginSeconds = seconds * spread)
    }
}
