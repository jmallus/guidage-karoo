package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.EffortBudget
import io.github.jmallus.guidage.core.EffortEstimate
import io.github.jmallus.guidage.core.EffortItem
import io.github.jmallus.guidage.core.EffortProgress
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.Pacing
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.ui.EffortFieldModel
import io.github.jmallus.guidage.ui.EffortSlice
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.PreviewData
import kotlin.math.roundToInt

/**
 * Ce qu'affiche le champ « Budget d'effort ».
 *
 * Extrait du champ pour que le banc d'essai montre ce que montrera l'appareil, plutôt qu'une
 * seconde écriture qui dériverait.
 */
object EffortModels {

    fun estimate(state: GuidanceState, rideData: RideData): EffortEstimate? {
        val route = state.route ?: return null
        val along = state.distanceAlongRoute ?: return null
        val remaining = rideData.distanceRemaining ?: state.distanceRemaining ?: return null
        return EffortBudget.estimate(
            pace = rideData.pace,
            terrain = Pacing.terrain(route.profile, along, remaining),
            climbs = EffortBudget.remainingClimbs(route, along),
        )
    }

    fun build(
        context: Context,
        rawState: GuidanceState,
        rideData: RideData,
        preview: Boolean,
    ): EffortFieldModel {
        val state = if (preview && !rawState.navigating) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            rawState
        }
        val data = if (preview && !rawState.navigating) PreviewData.effortSample else rideData

        val estimate = estimate(state, data)
            ?: return EffortFieldModel(
                label = context.getString(R.string.field_effort_label),
                emptyMessage = context.getString(
                    if (state.navigating) R.string.field_effort_waiting else R.string.field_no_route,
                ),
            )

        val total = estimate.kilojoules
        val progress = EffortBudget.progress(
            spentKilojoules = data.energyOutput,
            estimate = estimate,
            distanceAlongRoute = state.distanceAlongRoute,
            totalDistance = state.route?.totalDistance,
        )
        return EffortFieldModel(
            total = total.roundToInt().toString(),
            unit = context.getString(R.string.unit_kilojoule),
            label = context.getString(R.string.field_effort_label),
            slices = estimate.items.map { item -> slice(context, item, total) },
            spentShare = progress?.effortFraction?.toFloat(),
            progressCaption = progress?.let { caption(context, it) },
        )
    }

    /**
     * La ligne qui rapproche l'effort des kilomètres.
     *
     * Les deux parts côte à côte, et rien de plus : nommer l'écart — « en avance », « en
     * retard » — reviendrait à porter un jugement que le coureur fait mieux que nous, sachant
     * ce qu'il a dans les jambes. Deux nombres se comparent tout seuls.
     */
    private fun caption(context: Context, progress: EffortProgress): String? {
        val effort = progress.effortFraction ?: return null
        val distance = progress.distanceFraction
            ?: return context.getString(R.string.field_effort_share_only, percent(effort))
        return context.getString(R.string.field_effort_share, percent(effort), percent(distance))
    }

    private fun percent(fraction: Double): Int = (fraction * 100).roundToInt().coerceIn(0, 100)

    /**
     * Une tranche de la barre, et sa ligne de détail quand elle en mérite une.
     *
     * Le roulant et les faux plats n'en portent pas : ce sont des restes, pas des rendez-vous.
     * Les côtes, si — c'est en bosses que le coureur pense son parcours, pas en mètres de
     * dénivelé cumulés.
     */
    private fun slice(context: Context, item: EffortItem, total: Double): EffortSlice {
        val share = if (total > 0.0) (item.kilojoules / total).toFloat() else 0f
        val number = item.climbNumber
        val grade = item.grade
        return if (number != null && grade != null) {
            EffortSlice(
                share = share,
                color = FieldPalette.gradeColor(grade),
                label = context.getString(R.string.field_effort_climb, number, Format.grade(grade)),
                value = context.getString(R.string.field_effort_kilojoules, item.kilojoules.roundToInt()),
            )
        } else {
            EffortSlice(share = share, color = FieldPalette.NEUTRAL)
        }
    }
}
