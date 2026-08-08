package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.RoutePoi
import io.hammerhead.karooext.models.Symbol

/** Libellés lisibles pour les types de points d'intérêt Karoo. */
object PoiLabels {

    fun label(context: Context, poi: RoutePoi): String =
        poi.name?.takeIf { it.isNotBlank() } ?: context.getString(typeLabel(poi.type))

    private fun typeLabel(type: String): Int = when (type) {
        Symbol.POI.Types.AID_STATION -> R.string.poi_aid_station
        Symbol.POI.Types.BIKE_SHOP -> R.string.poi_bike_shop
        Symbol.POI.Types.CAUTION -> R.string.poi_caution
        Symbol.POI.Types.COFFEE -> R.string.poi_coffee
        Symbol.POI.Types.CONTROL -> R.string.poi_control
        Symbol.POI.Types.CONVENIENCE_STORE, Symbol.POI.Types.SHOPPING -> R.string.poi_store
        Symbol.POI.Types.FIRST_AID, Symbol.POI.Types.HOSPITAL -> R.string.poi_first_aid
        Symbol.POI.Types.FOOD, Symbol.POI.Types.BAR -> R.string.poi_food
        Symbol.POI.Types.GAS_STATION -> R.string.poi_gas_station
        Symbol.POI.Types.PARKING, Symbol.POI.Types.BIKE_PARKING -> R.string.poi_parking
        Symbol.POI.Types.REST_STOP -> R.string.poi_rest_stop
        Symbol.POI.Types.RESTROOM -> R.string.poi_restroom
        Symbol.POI.Types.SUMMIT -> R.string.poi_summit
        Symbol.POI.Types.VIEWPOINT -> R.string.poi_viewpoint
        Symbol.POI.Types.WATER -> R.string.poi_water
        else -> R.string.poi_generic
    }
}
