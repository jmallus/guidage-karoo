package io.github.jmallus.guidage.sim

import io.github.jmallus.guidage.core.BatteryDrain
import io.github.jmallus.guidage.core.DriftTracker
import io.github.jmallus.guidage.core.RideLevel
import io.github.jmallus.guidage.core.WPrime
import io.github.jmallus.guidage.core.WPrimeTracker
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

    /**
     * La réserve anaérobie, suivie par le vrai code de l'extension.
     *
     * À la différence des autres cumuls, celui-ci n'est pas une imitation de ce que
     * l'appareil publierait : c'est le même suiveur que le champ exécute sur le Karoo. Le
     * banc d'essai ne lui fournit que la puissance, comme le ferait le capteur.
     */
    private val reserve = WPrimeTracker()

    /**
     * La dérive et la décharge, elles aussi tenues par le code de l'appareil.
     *
     * La dérive a besoin d'un cœur qui monte à effort constant, ce que la sortie fictive
     * produit toute seule : sa fréquence rejoint sa cible avec retard, et les côtes de la
     * seconde moitié la trouvent plus haute qu'au départ.
     *
     * La décharge, elle, n'a rien à quoi se raccrocher — le banc d'essai n'a pas de batterie.
     * On lui en invente une qui se vide à [DECHARGE_PAR_HEURE], ce qui est l'ordre de grandeur
     * d'un Karoo écran allumé, carte affichée.
     */
    private val derive = DriftTracker()
    private val batterie = BatteryDrain()

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
        reserve.reset()
        derive.reset()
        batterie.reset()
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
        reserve.observe(horlogeMillis, instant.puissance, FTP_SIMULEE, RESERVE_SIMULEE)
        derive.observe(horlogeMillis, instant.puissance, instant.cardiaque)
        batterie.observe(
            horlogeMillis,
            CHARGE_AU_DEPART - DECHARGE_PAR_HEURE * horlogeMillis / 3_600_000.0,
        )
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
            wPrimeBalance = reserve.balance,
            wPrimeCapacity = reserve.size,
            criticalPower = FTP_SIMULEE,
            // Le temps total est celui de la sortie ; les arrêts sont inventés, la sortie
            // fictive roulant sans jamais s'arrêter.
            totalSeconds = secondesCumulees + arretsCumules(),
            movingSeconds = secondesCumulees,
            drift = derive.drift(),
            batteryPercent = batterie.percent,
            batteryPerHour = batterie.perHour,
        )
    }

    /**
     * Les arrêts de la sortie fictive.
     *
     * Le coureur simulé ne s'arrête jamais : il faut donc lui inventer ses haltes, faute de
     * quoi la case des arrêts montrerait une barre pleine et n'apprendrait rien.
     *
     * Une halte toutes les quarante minutes, ce qui est le rythme d'une flânerie et non celui
     * d'un brevet. Le rythme réel — une halte par heure et demie — ne se déclencherait jamais :
     * la sortie fictive ne dure qu'un peu plus d'une heure, et la case resterait à zéro d'un
     * bout à l'autre. C'est le parcours d'aperçu qui est court, pas le coureur qui est pressé.
     */
    private fun arretsCumules(): Double =
        (secondesCumulees / SECONDES_ENTRE_HALTES).toInt() * HALTE_SECONDES

    private companion object {
        /** La fenêtre de lissage de la puissance normalisée (s). */
        const val FENETRE_SECONDES = 30.0

        /** La puissance au seuil du coureur fictif (W), dont se déduit l'intensité. */
        const val FTP_SIMULEE = 250.0

        /** Le Karoo règle cinq zones cardiaques ; l'horloge en tient sept. */
        const val ZONES_CARDIAQUES = 5

        /** La réserve anaérobie du coureur fictif (J) : soixante-dix kilos à la règle du pouce. */
        val RESERVE_SIMULEE = WPrime.defaultCapacity(70.0)

        /** La batterie du Karoo fictif : sa charge au départ (%) et ce qu'elle perd par heure. */
        const val CHARGE_AU_DEPART = 92.0
        const val DECHARGE_PAR_HEURE = 11.0

        /** Le rythme des haltes de la sortie fictive, et ce qu'elles durent (s). */
        const val SECONDES_ENTRE_HALTES = 2_400.0
        const val HALTE_SECONDES = 420.0
    }
}
