package io.github.jmallus.guidage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jmallus.guidage.MainViewModel
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.GuidanceZoneType
import io.github.jmallus.guidage.core.Units
import io.github.jmallus.guidage.extension.FieldReport
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.settings.GuidageSettings
import kotlin.math.roundToInt

/**
 * Écran unique : état du guidage en haut, réglages en dessous.
 */
@Composable
fun GuidageScreen(viewModel: MainViewModel) {
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (viewModel.version.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.app_version, viewModel.version),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            StatusCard(connected, snapshot)
            SettingsCard(settings, snapshot.units, viewModel::update)
            FieldReportCard(viewModel.fieldInRide, viewModel.fieldInEditor)
        }
    }
}

@Composable
private fun StatusCard(connected: Boolean, snapshot: GuidanceSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    if (connected) R.string.status_connected else R.string.status_disconnected,
                ),
                style = MaterialTheme.typography.labelLarge,
            )

            val state = snapshot.state
            val route = state.route
            if (route == null) {
                Text(stringResource(R.string.status_no_route))
                return@Column
            }

            Text(text = route.name, style = MaterialTheme.typography.titleMedium)
            state.distanceRemaining?.let {
                Text(
                    stringResource(
                        R.string.status_remaining,
                        Format.longDistance(it, snapshot.units),
                    ),
                )
            }

            val along = state.distanceAlongRoute
            val climb = if (along != null) Guidance.climbStatus(route, along) else null
            Text(
                text = if (climb == null) {
                    stringResource(R.string.field_no_climb_ahead)
                } else if (climb.onClimb) {
                    stringResource(
                        R.string.status_climb_current,
                        Format.distance(climb.distanceToTop, snapshot.units),
                        Format.elevation(climb.elevationToTop, snapshot.units),
                    )
                } else {
                    stringResource(
                        R.string.status_climb_next,
                        Format.distance(climb.distanceToStart, snapshot.units),
                        Format.distance(climb.climb.length, snapshot.units),
                        Format.grade(climb.climb.grade),
                    )
                },
            )

            val poi = if (along != null) Guidance.nextPoi(route, along) else null
            poi?.let {
                Text(
                    stringResource(
                        R.string.status_next_poi,
                        it.poi.name ?: stringResource(R.string.poi_generic),
                        Format.distance(it.distance, snapshot.units),
                    ),
                )
            }
        }
    }
}

/**
 * La place que le Karoo donne au champ.
 *
 * Ce n'est pas un réglage mais un relevé, et il ne sert qu'une fois : le banc d'essai de
 * bureau ne peut pas deviner la hauteur qui reste au champ une fois la bande d'état
 * prélevée, et l'appareil est le seul à la connaître. Trois nombres lus ici valent une
 * mesure au pixel près sur une capture d'écran — et évitent d'avoir à brancher un câble.
 */
@Composable
private fun FieldReportCard(inRide: FieldReport?, inEditor: FieldReport?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.field_report_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (inRide == null && inEditor == null) {
                Text(
                    text = stringResource(R.string.field_report_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            inRide?.let { Text(stringResource(R.string.field_report_ride, describe(it))) }
            inEditor?.let { Text(stringResource(R.string.field_report_editor, describe(it))) }
        }
    }
}

@Composable
private fun describe(report: FieldReport): String = stringResource(
    R.string.field_report_line,
    report.width,
    report.height,
    report.gridColumns,
    report.gridRows,
    report.textSize,
)

@Composable
private fun SettingsCard(
    settings: GuidageSettings,
    units: Units,
    onChange: (GuidageSettings) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium,
            )

            SliderRow(
                label = stringResource(
                    R.string.settings_lookahead,
                    Format.longDistance(settings.lookaheadMeters, units),
                ),
                value = settings.lookaheadMeters.toFloat(),
                range = 1_000f..15_000f,
                steps = 13,
                onValueChange = { onChange(settings.copy(lookaheadMeters = it.toDouble())) },
            )

            SwitchRow(
                label = stringResource(R.string.settings_guidance_map),
                checked = settings.guidanceZone == GuidanceZoneType.MAP,
                onCheckedChange = { useMap ->
                    onChange(
                        settings.copy(
                            guidanceZone = if (useMap) GuidanceZoneType.MAP else GuidanceZoneType.PROFILE,
                        ),
                    )
                },
            )

            SwitchRow(
                label = stringResource(R.string.settings_color_by_grade),
                checked = settings.colorByGrade,
                onCheckedChange = { onChange(settings.copy(colorByGrade = it)) },
            )

            SwitchRow(
                label = stringResource(R.string.settings_poi_alerts),
                checked = settings.alerts.poiEnabled,
                onCheckedChange = { onChange(settings.copy(alerts = settings.alerts.copy(poiEnabled = it))) },
            )
            if (settings.alerts.poiEnabled) {
                SliderRow(
                    label = stringResource(
                        R.string.settings_poi_distance,
                        "${settings.alerts.poiDistance.roundToInt()} m",
                    ),
                    value = settings.alerts.poiDistance.toFloat(),
                    range = 100f..2_000f,
                    steps = 18,
                    onValueChange = {
                        onChange(settings.copy(alerts = settings.alerts.copy(poiDistance = it.toDouble())))
                    },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.coerceIn(range),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}
