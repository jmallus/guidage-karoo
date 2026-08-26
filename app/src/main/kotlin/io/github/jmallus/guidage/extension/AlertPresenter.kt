package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.AlertKind
import io.github.jmallus.guidage.core.ClimbStatus
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.GuidanceAlert
import io.github.jmallus.guidage.core.Units
import io.hammerhead.karooext.models.InRideAlert

/**
 * Traduit une alerte de guidage en notification plein écran Karoo.
 */
class AlertPresenter(private val context: Context) {

    fun toInRideAlert(alert: GuidanceAlert, units: Units): InRideAlert? = when (alert.kind) {
        AlertKind.POI_APPROACH -> alert.poi?.let { poi ->
            build(
                id = alert.key,
                icon = R.drawable.ic_poi,
                title = PoiLabels.label(context, poi.poi),
                detail = context.getString(
                    R.string.alert_poi_detail,
                    Format.distance(poi.distance, units),
                ),
                background = R.color.alert_poi,
            )
        }
    }

    /**
     * Description d'une côte, pour l'action bonus.
     *
     * Elle servait aussi à l'annonce automatique du pied de côte, qui n'existe plus. L'action
     * bonus, elle, reste : demandée d'un bouton, elle arrive quand le coureur la veut et non
     * au milieu de ce qu'il regardait.
     */
    fun climbDetail(climb: ClimbStatus, units: Units): String = context.getString(
        R.string.alert_climb_detail,
        Format.distance(climb.climb.length, units),
        Format.grade(climb.climb.grade),
        Format.elevation(climb.climb.totalElevation, units),
    )

    fun build(id: String, icon: Int, title: String, detail: String?, background: Int) = InRideAlert(
        id = id,
        icon = icon,
        title = title,
        detail = detail,
        autoDismissMs = AUTO_DISMISS_MS,
        backgroundColor = background,
        textColor = R.color.alert_text,
    )

    private companion object {
        const val AUTO_DISMISS_MS = 8_000L
    }
}
