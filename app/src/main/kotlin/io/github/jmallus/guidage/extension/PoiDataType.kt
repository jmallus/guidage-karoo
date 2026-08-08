package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateNumericConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Champ numérique « Prochain point d'intérêt » : distance jusqu'au prochain POI
 * de l'itinéraire (ravitaillement, eau, contrôle…).
 *
 * L'affichage réutilise le formatage de distance du système, unités du coureur comprises.
 */
class PoiDataType(
    private val provider: GuidanceProvider,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            provider.snapshot
                .map { snapshot ->
                    val route = snapshot.state.route
                    val along = snapshot.state.distanceAlongRoute
                    if (route == null || along == null) null else Guidance.nextPoi(route, along)?.distance
                }
                .distinctUntilChanged()
                .collect { distance ->
                    if (distance == null) {
                        emitter.onNext(StreamState.NotAvailable)
                    } else {
                        emitter.onNext(
                            StreamState.Streaming(
                                DataPoint(dataTypeId, values = mapOf(DataType.Field.SINGLE to distance)),
                            ),
                        )
                    }
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Formate la valeur comme une distance de navigation (m puis km/mi).
        emitter.onNext(UpdateNumericConfig(formatDataTypeId = DataType.Type.DISTANCE_TO_NEXT_TURN))
    }

    companion object {
        const val TYPE_ID = "poi"
    }
}
