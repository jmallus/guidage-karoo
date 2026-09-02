package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.ArrivalEstimate
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.Nightfall
import io.github.jmallus.guidage.core.Sun
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.ui.NightFieldModel
import io.github.jmallus.guidage.ui.NightTimeline
import io.github.jmallus.guidage.ui.NightVerdict
import io.github.jmallus.guidage.ui.PreviewData
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ce qu'affiche le champ « Avant la nuit ».
 *
 * Extrait du champ pour la raison habituelle : le banc d'essai doit montrer ce que montrera
 * l'appareil. Le coucher est calculé sur place, depuis la position du coureur — l'extension
 * ne demande rien au réseau, elle n'en a pas le droit et n'en a pas besoin.
 */
object NightModels {

    /** Ce qui alimente le flux numérique : l'avance sur le coucher, en secondes signées. */
    fun assessment(
        snapshot: GuidanceSnapshot,
        rideData: RideData,
        nowMillis: Long,
    ): Nightfall.Assessment? {
        val state = snapshot.state
        if (state.route == null) return null
        val position = snapshot.location?.position ?: return null
        val sun = Sun.next(position, nowMillis) ?: return null
        val remaining = rideData.distanceRemaining ?: state.distanceRemaining ?: return null
        val estimate = estimate(state, rideData, nowMillis) ?: return null
        return Nightfall.assess(nowMillis, sun.sunsetMillis, estimate, remaining)
    }

    fun build(
        context: Context,
        snapshot: GuidanceSnapshot,
        rideData: RideData,
        preview: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): NightFieldModel {
        val title = context.getString(R.string.field_night_title)
        val substituted = preview && !snapshot.state.navigating
        val state = if (substituted) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }
        val data = if (substituted) PreviewData.effortSample else rideData
        val position = (if (substituted) PreviewData.location else snapshot.location)?.position

        if (state.route == null) {
            return NightFieldModel(title, emptyMessage = context.getString(R.string.field_no_route))
        }
        if (position == null) {
            return NightFieldModel(title, emptyMessage = context.getString(R.string.field_waiting_for_position))
        }
        val sun = Sun.next(position, nowMillis)
            ?: return NightFieldModel(title, emptyMessage = context.getString(R.string.field_night_no_sunset))

        // L'aperçu se place à l'heure où le champ a quelque chose à dire : une petite heure
        // avant le coucher du jour, là où le verdict se joue. À dix heures du matin il
        // afficherait un « oui » à neuf heures de marge, qui ne montre rien du champ.
        val now = if (substituted) sun.sunsetMillis - PREVIEW_LEAD_MS else nowMillis
        val footnote = context.getString(R.string.field_night_footnote, coordinates(context, position))

        val remaining = data.distanceRemaining ?: state.distanceRemaining
        val estimate = estimate(state, data, now)
        if (estimate == null || remaining == null) {
            return NightFieldModel(
                title = title,
                timeline = timeline(context, now, sun, null),
                footnote = footnote,
                emptyMessage = context.getString(R.string.field_effort_waiting),
            )
        }

        val assessment = Nightfall.assess(now, sun.sunsetMillis, estimate, remaining)
        val marginMinutes = (assessment.marginSeconds / 60.0).roundToInt()
        val uncertaintyMinutes = (assessment.uncertaintySeconds / 60.0).roundToInt()
        val units = snapshot.units
        return NightFieldModel(
            title = title,
            verdict = when (assessment.verdict) {
                Nightfall.Verdict.YES -> NightVerdict.YES
                Nightfall.Verdict.TIGHT -> NightVerdict.TIGHT
                Nightfall.Verdict.NO -> NightVerdict.NO
            },
            verdictLabel = context.getString(
                when (assessment.verdict) {
                    Nightfall.Verdict.YES -> R.string.field_night_yes
                    Nightfall.Verdict.TIGHT -> R.string.field_night_tight
                    Nightfall.Verdict.NO -> R.string.field_night_no
                },
            ),
            marginLabel = marginLabel(context, marginMinutes),
            // L'incertitude ne s'écrit qu'à partir de la minute : « ± 0 » promettrait une
            // précision que rien ne garantit.
            uncertaintyLabel = uncertaintyMinutes.takeIf { it >= 1 }
                ?.let { context.getString(R.string.field_night_uncertainty, it) },
            timeline = timeline(context, now, sun, estimate),
            worstCaseLabel = assessment.distanceLeftAtSunset?.let { context.getString(R.string.field_night_worst_label) },
            worstCaseValue = assessment.distanceLeftAtSunset?.let {
                context.getString(R.string.field_night_worst_value, Format.distance(it, units))
            },
            footnote = footnote,
        )
    }

    /**
     * « 8 min d'avance », ou « 9 h 43 d'avance » : au-delà de l'heure, les minutes seules
     * feraient un nombre à trois chiffres que personne ne convertit en roulant.
     */
    private fun marginLabel(context: Context, marginMinutes: Int): String {
        val ahead = marginMinutes >= 0
        val minutes = abs(marginMinutes)
        return if (minutes < 60) {
            context.getString(
                if (ahead) R.string.field_night_margin_ahead else R.string.field_night_margin_behind,
                minutes,
            )
        } else {
            context.getString(
                if (ahead) R.string.field_night_margin_ahead_hours else R.string.field_night_margin_behind_hours,
                minutes / 60,
                minutes % 60,
            )
        }
    }

    /**
     * L'arrivée : l'allure apprise d'abord, l'heure du Karoo à défaut.
     *
     * L'heure du Karoo n'a pas d'incertitude connue ; on la prend pour exacte, et le verdict
     * ne peut alors être que oui ou non. C'est moins bien qu'une fourchette, mais mieux
     * qu'un champ muet pendant les premières minutes de la sortie.
     */
    private fun estimate(state: GuidanceState, rideData: RideData, nowMillis: Long): ArrivalEstimate? =
        FieldModels.arrival(state, rideData)
            ?: rideData.arrivalTime
                ?.let { ArrivalEstimate((it - nowMillis) / 1_000.0, 0.0) }
                ?.takeIf { it.seconds >= 0.0 }

    /**
     * La frise, graduée du présent jusqu'un peu après le dernier des trois instants — la
     * nuit, ou l'arrivée au pire, si elle vient après.
     */
    private fun timeline(context: Context, nowMillis: Long, sun: Sun.Times, estimate: ArrivalEstimate?): NightTimeline {
        val worstArrival = estimate?.let { nowMillis + ((it.seconds + it.marginSeconds) * 1_000).toLong() } ?: nowMillis
        val last = max(sun.duskMillis ?: sun.sunsetMillis, max(worstArrival, sun.sunsetMillis))
        val span = ((last - nowMillis) * TIMELINE_HEADROOM).coerceAtLeast(60_000.0)
        fun fraction(millis: Long) = ((millis - nowMillis) / span).toFloat()

        val uncertaintyMinutes = estimate?.let { (it.marginSeconds / 60.0).roundToInt() }?.takeIf { it >= 1 }
        val arrivalMillis = estimate?.let { nowMillis + (it.seconds * 1_000).toLong() }
        return NightTimeline(
            nowLabel = context.getString(R.string.field_night_now),
            arrivalFraction = arrivalMillis?.let { fraction(it) },
            arrivalSpread = estimate?.let { (it.marginSeconds * 1_000 / span).toFloat() } ?: 0f,
            arrivalLabel = arrivalMillis?.let { millis ->
                val clock = Format.clock(millis.toDouble())
                if (uncertaintyMinutes != null) {
                    context.getString(R.string.field_night_arrival_spread, clock, uncertaintyMinutes)
                } else {
                    context.getString(R.string.field_night_arrival, clock)
                }
            },
            sunsetFraction = fraction(sun.sunsetMillis),
            sunsetLabel = context.getString(R.string.field_night_sunset, Format.clock(sun.sunsetMillis.toDouble())),
            duskFraction = sun.duskMillis?.let { fraction(it) },
            duskLabel = sun.duskMillis?.let { context.getString(R.string.field_night_dusk, Format.clock(it.toDouble())) },
        )
    }

    /** « 45,18° N · 5,72° E » — assez pour vérifier que le calcul parle bien d'ici. */
    private fun coordinates(context: Context, position: GeoPoint): String {
        val locale = Locale.getDefault()
        val lat = String.format(locale, "%.2f° %s", abs(position.lat), context.getString(
            if (position.lat >= 0) R.string.field_night_north else R.string.field_night_south,
        ))
        val lng = String.format(locale, "%.2f° %s", abs(position.lng), context.getString(
            if (position.lng >= 0) R.string.field_night_east else R.string.field_night_west,
        ))
        return "$lat · $lng"
    }

    /** L'aperçu se joue une heure avant le coucher, à quelques minutes près. */
    private const val PREVIEW_LEAD_MS = 62 * 60_000L

    /** Un peu d'air à droite du dernier instant, pour que son libellé tienne. */
    private const val TIMELINE_HEADROOM = 1.12
}
