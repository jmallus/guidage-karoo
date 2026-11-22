package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.ArrivalEstimate
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.Nightfall
import io.github.jmallus.guidage.core.Sun
import io.github.jmallus.guidage.core.Zones
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.ui.KarooColors
import io.github.jmallus.guidage.ui.LevelFieldModel
import io.github.jmallus.guidage.ui.LevelSlice
import io.github.jmallus.guidage.ui.NightRenderer
import io.github.jmallus.guidage.ui.NightVerdict
import io.github.jmallus.guidage.ui.PreviewData
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Les dix cases de bilan : ce que la sortie vaut depuis le départ.
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

    /** Puissance moyenne et normalisée. */
    PUISSANCE("bilan-puissance"),

    /** Heure d'arrivée, heure du coucher, et le verdict en aplat. */
    ARRIVEE("bilan-arrivee"),

    /** Dénivelé monté et restant. */
    DENIVELE("bilan-denivele"),

    /** La zone où il s'est le plus passé de temps, ce temps, et la barre des zones. */
    ZONES("bilan-zones"),

    /**
     * Ce qu'il reste de la réserve anaérobie.
     *
     * La seule qui soit un calcul et non un nombre du Karoo rangé autrement : le
     * modèle de puissance critique, tenu par l'extension au fil de la sortie.
     */
    RESERVE("bilan-reserve-w"),

    /** Le temps passé en selle, et celui qu'on a laissé aux arrêts. */
    ARRETS("bilan-arrets"),

    /** La charge de l'appareil, et ce qu'il en restera à l'arrivée. */
    BATTERIE("bilan-batterie"),
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
            Bilan.ZONES -> level.dominantHeartRateZone?.toDouble()
            // En joules, non en pourcentage : c'est ce qui s'enregistre et se compare d'une
            // sortie à l'autre, le pourcentage dépendant d'une échelle qui peut changer.
            Bilan.RESERVE -> level.wPrimeBalance
            Bilan.ARRETS -> level.stoppedSeconds
            Bilan.BATTERIE -> level.batteryPercent
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
        // En aperçu, la case montre toujours le bilan d'une sortie fictive, comme le tableau de
        // bord : sinon le sélecteur de champs afficherait « pas de cardiofréquencemètre », et
        // l'on choisirait la case sans l'avoir vue. Attendre un bilan vide pour substituer ne
        // marchait qu'au simulateur : sur l'appareil, le relevé porte toujours la FTP, la
        // charge et les compteurs de zones, et n'est donc jamais vide.
        val data = if (preview) PreviewData.levelRide else rideData
        return when (bilan) {
            Bilan.COEUR -> coeur(context, data)
            Bilan.PUISSANCE -> puissance(context, data)
            Bilan.ARRIVEE -> arrivee(context, snapshot, data, preview, nowMillis)
            Bilan.DENIVELE -> denivele(context, snapshot, data)
            Bilan.ZONES -> zones(context, data)
            Bilan.RESERVE -> reserve(context, data)
            Bilan.ARRETS -> arrets(context, data)
            Bilan.BATTERIE -> batterie(context, snapshot, data, preview, nowMillis)
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
     * La puissance : la moyenne en grand, la normalisée à côté, l'aplat de la zone de la moyenne.
     *
     * Leur écart dit si la sortie fut lisse ou hachée : il se lit d'un coup d'œil, les deux
     * chiffres étant voisins sur la même ligne. L'aplat est celui des zones de puissance du
     * Karoo, comme le cœur porte celui des zones cardiaques.
     */
    private fun puissance(context: Context, data: RideData): LevelFieldModel {
        val level = data.level
        val moyenne = level.averagePower
        return LevelFieldModel(
            label = context.getString(R.string.field_level_power_label),
            value = entier(moyenne),
            unit = context.getString(R.string.unit_watt),
            referenceLabel = context.getString(R.string.field_level_power_normalized),
            referenceValue = level.normalizedPower?.let { entier(it) },
            background = moyenne?.let { Zones.powerColor(it, data.powerZones) },
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
        )
    }

    /**
     * Le dénivelé : ce qui est monté en grand, ce qui reste à côté.
     *
     * « 1 240 m » ne dit rien tant qu'on ne sait pas ce qui reste : les deux nombres, de même
     * nature, se lisent ensemble.
     */
    private fun denivele(context: Context, snapshot: GuidanceSnapshot, data: RideData): LevelFieldModel {
        val level = data.level
        val monte = level.elevationGain
        val restant = restantJusquALArrivee(level.elevationRemaining, snapshot.state)
        val units = snapshot.units
        return LevelFieldModel(
            label = context.getString(R.string.field_level_ascent_label),
            value = monte?.let { Format.elevation(it, units) } ?: "--",
            referenceLabel = restant?.let { context.getString(R.string.field_level_ascent_remaining) },
            referenceValue = restant?.let { Format.elevation(it, units) },
            emptyMessage = if (monte == null) context.getString(R.string.field_level_no_ascent) else null,
        )
    }

    /**
     * Le dénivelé restant : celui du Karoo, sauf quand il dit zéro alors que le profil monte.
     *
     * Sur le vélo, le Karoo a publié « 0 m » toute une sortie de 608 m de montée, itinéraire
     * chargé : son `ELEVATION_REMAINING` ne se calcule pas pour tous les parcours. Le profil
     * de l'itinéraire, lui, est là — c'est celui qu'on dessine. Quand le Karoo répond zéro ou
     * rien et que le profil compte encore plus de [RESTANT_MINI_METRES] de montée devant le
     * coureur, c'est le profil qui parle. Le reste du temps, le chiffre du Karoo prime.
     */
    internal fun restantJusquALArrivee(duKaroo: Double?, state: GuidanceState): Double? {
        if (duKaroo != null && duKaroo > 0.0) return duKaroo
        val profil = state.route?.profile ?: return duKaroo
        val ici = state.distanceAlongRoute ?: return duKaroo
        val parProfil = profil.ascentBetween(ici, profil.totalDistance)
        return if (parProfil > RESTANT_MINI_METRES) parProfil else duKaroo
    }

    /** En deçà, la montée lue sur le profil tient du bruit d'altitude, pas d'une côte. */
    private const val RESTANT_MINI_METRES = 5.0

    /**
     * Le temps par zone : la zone où il s'est le plus passé, et ce temps.
     *
     * La barre empilée des zones est en pied de case, dans leur ordre et non dans celui des
     * durées : c'est la **place** du gros de la barre qui dit le niveau de la sortie.
     */
    private fun zones(context: Context, data: RideData): LevelFieldModel {
        val level = data.level
        val dominante = level.dominantHeartRateZone
        if (dominante == null) {
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
            slices = level.heartRateZoneShares.mapIndexed { index, part ->
                LevelSlice(part, Zones.HEART_RATE_COLORS.getOrElse(index) { Zones.HEART_RATE_COLORS.last() })
            },
        )
    }

    /**
     * La réserve anaérobie : ce qu'il en reste, en part et en kilojoules.
     *
     * Le pourcentage est en grand parce que c'est lui qui se lit en roulant — il reste des
     * cartouches, ou il n'en reste plus. Les kilojoules sont à côté pour qui sait ce qu'ils
     * valent chez lui ; la puissance critique retenue se lit dans les réglages.
     */
    private fun reserve(context: Context, data: RideData): LevelFieldModel {
        val level = data.level
        val part = level.wPrimeShare
        val reste = level.wPrimeBalance
        if (part == null || reste == null) {
            return LevelFieldModel(
                label = context.getString(R.string.field_level_reserve_label),
                value = "--",
                emptyMessage = context.getString(R.string.field_level_no_reserve),
            )
        }
        return LevelFieldModel(
            label = context.getString(R.string.field_level_reserve_label),
            value = (part * 100).roundToInt().toString(),
            unit = context.getString(R.string.unit_percent),
            referenceLabel = context.getString(R.string.field_level_reserve_left),
            referenceValue = context.getString(
                R.string.field_level_reserve_kilojoules,
                decimales(reste / 1_000.0, 1),
            ),
        )
    }

    /**
     * Les arrêts : le temps en selle en grand, celui qu'on a laissé aux ravitaillements à
     * côté.
     *
     * Le Karoo publie les deux durées, jamais leur différence — et c'est la différence qui
     * décide d'un brevet. Personne ne la regarde avant l'arrivée, où il est trop tard.
     */
    private fun arrets(context: Context, data: RideData): LevelFieldModel {
        val level = data.level
        val label = context.getString(R.string.field_level_stops_label)
        val roule = level.movingSeconds
        val arrete = level.stoppedSeconds
        if (roule == null || arrete == null) {
            return LevelFieldModel(
                label = label,
                value = "--",
                emptyMessage = context.getString(R.string.field_level_no_stops),
            )
        }
        return LevelFieldModel(
            label = label,
            value = duree(context, roule),
            referenceLabel = context.getString(R.string.field_level_stops_stopped),
            referenceValue = duree(context, arrete),
        )
    }

    /**
     * La batterie : la charge en grand, ce qu'il en restera à l'arrivée à côté.
     *
     * Le niveau seul ne répond à rien — quarante pour cent, c'est confortable à une heure de
     * l'arrivée et perdu d'avance à six. C'est la projection qui répond, et l'aplat qui la
     * porte, comme pour la nuit. Sans itinéraire chargé, la case reste la charge et sa pente :
     * on ne peut pas projeter sur une arrivée qu'on ne connaît pas.
     */
    private fun batterie(
        context: Context,
        snapshot: GuidanceSnapshot,
        data: RideData,
        preview: Boolean,
        nowMillis: Long,
    ): LevelFieldModel {
        val level = data.level
        val label = context.getString(R.string.field_level_battery_label)
        val charge = level.batteryPercent
            ?: return LevelFieldModel(
                label = label,
                value = "--",
                emptyMessage = context.getString(R.string.field_level_no_battery),
            )
        val base = LevelFieldModel(
            label = label,
            value = charge.roundToInt().toString(),
            unit = context.getString(R.string.unit_percent),
        )

        val pente = level.batteryPerHour ?: return base
        val heures = heuresJusquALArrivee(snapshot, data, preview, nowMillis) ?: return base
        val restante = (charge - pente * heures).coerceAtLeast(0.0)
        return base.copy(
            referenceLabel = context.getString(R.string.field_level_battery_at_arrival),
            referenceValue = context.getString(R.string.field_level_battery_percent, restante.roundToInt()),
            background = couleurDeBatterie(restante),
        )
    }

    /**
     * Combien d'heures il reste à rouler, de la même source que la case « Arrivée ».
     *
     * L'allure apprise d'abord, l'heure du Karoo à défaut : deux cases qui projetteraient sur
     * deux arrivées différentes se contrediraient sur la même page.
     */
    private fun heuresJusquALArrivee(
        snapshot: GuidanceSnapshot,
        data: RideData,
        preview: Boolean,
        nowMillis: Long,
    ): Double? {
        val state = if (preview && !snapshot.state.navigating) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }
        if (state.route == null) return null
        val secondes = FieldModels.arrival(state, data)?.seconds
            ?: data.arrivalTime?.let { (it - nowMillis) / 1_000.0 }
            ?: return null
        return (secondes / 3_600.0).takeIf { it > 0.0 }
    }

    /** L'aplat de la batterie, aux seuils où l'on commence à éteindre des choses. */
    private fun couleurDeBatterie(restante: Double): Int = when {
        restante >= 20.0 -> KarooColors.HIGH_VIS_GREEN
        restante >= 5.0 -> NightRenderer.TIGHT
        else -> KarooColors.UI_RED
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
}
