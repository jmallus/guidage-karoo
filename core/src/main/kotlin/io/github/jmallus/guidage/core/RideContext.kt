package io.github.jmallus.guidage.core

/** Ce que la sortie est en train de faire, et donc ce que le champ doit montrer. */
enum class RideContext {
    /** En montée : ce qui reste au sommet, et la pente. */
    CLIMB,

    /** En descente avec un virage serré devant : la géométrie. */
    DESCENT,

    /** À l'approche d'un ravitaillement : sa distance et son nom. */
    RESUPPLY,

    /** Rien de particulier : la vitesse et ce qui reste. */
    CRUISE,
}

/** Ce que le champ regarde pour se décider. */
data class ContextInputs(
    /** Vrai quand le Karoo dit qu'on est dans une côte. */
    val onClimb: Boolean = false,
    val gradePercent: Double? = null,
    /** Distance au prochain ravitaillement (m), null s'il n'y en a pas devant. */
    val resupplyDistance: Double? = null,
    /** Distance au prochain virage serré (m), null s'il n'y en a pas. */
    val sharpBendDistance: Double? = null,
)

/**
 * La règle qui décide de ce que montre le champ contextuel.
 *
 * L'ordre n'est pas arbitraire. La descente passe devant tout dès qu'un virage serré est
 * annoncé : à soixante à l'heure, une fontaine attend, un virage non. Le ravitaillement
 * vient ensuite, parce qu'il faut le décider avant de l'avoir dépassé. La montée après :
 * elle dure, et rien n'y presse. Le reste est du roulage.
 */
object RideContexts {

    /** Distance à laquelle un ravitaillement prend la main (m). */
    const val RESUPPLY_METERS = 800.0

    /** Distance à laquelle un virage serré prend la main (m). */
    const val BEND_METERS = 1_000.0

    /** Pente au-delà de laquelle on monte (%). */
    const val CLIMB_GRADE = 3.0

    /** Pente en deçà de laquelle on descend (%). */
    const val DESCENT_GRADE = -3.0

    fun candidate(inputs: ContextInputs): RideContext {
        val grade = inputs.gradePercent
        val descending = grade != null && grade <= DESCENT_GRADE
        val bendClose = (inputs.sharpBendDistance ?: Double.MAX_VALUE) <= BEND_METERS
        if (descending && bendClose) return RideContext.DESCENT

        if ((inputs.resupplyDistance ?: Double.MAX_VALUE) <= RESUPPLY_METERS) {
            return RideContext.RESUPPLY
        }

        if (inputs.onClimb || (grade != null && grade >= CLIMB_GRADE)) return RideContext.CLIMB

        return RideContext.CRUISE
    }
}

/**
 * Retient l'état courant, et n'en change qu'à bon escient.
 *
 * Sans cela le champ battrait : une pente qui oscille autour de trois pour cent ferait
 * clignoter la moitié basse d'un état à l'autre, et une mise en page qui change deux fois
 * par seconde est illisible même quand chacune de ses versions est juste. On exige donc que
 * le nouvel état tienne quelques secondes avant de basculer — sauf pour la descente, où
 * attendre reviendrait à annoncer le virage une fois dedans.
 */
class ContextSelector(
    private val holdSeconds: Double = DEFAULT_HOLD_SECONDS,
    initial: RideContext = RideContext.CRUISE,
) {

    var current: RideContext = initial
        private set

    private var pending: RideContext? = null
    private var pendingSeconds = 0.0

    fun update(deltaSeconds: Double, candidate: RideContext): RideContext {
        if (candidate == current) {
            pending = null
            pendingSeconds = 0.0
            return current
        }

        // La sécurité ne patiente pas : un virage annoncé trop tard ne sert à rien.
        if (candidate == RideContext.DESCENT) {
            current = candidate
            pending = null
            pendingSeconds = 0.0
            return current
        }

        if (candidate != pending) {
            pending = candidate
            pendingSeconds = 0.0
        }
        pendingSeconds += deltaSeconds.coerceAtLeast(0.0)
        if (pendingSeconds >= holdSeconds) {
            current = candidate
            pending = null
            pendingSeconds = 0.0
        }
        return current
    }

    fun reset(context: RideContext = RideContext.CRUISE) {
        current = context
        pending = null
        pendingSeconds = 0.0
    }

    companion object {
        /** Durée pendant laquelle un état candidat doit tenir avant la bascule (s). */
        const val DEFAULT_HOLD_SECONDS = 8.0
    }
}
