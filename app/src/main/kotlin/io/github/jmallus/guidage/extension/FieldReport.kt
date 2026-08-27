package io.github.jmallus.guidage.extension

import android.content.Context
import android.content.SharedPreferences
import io.hammerhead.karooext.models.ViewConfig

/**
 * Ce que le système a réellement alloué au champ, relevé à sa dernière ouverture.
 *
 * @property width largeur en pixels
 * @property height hauteur en pixels, bandeau d'état du Karoo déjà déduit
 * @property gridColumns empan horizontal sur la grille de soixante
 * @property gridRows empan vertical sur la grille de soixante
 * @property textSize corps que le Karoo emploie lui-même pour un champ numérique de cette
 *   taille, en sp — la référence de l'appareil, plus sûre que celle d'une maquette
 */
data class FieldReport(
    val width: Int,
    val height: Int,
    val gridColumns: Int,
    val gridRows: Int,
    val textSize: Int,
)

/**
 * Le carnet du champ.
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
 * La vue d'édition et la vue en roulant sont notées séparément : rien ne garantit que le
 * système alloue la même place aux deux, et se tromper de mesure ramènerait le banc d'essai
 * exactement là d'où il vient.
 */
class FieldReportStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(config: ViewConfig) {
        prefs.edit()
            .putInt(key(config.preview, WIDTH), config.viewSize.first)
            .putInt(key(config.preview, HEIGHT), config.viewSize.second)
            .putInt(key(config.preview, COLUMNS), config.gridSize.first)
            .putInt(key(config.preview, ROWS), config.gridSize.second)
            .putInt(key(config.preview, TEXT_SIZE), config.textSize)
            .apply()
    }

    /** Null tant que le champ n'a pas été ouvert dans ce mode. */
    fun read(preview: Boolean): FieldReport? {
        val width = prefs.getInt(key(preview, WIDTH), 0)
        val height = prefs.getInt(key(preview, HEIGHT), 0)
        if (width <= 0 || height <= 0) return null
        return FieldReport(
            width = width,
            height = height,
            gridColumns = prefs.getInt(key(preview, COLUMNS), 0),
            gridRows = prefs.getInt(key(preview, ROWS), 0),
            textSize = prefs.getInt(key(preview, TEXT_SIZE), 0),
        )
    }

    private fun key(preview: Boolean, name: String) =
        if (preview) "preview_$name" else "ride_$name"

    private companion object {
        const val PREFS_NAME = "guidage-champ"
        const val WIDTH = "largeur"
        const val HEIGHT = "hauteur"
        const val COLUMNS = "colonnes"
        const val ROWS = "rangs"
        const val TEXT_SIZE = "corps"
    }
}
