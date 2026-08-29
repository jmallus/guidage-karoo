package io.github.jmallus.guidage.extension

import android.util.Log
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.AlertEngine
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideDataProvider
import io.github.jmallus.guidage.karoo.RoadMapRepository
import io.github.jmallus.guidage.karoo.consumerFlow
import io.github.jmallus.guidage.settings.SettingsRepository
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Extension « Guidage » : champs de données et alertes construits à partir de
 * l'itinéraire chargé dans le Karoo. Tout est calculé localement, sans réseau.
 */
class GuidageExtension : KarooExtension(EXTENSION_ID, VERSION) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var karooSystem: KarooSystemService
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var provider: GuidanceProvider
    private lateinit var rideDataProvider: RideDataProvider
    private lateinit var alertPresenter: AlertPresenter
    private lateinit var roadMapRepository: RoadMapRepository

    override val types: List<DataTypeImpl> by lazy {
        listOf(
            DashboardDataType(provider, rideDataProvider, settingsRepository, roadMapRepository, extension),
            ProfileDataType(provider, settingsRepository, extension),
            ClimbDataType(provider, extension),
            EffortDataType(provider, rideDataProvider, extension),
            BendDataType(provider, extension),
            ContextDataType(provider, rideDataProvider, settingsRepository, extension),
            SurfaceDataType(provider, roadMapRepository, extension),
            ResupplyDataType(provider, settingsRepository, extension),
            AutonomyDataType(provider, rideDataProvider, settingsRepository, extension),
            PoiDataType(provider, extension),
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(this)
        settingsRepository = SettingsRepository(this)
        provider = GuidanceProvider(karooSystem, scope)
        rideDataProvider = RideDataProvider(karooSystem, scope)
        alertPresenter = AlertPresenter(this)
        roadMapRepository = RoadMapRepository(this)

        // Le déballage de la carte lit et réécrit une trentaine de méga-octets : hors du
        // fil principal, et une seule fois dans la vie de l'installation.
        scope.launch { roadMapRepository.unpackIfNeeded() }

        karooSystem.connect { connected ->
            Log.i(TAG, "Karoo system connected: $connected")
        }

        scope.launch { runAlerts() }
    }

    override fun onDestroy() {
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }

    /**
     * Surveille la progression sur l'itinéraire et déclenche les annonces.
     * Les alertes ne sont émises que pendant l'enregistrement d'une sortie.
     */
    private suspend fun runAlerts() {
        val engine = AlertEngine(resupplyTypes = ResupplyTypes.ALL)
        val rideState = karooSystem.consumerFlow<RideState>().onStart { emit(RideState.Idle) }

        combine(
            provider.snapshot,
            settingsRepository.settings,
            rideState,
        ) { snapshot, settings, ride ->
            Triple(snapshot, settings, ride)
        }.collect { (snapshot, settings, ride) ->
            engine.updateSettings(settings.alerts)
            // L'annonce compte les mêmes points que le champ : sans quoi la voix nommerait un
            // dernier ravitaillement que l'écran, réglé sur l'eau seule, ne montre pas.
            engine.resupplyTypes = ResupplyTypes.of(settings.resupplyWaterOnly)
            if (ride !is RideState.Recording) return@collect

            engine.evaluate(snapshot.state).forEach { alert ->
                alertPresenter.toInRideAlert(alert, snapshot.units)?.let { inRideAlert ->
                    Log.d(TAG, "Dispatching alert ${alert.key}")
                    karooSystem.dispatch(inRideAlert)
                }
            }
        }
    }

    /** Action assignable à un bouton : annonce à la demande la prochaine côte. */
    override fun onBonusAction(actionId: String) {
        if (actionId != ACTION_NEXT_CLIMB) {
            Log.w(TAG, "Unknown bonus action $actionId")
            return
        }
        scope.launch {
            val snapshot: GuidanceSnapshot = provider.snapshot.first()
            val route = snapshot.state.route
            val along = snapshot.state.distanceAlongRoute
            val status = if (route != null && along != null) Guidance.climbStatus(route, along) else null

            val alert = if (status == null) {
                alertPresenter.build(
                    id = "guidage-next-climb",
                    icon = R.drawable.ic_climb,
                    title = getString(R.string.field_no_climb_ahead),
                    detail = null,
                    background = R.color.alert_summit,
                )
            } else {
                val distance = if (status.onClimb) status.distanceToTop else status.distanceToStart
                alertPresenter.build(
                    id = "guidage-next-climb",
                    icon = R.drawable.ic_climb,
                    title = getString(
                        if (status.onClimb) R.string.alert_summit_title else R.string.alert_climb_title,
                        Format.distance(distance, snapshot.units),
                    ),
                    detail = alertPresenter.climbDetail(status, snapshot.units),
                    background = R.color.alert_climb,
                )
            }
            karooSystem.dispatch(alert)
        }
    }

    companion object {
        /** Doit correspondre à l'attribut `id` de res/xml/extension_info.xml. */
        const val EXTENSION_ID = "guidage"
        const val ACTION_NEXT_CLIMB = "prochaine-cote"
        private const val VERSION = "1.0"
        private const val TAG = "GuidageExtension"
    }
}
