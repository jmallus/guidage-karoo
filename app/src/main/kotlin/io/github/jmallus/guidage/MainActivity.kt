package io.github.jmallus.guidage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jmallus.guidage.extension.FieldReport
import io.github.jmallus.guidage.extension.FieldReportStore
import io.github.jmallus.guidage.karoo.GuidanceProvider
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.settings.GuidageSettings
import io.github.jmallus.guidage.settings.SettingsRepository
import io.github.jmallus.guidage.ui.GuidageScreen
import io.github.jmallus.guidage.ui.GuidageTheme
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Écran de configuration de l'extension, ouvert depuis le launcher du Karoo.
 * Il montre aussi l'état du guidage courant, pratique pour vérifier avant de partir.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GuidageTheme {
                val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(this))
                GuidageScreen(viewModel)
            }
        }
    }
}

class MainViewModel(
    private val karooSystem: KarooSystemService,
    private val settingsRepository: SettingsRepository,
    fieldReports: FieldReportStore,
    /**
     * La version installée, affichée en tête de l'écran.
     *
     * Sans elle, rien ne distingue une fonction absente d'une fonction présente mais muette :
     * on cherche un défaut dans le code alors que l'appareil porte encore la construction
     * d'avant. Une ligne suffit à trancher.
     */
    val version: String,
) : ViewModel() {

    private val provider = GuidanceProvider(karooSystem, viewModelScope)

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    val snapshot: StateFlow<GuidanceSnapshot> = provider.snapshot

    val settings: StateFlow<GuidageSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.read())

    /**
     * La place que le système a donnée au champ, lue une fois à l'ouverture de l'écran.
     *
     * Pas de flux : le champ et cet écran ne s'affichent jamais ensemble, et rien ne peut
     * donc changer sous les yeux du lecteur.
     */
    val fieldInRide: FieldReport? = fieldReports.read(preview = false)
    val fieldInEditor: FieldReport? = fieldReports.read(preview = true)

    init {
        karooSystem.connect { connected -> _connected.value = connected }
    }

    fun update(settings: GuidageSettings) = settingsRepository.write(settings)

    override fun onCleared() {
        karooSystem.disconnect()
        super.onCleared()
    }

    companion object {
        fun factory(activity: ComponentActivity) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val context = activity.applicationContext
                val version = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull().orEmpty()
                return MainViewModel(
                    KarooSystemService(context),
                    SettingsRepository(context),
                    FieldReportStore(context),
                    version,
                ) as T
            }
        }
    }
}
