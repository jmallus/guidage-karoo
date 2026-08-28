package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.ClimbStatus
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.ProfileWindow
import io.github.jmallus.guidage.core.Resupply
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.settings.GuidageSettings
import io.github.jmallus.guidage.ui.ClimbFieldModel
import io.github.jmallus.guidage.ui.PreviewData
import io.github.jmallus.guidage.ui.ProfileFieldModel
import io.github.jmallus.guidage.ui.ResupplyFieldModel
import io.github.jmallus.guidage.ui.ResupplyStop
import io.github.jmallus.guidage.ui.StopKind

/**
 * Ce qu'affichent les champs « Profil à venir » et « Prochaine côte ».
 *
 * Extrait des champs eux-mêmes pour la même raison que l'avait été le tableau de bord : une
 * seule construction sert à l'extension et au banc d'essai, de sorte que ce que montre celui-ci
 * est ce que montrera l'appareil. Recopier ces quarante lignes dans le simulateur reviendrait
 * à écrire l'affichage une seconde fois — et deux écritures d'une même chose finissent
 * toujours par diverger sans que rien ne le signale.
 */
object FieldModels {

    /**
     * Le profil à venir : tout ce qui reste de l'itinéraire.
     *
     * [preview] est vrai dans le sélecteur de champs du Karoo, où l'on substitue un parcours
     * d'exemple : un champ figé sur « — » ne dit rien de ce qu'il donnera en roulant.
     */
    fun profile(
        context: Context,
        snapshot: GuidanceSnapshot,
        settings: GuidageSettings,
        preview: Boolean,
    ): ProfileFieldModel {
        val state = substituted(snapshot, preview)
        val route = state.route
        val along = state.distanceAlongRoute
        if (route == null || along == null) {
            return ProfileFieldModel(
                window = ProfileWindow(emptyList(), 0.0, 0.0, 0.0, 0.0),
                emptyMessage = context.getString(R.string.field_no_route),
                colorByGrade = settings.colorByGrade,
                units = snapshot.units,
            )
        }

        // La position est arrondie pour éviter de redessiner à chaque mètre parcouru.
        val quantized = (along / POSITION_STEP_METERS).toInt() * POSITION_STEP_METERS
        val window = Guidance.profileToFinish(route, quantized)
        val units = snapshot.units
        val ascent = route.profile?.ascentBetween(window.start, window.end)

        return ProfileFieldModel(
            window = window,
            climbs = route.climbs,
            ascentLabel = ascent?.let { "+${Format.elevation(it, units)}" },
            rangeLabel = window.distanceSpan.takeIf { it > 0.0 }?.let { Format.longDistance(it, units) },
            emptyMessage = context.getString(R.string.field_no_route),
            colorByGrade = settings.colorByGrade,
            units = units,
        )
    }

    /** La prochaine côte, ou celle en cours. */
    fun climb(context: Context, snapshot: GuidanceSnapshot, preview: Boolean): ClimbFieldModel {
        val state = substituted(snapshot, preview)
        val status = climbStatus(state)
            ?: return ClimbFieldModel(
                primary = "—",
                caption = context.getString(
                    if (state.navigating) R.string.field_no_climb_ahead else R.string.field_no_route,
                ),
            )

        val units = snapshot.units
        return if (status.onClimb) {
            ClimbFieldModel(
                header = context.getString(R.string.field_climb_in_progress),
                primary = Format.distance(status.distanceToTop, units),
                secondaryTop = Format.grade(status.climb.grade),
                secondaryBottom = "+${Format.elevation(status.elevationToTop, units)}",
                progress = status.progress.toFloat(),
                accentGrade = status.climb.grade,
            )
        } else {
            ClimbFieldModel(
                header = context.getString(R.string.field_climb_number, status.number, status.totalClimbs),
                primary = Format.distance(status.distanceToStart, units),
                secondaryTop = Format.grade(status.climb.grade),
                secondaryBottom = climbSizeLabel(status, units),
                accentGrade = status.climb.grade,
            )
        }
    }

    /**
     * La réserve : ce que l'itinéraire offre encore, et ce qu'il n'offre plus.
     *
     * Le modèle porte **tout** l'itinéraire et non la portion à venir. Ce n'est pas de la
     * générosité : le vide qui suit le dernier point ne se voit que comparé aux points qui le
     * précèdent, et une ligne qui commencerait au coureur montrerait un vide sans montrer
     * qu'il est anormal.
     */
    fun resupply(
        context: Context,
        snapshot: GuidanceSnapshot,
        preview: Boolean,
        types: Set<String>,
    ): ResupplyFieldModel {
        val state = substituted(snapshot, preview)
        val route = state.route
        val along = state.distanceAlongRoute
        if (route == null || along == null) {
            return ResupplyFieldModel(emptyMessage = context.getString(R.string.field_no_route))
        }

        val units = snapshot.units
        val length = route.totalDistance.takeIf { it > 0.0 }
            ?: return ResupplyFieldModel(emptyMessage = context.getString(R.string.field_no_route))
        val points = Resupply.stops(route, types)
        if (points.isEmpty()) {
            return ResupplyFieldModel(emptyMessage = context.getString(R.string.field_resupply_none))
        }

        val status = Resupply.status(route, along, types)
        val crossing = status.crossing
        val lastUseful = crossing?.lastPoi?.poi
        val next = status.next?.poi

        return ResupplyFieldModel(
            warningLabel = crossing?.let { context.getString(R.string.field_resupply_last_before) },
            warningValue = crossing?.let { Format.distance(it.length, units) },
            sinceLabel = context.getString(R.string.field_resupply_since),
            sinceValue = status.sinceLast?.let { Format.distance(it, units) },
            stops = points.map { poi ->
                ResupplyStop(
                    fraction = (poi.distanceAlongRoute / length).toFloat(),
                    kind = when {
                        poi === lastUseful -> StopKind.LAST_USEFUL
                        poi === next -> StopKind.NEXT
                        poi.distanceAlongRoute <= along -> StopKind.PASSED
                        else -> StopKind.AHEAD
                    },
                )
            },
            position = (along / length).toFloat(),
            // La traversée commence au dernier point utile, ou sous les roues du coureur quand
            // il n'y a plus rien devant : il est alors déjà dedans.
            dryFrom = crossing?.let { (((it.lastPoi?.poi?.distanceAlongRoute ?: along)) / length).toFloat() },
            lastUsefulCaption = lastUseful?.let { context.getString(R.string.field_resupply_last_useful) },
            dryCaption = crossing?.let { context.getString(R.string.field_resupply_dry) },
            nextLabel = context.getString(R.string.field_resupply_next),
            nextName = next?.let { PoiLabels.label(context, it) },
            nextValue = status.next?.let { Format.distance(it.distance, units) },
        )
    }

    fun climbStatus(state: GuidanceState): ClimbStatus? {
        val route = state.route ?: return null
        val along = state.distanceAlongRoute ?: return null
        return Guidance.climbStatus(route, along)
    }

    /** Le parcours d'exemple, quand le champ est affiché hors sortie. */
    private fun substituted(snapshot: GuidanceSnapshot, preview: Boolean): GuidanceState =
        if (preview && !snapshot.state.navigating) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }

    private fun climbSizeLabel(status: ClimbStatus, units: Units): String =
        "${Format.distance(status.climb.length, units)} · +${Format.elevation(status.climb.totalElevation, units)}"

    private const val POSITION_STEP_METERS = 10.0
}
