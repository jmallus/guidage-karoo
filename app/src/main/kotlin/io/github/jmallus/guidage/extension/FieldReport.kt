package io.github.jmallus.guidage.extension

import android.content.Context
import android.content.SharedPreferences
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Ce que le système a réellement alloué à un champ, relevé à sa dernière ouverture.
 *
 * @property typeId le champ concerné, tel que déclaré dans `extension_info.xml`
 * @property preview vrai dans l'éditeur de pages, faux en roulant
 * @property width largeur en pixels
 * @property height hauteur en pixels, bandeau d'état du Karoo déjà déduit
 * @property gridColumns empan horizontal sur la grille de soixante
 * @property gridRows empan vertical sur la grille de soixante
 * @property textSize corps que le Karoo emploie lui-même pour un champ numérique de cette
 *   taille, en sp — la référence de l'appareil, plus sûre que celle d'une maquette
 */
data class FieldReport(
    val typeId: String,
    val preview: Boolean,
    val width: Int,
    val height: Int,
    val gridColumns: Int,
    val gridRows: Int,
    val textSize: Int,
)

/**
 * Le carnet des champs.
 *
 * Ces dimensions n'existent que sur l'appareil : `ViewConfig` les donne à l'extension, et à
 * personne d'autre. Le banc d'essai de bureau, lui, ne peut que les recopier — encore
 * faut-il les connaître, et il a longtemps supposé un écran entier, huit cents points de
 * haut, alors que le Karoo garde une bande pour l'heure et la batterie. La mise en page du
 * simulateur était donc plus aérée que la vraie.
 *
 * Les relever demandait jusqu'ici `adb`, c'est-à-dire un ordinateur outillé et un câble.
 * Les écrire ici les fait ressortir dans l'écran de configuration : le champ s'ouvre une
 * fois, on lit trois nombres sur l'appareil lui-même.
 *
 * Chaque champ tient sa propre ligne, et l'édition est séparée de la sortie. C'est ce qui
 * rend le carnet lisible quand il est vide : une carte muette ne dit pas si le relevé a
 * échoué ou si le champ attendu n'a simplement jamais été posé sur une page — un carnet qui
 * montre les autres champs, si.
 */
class FieldReportStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(typeId: String, config: ViewConfig) {
        val value = listOf(
            config.viewSize.first,
            config.viewSize.second,
            config.gridSize.first,
            config.gridSize.second,
            config.textSize,
        ).joinToString(SEPARATOR)
        prefs.edit().putString(key(typeId, config.preview), value).apply()
    }

    /**
     * Les relevés, relus à chaque fois qu'on écoute et à chaque écriture.
     *
     * Un simple appel à [all] à la construction de l'écran ne suffit pas : l'activité de
     * configuration est en `singleTop` et son ViewModel lui survit, si bien qu'y revenir
     * après avoir ouvert le champ reservait la lecture d'avant — celle d'un carnet encore
     * vide. C'est exactement le cas où le relevé a lieu : entre deux passages sur l'écran.
     */
    val reports: Flow<List<FieldReport>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(all())
        }
        trySend(all())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    /** Tous les relevés connus, le tableau de bord d'abord puisque c'est lui qu'on règle. */
    fun all(): List<FieldReport> = prefs.all.entries
        .mapNotNull { (key, value) -> parse(key, value as? String ?: return@mapNotNull null) }
        .sortedWith(compareBy({ it.typeId != DashboardDataType.TYPE_ID }, { it.preview }))

    private fun parse(key: String, value: String): FieldReport? {
        val typeId = key.substringBeforeLast(MODE_SEPARATOR, missingDelimiterValue = "")
        val mode = key.substringAfterLast(MODE_SEPARATOR)
        if (typeId.isEmpty() || (mode != RIDE && mode != EDITOR)) return null
        val parts = value.split(SEPARATOR).mapNotNull(String::toIntOrNull)
        if (parts.size != FIELDS || parts[0] <= 0 || parts[1] <= 0) return null
        return FieldReport(
            typeId = typeId,
            preview = mode == EDITOR,
            width = parts[0],
            height = parts[1],
            gridColumns = parts[2],
            gridRows = parts[3],
            textSize = parts[4],
        )
    }

    private fun key(typeId: String, preview: Boolean) =
        "$typeId$MODE_SEPARATOR${if (preview) EDITOR else RIDE}"

    private companion object {
        const val PREFS_NAME = "guidage-champ"
        const val MODE_SEPARATOR = "@"
        const val SEPARATOR = ","
        const val RIDE = "sortie"
        const val EDITOR = "edition"
        const val FIELDS = 5
    }
}
