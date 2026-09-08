package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.ArrivalEstimate
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.Nightfall
import io.github.jmallus.guidage.core.RideLevel
import io.github.jmallus.guidage.core.Sun
import io.github.jmallus.guidage.core.Zones
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.ui.LevelFieldModel
import io.github.jmallus.guidage.ui.LevelSlice
import io.github.jmallus.guidage.ui.NightRenderer
import io.github.jmallus.guidage.ui.NightVerdict
import io.github.jmallus.guidage.ui.PreviewData
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Les six cases de bilan : ce que la sortie vaut depuis le départ.
 *
 * Chacune tient dans une case ordinaire, et une page en porte dix. C'est là toute leur raison
 * d'être : le Karoo publie déjà chacun de ces nombres, mais un par champ, si bien qu'une
 * moyenne et son maximum coûtent deux emplacements — et le coureur fait la soustraction de
 * tête. Ici les deux voyagent ensemble, le grand chiffre et sa glose, et la case dit en plus
 * ce que leur voisinage veut dire.
 */
enum class Bilan(
    /**
     * L'identifiant du champ auprès de Karoo OS.
     *
     * Écrit en toutes lettres et non dérivé du nom de la constante : il voyage dans
     * `extension_info.xml` et, une fois la case posée sur une page, dans les réglages du
     * coureur. Un renommage de la constante ne doit pas déplacer ses champs.
     */
    val typeId: String,
) {
    /** FC moyenne, son maximum, et la couleur de la zone où la moyenne tombe. */
    COEUR("bilan-coeur"),

    /** Puissance moyenne, normalisée, et le mot qui dit si l'heure fut lisse ou hachée. */
    PUISSANCE("bilan-puissance"),

    /** Heure d'arrivée, heure du coucher, et le verdict en aplat. */
    ARRIVEE("bilan-arrivee"),

    /** Dénivelé monté, restant, et la part faite en barre. */
    DENIVELE("bilan-denivele"),

    /** Facteur d'intensité, charge, et le mot qui les nomme. */
    INTENSITE("bilan-intensite"),

    /** Le temps par zone en barre empilée, et la zone où il s'est le plus passé. */
    ZONES("bilan-zones"),
}

object LevelModels {

    /**
     * Ce que publie le flux numérique de chaque case.
     *
     * Une case de bilan a un chiffre principal, et c'est lui qu'elle publie : le coureur peut
     * ainsi la poser aussi comme champ de texte, ou l'enregistrer. Les cases qui n'en ont pas
     * — la répartition par zone n'est pas un nombre — ne publient rien.
     */
    fun value(bilan: Bilan, snapshot: GuidanceSnapshot, rideData: RideData, nowMillis: Long): Double? {
        val level = rideData.level
        return when (bilan) {
            Bilan.COEUR -> level.averageHeartRate
            Bilan.PUISSANCE -> level.averagePower
            Bilan.ARRIVEE -> NightModels.assessment(snapshot, rideData, nowMillis)?.marginSeconds
            Bilan.DENIVELE -> level.elevationGain
            Bilan.INTENSITE -> level.intensityFactor
            Bilan.ZONES -> level.dominantHeartRateZone?.toDouble()
        }
    }

    fun build(
        context: Context,
        bilan: Bilan,
        snapshot: GuidanceSnapshot,
        rideData: RideData,
        preview: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): LevelFieldModel {
        // Hors sortie, la case montre le bilan d'une sortie fictive : sinon le sélecteur de
        // champs n'afficherait que « -- », et l'on choisirait la case sans l'avoir vue.
        val data = if (preview && rideData.level == RideLevel.UNKNOWN) PreviewData.levelRide else rideData
        return when (bilan) {
            Bilan.COEUR -> coeur(context, data)
            Bilan.PUISSANCE -> puissance(context, data)
            Bilan.ARRIVEE -> arrivee(context, snapshot, data, preview, nowMillis)
            Bilan.DENIVELE -> denivele(context, snapshot, data)
            Bilan.INTENSITE -> intensite(context, data)
            Bilan.ZONES -> zones(context, data)
        }
    }

    /**
     * Le cœur : la moyenne en grand, le maximum à côté, l'aplat de la zone de la moyenne.
     *
     * L'aplat est celui du tableau de bord, et c'est voulu : le vert d'une case de bilan doit
     * vouloir dire la même chose que le vert d'une case instantanée. Il porte ici la zone
     * **moyenne** de la sortie — c'est-à-dire, d'un coup d'œil et sans lire un chiffre, à quoi
     * l'on aura passé sa journée.
     */
    private fun coeur(context: Context, data: RideData): LevelFieldModel {
        val level = data.level
        val moyenne = level.averageHeartRate
        return LevelFieldModel(
            label = context.getString(R.string.field_level_heart_label),
            value = entier(moyenne),
            unit = context.getString(R.string.unit_bpm),
            referenceLabel = context.getString(R.string.field_level_heart_max),
            referenceValue = level.maxHeartRate?.let { entier(it) },
            background = moyenne?.let { Zones.heartRateColor(it, data.heartRateZones) },
            emptyMessage = if (moyenne == null) context.getString(R.string.field_level_no_heart) else null,
        )
    }

    /**
     * La puissance : la moyenne, la normalisée, et le mot que leur écart forme.
     *
     * Le rapport des deux — l'indice de variabilité — ne se lit pas seul : personne ne sait
     * de tête si 1,17 est beaucoup. Le mot le dit, le chiffre reste à côté pour qui le veut.
     */
    private fun puissance(context: Context, data: RideData): LevelFieldModel {
        val level = data.level
        val moyenne = level.averagePower
        val variabilite = level.variability
        return LevelFieldModel(
            label = context.getString(R.string.field_level_power_label),
            value = entier(moyenne),
            unit = context.getString(R.string.unit_watt),
            referenceLabel = context.getString(R.string.field_level_power_normalized),
            referenceValue = level.normalizedPower?.let { entier(it) },
            caption = variabilite?.let {
                context.getString(motDeVariabilite(it), decimales(it, 2))
            },
            emptyMessage = if (moyenne == null) context.getString(R.string.field_level_no_power) else null,
        )
    }

    /**
     * L'arrivée : l'heure en grand, celle du coucher à côté, le verdict en aplat.
     *
     * C'est le champ « Avant la nuit » ramené à une case. Il a perdu sa frise et sa fourchette,
     * et garde ce qui décide : l'heure, le coucher, et la couleur qui dit s'il faut sortir la
     * lampe. Ce qui se calculait sur une page entière tient dans un dixième de page.
     */
    private fun arrivee(
        context: Context,
        snapshot: GuidanceSnapshot,
        data: RideData,
        preview: Boolean,
        nowMillis: Long,
    ): LevelFieldModel {
        val label = context.getString(R.string.field_level_arrival_label)
        val substitue = preview && !snapshot.state.navigating
        val state = if (substitue) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }
        val position = (if (substitue) PreviewData.location else snapshot.location)?.position
        if (state.route == null) {
            return LevelFieldModel(label, "--", emptyMessage = context.getString(R.string.field_no_route))
        }
        if (position == null) {
            return LevelFieldModel(label, "--", emptyMessage = context.getString(R.string.field_waiting_for_position))
        }
        val sun = Sun.next(position, nowMillis)
        // L'aperçu se place là où le verdict se joue : une petite heure avant le coucher.
        val maintenant = if (substitue && sun != null) sun.sunsetMillis - APERCU_AVANT_COUCHER_MS else nowMillis

        val estimation = FieldModels.arrival(state, data)
            ?: data.arrivalTime?.let { ArrivalEstimate((it - maintenant) / 1_000.0, 0.0) }
        if (estimation == null) {
            return LevelFieldModel(label, "--", emptyMessage = context.getString(R.string.field_effort_waiting))
        }
        val arriveeMillis = maintenant + (estimation.seconds * 1_000).toLong()
        val restante = data.distanceRemaining ?: state.distanceRemaining

        // Sans coucher connu — les nuits polaires, et rien d'autre — l'heure reste juste :
        // c'est le verdict qui n'existe pas, et la case le dit en n'ayant pas de couleur.
        if (sun == null || restante == null) {
            return LevelFieldModel(
                label = label,
                value = Format.clock(arriveeMillis.toDouble()),
                emptyMessage = null,
            )
        }
        val verdict = Nightfall.assess(maintenant, sun.sunsetMillis, estimation, restante)
        val minutes = (verdict.marginSeconds / 60.0).roundToInt()
        return LevelFieldModel(
            label = label,
            value = Format.clock(arriveeMillis.toDouble()),
            referenceLabel = context.getString(R.string.field_level_arrival_sunset),
            referenceValue = Format.clock(sun.sunsetMillis.toDouble()),
            background = NightRenderer.verdictColor(
                when (verdict.verdict) {
                    Nightfall.Verdict.YES -> NightVerdict.YES
                    Nightfall.Verdict.TIGHT -> NightVerdict.TIGHT
                    Nightfall.Verdict.NO -> NightVerdict.NO
                },
            ),
            caption = margeCourte(context, minutes),
        )
    }

    /**
     * Le dénivelé : ce qui est monté, ce qui reste, et la part faite en barre.
     *
     * La barre n'est pas un ornement : « 1 240 m » ne dit rien tant qu'on ne sait pas si c'est
     * le tiers ou les neuf dixièmes. C'est la seule des six cases où la comparaison se fait
     * mieux à l'œil qu'au chiffre, parce que les deux nombres sont de même nature.
     */
    private fun denivele(context: Context, snapshot: GuidanceSnapshot, data: RideData): LevelFieldModel {
        val level = data.level
        val monte = level.elevationGain
        val restant = level.elevationRemaining
        val units = snapshot.units
        val total = (monte ?: 0.0) + (restant ?: 0.0)
        return LevelFieldModel(
            label = context.getString(R.string.field_level_ascent_label),
            value = monte?.let { Format.elevation(it, units) } ?: "--",
            referenceLabel = restant?.let { context.getString(R.string.field_level_ascent_remaining) },
            referenceValue = restant?.let { Format.elevation(it, units) },
            slices = if (monte == null || restant == null || total <= 0.0) {
                emptyList()
            } else {
                listOf(
                    LevelSlice((monte / total).toFloat(), MONTE),
                    LevelSlice((restant / total).toFloat(), RESTANT),
                )
            },
            emptyMessage = if (monte == null) context.getString(R.string.field_level_no_ascent) else null,
        )
    }

    /**
     * L'intensité : le facteur, la charge, et le mot qui les nomme.
     *
     * Ces deux nombres-là sont ceux du Karoo, calculés par lui : rien n'est refait ici. Ce que
     * la case ajoute est le mot — un facteur de 0,74 est une sortie d'endurance soutenue, et
     * c'est cela qu'on veut savoir en roulant, pas le nombre.
     */
    private fun intensite(context: Context, data: RideData): LevelFieldModel {
        val level = data.level
        val facteur = level.intensityFactor
        return LevelFieldModel(
            label = context.getString(R.string.field_level_intensity_label),
            value = facteur?.let { decimales(it, 2) } ?: "--",
            referenceLabel = level.trainingStressScore?.let { context.getString(R.string.field_level_intensity_load) },
            referenceValue = level.trainingStressScore?.let { entier(it) },
            caption = facteur?.let { context.getString(motDIntensite(it)) },
            emptyMessage = if (facteur == null) context.getString(R.string.field_level_no_intensity) else null,
        )
    }

    /**
     * Le temps par zone : la barre, et la zone où il s'est le plus passé.
     *
     * La barre garde l'ordre des zones et non celui des poids : c'est la **place** du gros de
     * la barre qui dit le niveau de la sortie, et l'ordonner par durée détruirait cette
     * lecture-là au profit d'un classement dont personne n'a besoin.
     */
    private fun zones(context: Context, data: RideData): LevelFieldModel {
        val level = data.level
        val parts = level.heartRateZoneShares
        val dominante = level.dominantHeartRateZone
        if (parts.isEmpty() || dominante == null) {
            return LevelFieldModel(
                label = context.getString(R.string.field_level_zones_label),
                value = "--",
                emptyMessage = context.getString(R.string.field_level_no_zones),
            )
        }
        val secondes = level.heartRateZoneSeconds.getOrNull(dominante - 1) ?: 0.0
        return LevelFieldModel(
            label = context.getString(R.string.field_level_zones_label),
            value = context.getString(R.string.field_level_zones_value, dominante),
            referenceLabel = context.getString(R.string.field_level_zones_spent),
            referenceValue = duree(context, secondes),
            slices = parts.mapIndexed { index, part ->
                LevelSlice(part, Zones.HEART_RATE_COLORS.getOrElse(index) { Zones.HEART_RATE_COLORS.last() })
            },
        )
    }

    /**
     * Le mot de la variabilité, aux seuils de la littérature d'entraînement.
     *
     * En deçà de 1,05 la puissance s'est tenue ; au-delà de 1,15 la sortie fut une succession
     * de relances. Entre les deux, du roulage ordinaire avec du terrain.
     */
    private fun motDeVariabilite(indice: Double): Int = when {
        indice < 1.05 -> R.string.field_level_power_smooth
        indice < 1.15 -> R.string.field_level_power_rolling
        else -> R.string.field_level_power_choppy
    }

    /** Les seuils habituels du facteur d'intensité : endurance, tempo, seuil, course. */
    private fun motDIntensite(facteur: Double): Int = when {
        facteur < 0.65 -> R.string.field_level_intensity_endurance
        facteur < 0.80 -> R.string.field_level_intensity_tempo
        facteur < 0.95 -> R.string.field_level_intensity_threshold
        else -> R.string.field_level_intensity_race
    }

    /** « 28 MIN D'AVANCE », ou « 1 H 12 D'AVANCE » au-delà de l'heure. */
    private fun margeCourte(context: Context, minutes: Int): String {
        val avance = minutes >= 0
        val valeur = abs(minutes)
        return if (valeur < 60) {
            context.getString(
                if (avance) R.string.field_level_arrival_ahead else R.string.field_level_arrival_behind,
                valeur,
            )
        } else {
            context.getString(
                if (avance) R.string.field_level_arrival_ahead_hours else R.string.field_level_arrival_behind_hours,
                valeur / 60,
                valeur % 60,
            )
        }
    }

    /** « 1 h 12 », ou « 24 min » en deçà de l'heure. */
    private fun duree(context: Context, secondes: Double): String {
        val minutes = (secondes / 60.0).roundToInt()
        return if (minutes < 60) {
            context.getString(R.string.field_level_duration_minutes, minutes)
        } else {
            context.getString(R.string.field_level_duration_hours, minutes / 60, minutes % 60)
        }
    }

    private fun entier(valeur: Double?): String =
        valeur?.let { String.format(Locale.getDefault(), "%d", it.roundToInt()) } ?: "--"

    private fun decimales(valeur: Double, chiffres: Int): String =
        String.format(Locale.getDefault(), "%.${chiffres}f", valeur)

    /** L'aperçu se joue une heure avant le coucher, là où le verdict se décide. */
    private const val APERCU_AVANT_COUCHER_MS = 62 * 60_000L

    /** La barre du dénivelé : ce qui est monté, et ce qui attend. */
    private const val MONTE = 0xFF0092DC.toInt()
    private const val RESTANT = 0xFF37474F.toInt()
}
