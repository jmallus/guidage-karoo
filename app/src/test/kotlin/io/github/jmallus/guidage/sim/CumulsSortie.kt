package io.github.jmallus.guidage.sim

import io.github.jmallus.guidage.core.RideLevel
import io.github.jmallus.guidage.core.ZoneClock
import io.github.jmallus.guidage.core.ZoneRange
import io.github.jmallus.guidage.core.Zones
import kotlin.math.pow

/**
 * Les cumuls que le Karoo tient sur une sortie, refaits pour la sortie fictive.
 *
 * Ce n'est pas du code de l'appareil et cela n'a pas à l'être : sur le Karoo ces valeurs
 * arrivent toutes faites, publiées par le système, et l'extension ne fait que les lire. Le
 * banc d'essai n'a personne pour les lui donner — il lui faut donc les fabriquer, exactement
 * comme il lui faut fabriquer un fond de carte.
 *
 * Les formules sont celles de la littérature, et le sont **volontairement** de façon
 * approchée : ce qu'on juge sur les planches est la mise en page des cases de bilan, non
 * l'exactitude d'un facteur d'intensité. Une valeur plausible suffit ; une valeur juste ne
 * changerait rien à ce qu'on regarde.
 */
class CumulsSortie {

    /** Jusqu'où la sortie a été parcourue, en secondes depuis le départ. */
    var jusqua: Double = 0.0
        private set

    private var secondesCumulees = 0.0
    private var sommeCardiaque = 0.0
    private var maximumCardiaque = 0.0
    private var sommePuissance = 0.0

    /** La moyenne glissante de trente secondes, dont la normalisée est la moyenne quartique. */
    private val fenetre = ArrayDeque<Double>()
    private var sommeQuatriemes = 0.0
    private var echantillonsQuatriemes = 0

    private val horloge = ZoneClock()
    private var horlogeMillis = 0L

    fun reset() {
        jusqua = 0.0
        secondesCumulees = 0.0
        sommeCardiaque = 0.0
        maximumCardiaque = 0.0
        sommePuissance = 0.0
        fenetre.clear()
        sommeQuatriemes = 0.0
        echantillonsQuatriemes = 0
        horloge.reset()
        horlogeMillis = 0L
    }

    fun ajouter(pas: Double, instant: InstantSortie, zonesCardiaques: List<ZoneRange>) {
        jusqua += pas
        secondesCumulees += pas
        sommeCardiaque += instant.cardiaque * pas
        if (instant.cardiaque > maximumCardiaque) maximumCardiaque = instant.cardiaque
        sommePuissance += instant.puissance * pas

        fenetre.addLast(instant.puissance)
        while (fenetre.size * pas > FENETRE_SECONDES) fenetre.removeFirst()
        if (fenetre.size * pas >= FENETRE_SECONDES) {
            sommeQuatriemes += fenetre.average().pow(4)
            echantillonsQuatriemes++
        }

        // L'horloge des zones est celle de l'extension, et non une seconde écriture : c'est
        // le seul cumul que le Karoo ne publie pas, donc le seul que le code de l'appareil
        // tient lui-même — le banc d'essai doit donc passer par lui.
        horlogeMillis += (pas * 1_000).toLong()
        horloge.observe(horlogeMillis, Zones.zoneOf(instant.cardiaque, zonesCardiaques))
    }

    fun niveau(elevationGain: Double, elevationRemaining: Double): RideLevel {
        if (secondesCumulees <= 0.0) return RideLevel.UNKNOWN
        val moyennePuissance = sommePuissance / secondesCumulees
        val normalisee = if (echantillonsQuatriemes == 0) {
            moyennePuissance
        } else {
            (sommeQuatriemes / echantillonsQuatriemes).pow(0.25)
        }
        val facteur = normalisee / FTP_SIMULEE
        return RideLevel(
            averageHeartRate = sommeCardiaque / secondesCumulees,
            maxHeartRate = maximumCardiaque,
            averagePower = moyennePuissance,
            normalizedPower = normalisee,
            elevationGain = elevationGain,
            elevationRemaining = elevationRemaining,
            intensityFactor = facteur,
            trainingStressScore = secondesCumulees * normalisee * facteur / (FTP_SIMULEE * 36.0),
            heartRateZoneSeconds = horloge.elapsed.take(ZONES_CARDIAQUES),
        )
    }

    private companion object {
        /** La fenêtre de lissage de la puissance normalisée (s). */
        const val FENETRE_SECONDES = 30.0

        /** La puissance au seuil du coureur fictif (W), dont se déduit l'intensité. */
        const val FTP_SIMULEE = 250.0

        /** Le Karoo règle cinq zones cardiaques ; l'horloge en tient sept. */
        const val ZONES_CARDIAQUES = 5
    }
}
