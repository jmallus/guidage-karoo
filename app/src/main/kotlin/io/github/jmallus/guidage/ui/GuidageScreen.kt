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
import io.github.jmallus.guidage.extension.AutonomyDataType
import io.github.jmallus.guidage.extension.BendDataType
import io.github.jmallus.guidage.extension.ClimbDataType
import io.github.jmallus.guidage.extension.ContextDataType
import io.github.jmallus.guidage.extension.DashboardDataType
import io.github.jmallus.guidage.extension.EffortDataType
import io.github.jmallus.guidage.extension.FieldReport
import io.github.jmallus.guidage.extension.PoiDataType
import io.github.jmallus.guidage.extension.ProfileDataType
import io.github.jmallus.guidage.extension.ResupplyDataType
import io.github.jmallus.guidage.extension.SurfaceDataType
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
    val fields by viewModel.fields.collectAsStateWithLifecycle()

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
            SettingsCard(settings, viewModel::update)
            FieldReportCard(fields)
            BasemapNotice()
        }
    }
}

/**
 * L'attribution du fond de carte.
 *
 * Elle n'est pas décorative : les données viennent d'OpenStreetMap, sous ODbL, et cette
 * licence exige que l'attribution soit portée là où l'œuvre dérivée est utilisée — ici, dans
 * l'application qui redistribue la carte à chaque APK. Le `NOTICE` du dépôt ne suffit pas :
 * personne n'ouvre le dépôt en roulant.
 *
 * Elle est en pied d'écran plutôt que sur la minicarte : celle-ci fait deux centimètres de
 * côté et chaque pixel y sert déjà à quelque chose. Le pied de l'écran de réglages est le
 * seul endroit de l'application où une ligne de texte est à la fois lisible et sans coût.
 */
@Composable
private fun BasemapNotice() {
    Text(
        text = stringResource(R.string.about_basemap),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
 * La place que le Karoo donne à chaque champ.
 *
 * Ce n'est pas un réglage mais un relevé : le banc d'essai de bureau ne peut pas deviner la
 * hauteur qui reste au champ une fois la bande d'état prélevée, et l'appareil est le seul à
 * la connaître. Trois nombres lus ici valent une mesure au pixel près sur une capture
 * d'écran — et évitent d'avoir à brancher un câble.
 *
 * Tous les champs posés y figurent, pas seulement celui qu'on cherche à régler : une carte
 * qui n'afficherait que le tableau de bord serait muette dans deux cas très différents — le
 * relevé n'a pas eu lieu, ou c'est un autre champ qui a été posé — sans permettre de les
 * distinguer.
 */
@Composable
private fun FieldReportCard(reports: List<FieldReport>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.field_report_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (reports.isEmpty()) {
                Text(
                    text = stringResource(R.string.field_report_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            reports.forEach { report ->
                Text(
                    text = stringResource(
                        if (report.preview) R.string.field_report_editor else R.string.field_report_ride,
                        fieldName(report.typeId),
                        stringResource(
                            R.string.field_report_line,
                            report.width,
                            report.height,
                            report.gridColumns,
                            report.gridRows,
                            report.textSize,
                        ),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Le nom que le sélecteur de champs du Karoo affiche, pour que la ligne se reconnaisse.
 *
 * Tous les champs y sont, et non les trois premiers écrits : la table s'était arrêtée à ceux-là
 * pendant que six autres s'ajoutaient, et le `else` les nommait tous « Prochain point
 * d'intérêt ». La carte servait alors exactement le contraire de ce pour quoi elle existe —
 * distinguer quel champ a été posé — en affirmant chaque fois le même, faux six fois sur neuf.
 *
 * Le `when` est donc exhaustif sur les identifiants connus et le dernier cas rend
 * l'identifiant brut plutôt qu'un nom : un champ ajouté et oublié ici s'affichera « autonomie »,
 * ce qui est laid mais vrai, au lieu de se déguiser en un autre.
 */
@Composable
private fun fieldName(typeId: String): String = when (typeId) {
    DashboardDataType.TYPE_ID -> stringResource(R.string.field_dashboard_name)
    ProfileDataType.TYPE_ID -> stringResource(R.string.field_profile_name)
    ClimbDataType.TYPE_ID -> stringResource(R.string.field_climb_name)
    EffortDataType.TYPE_ID -> stringResource(R.string.field_effort_name)
    BendDataType.TYPE_ID -> stringResource(R.string.field_bends_name)
    ContextDataType.TYPE_ID -> stringResource(R.string.field_context_name)
    SurfaceDataType.TYPE_ID -> stringResource(R.string.field_surface_name)
    ResupplyDataType.TYPE_ID -> stringResource(R.string.field_resupply_name)
    AutonomyDataType.TYPE_ID -> stringResource(R.string.field_autonomy_name)
    PoiDataType.TYPE_ID -> stringResource(R.string.field_poi_name)
    else -> typeId
}

/**
 * Les réglages, rangés par ce qu'ils touchent.
 *
 * Ils étaient en liste plate : trois interrupteurs de même poids, sans rien dire du champ que
 * chacun modifie. Or l'extension pose maintenant dix champs, et « Colorer le profil selon la
 * pente » n'en concerne que deux. Un réglage dont on ne sait pas ce qu'il change se laisse
 * dans son état d'usine, ce qui revient à ne pas l'avoir écrit.
 *
 * Chaque ligne porte donc une seconde ligne qui nomme les champs concernés — et non ce que
 * l'interrupteur fait, que son libellé dit déjà.
 */
@Composable
private fun SettingsCard(
    settings: GuidageSettings,
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

            SectionTitle(R.string.settings_section_dashboard)

            // Le réglage de portée du profil a disparu : l'échelle du champ est désormais
            // comprimée au loin, et montre tout ce qui reste sans qu'on ait à choisir.
            SwitchRow(
                label = stringResource(R.string.settings_guidance_map),
                hint = stringResource(R.string.settings_guidance_map_hint),
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
                hint = stringResource(R.string.settings_color_by_grade_hint),
                checked = settings.colorByGrade,
                onCheckedChange = { onChange(settings.copy(colorByGrade = it)) },
            )

            SectionTitle(R.string.settings_section_resupply)

            SwitchRow(
                label = stringResource(R.string.settings_water_only),
                hint = stringResource(R.string.settings_water_only_hint),
                checked = settings.resupplyWaterOnly,
                onCheckedChange = { onChange(settings.copy(resupplyWaterOnly = it)) },
            )

            SectionTitle(R.string.settings_section_alerts)

            SwitchRow(
                label = stringResource(R.string.settings_poi_alerts),
                hint = stringResource(R.string.settings_poi_alerts_hint),
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
private fun SectionTitle(label: Int) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * Une ligne de réglage : le libellé, ce qu'il touche, et l'interrupteur.
 *
 * L'interrupteur est sorti du flux du texte par un poids : sans cela, un libellé long le
 * repoussait hors de l'écran étroit du Karoo, où la colonne fait quatre cent quatre-vingts
 * points de large.
 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
