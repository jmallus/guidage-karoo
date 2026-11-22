package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.settings.GuidageSettings
import io.github.jmallus.guidage.ui.CompactModel
import io.github.jmallus.guidage.ui.EdgeCase

/**
 * Ce qu'affiche Ride + Climb compact.
 *
 * Les trois cases sont prises au modèle de Ride + Climb et le profil au champ « Profil à
 * venir » : deux constructions qui existent déjà, et qu'il ne faut pas laisser diverger.
 */
object CompactModels {

    fun build(
        context: Context,
        snapshot: GuidanceSnapshot,
        rideData: RideData,
        settings: GuidageSettings,
        preview: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): CompactModel {
        val edge = EdgeModels.build(context, snapshot, rideData, settings, preview, nowMillis)
        // Sans unité, comme la vitesse vedette de Ride + Climb : « km/h » prenait la largeur
        // qui manquait au chiffre, plus petit alors que ses deux voisins.
        val vitesse = EdgeCase(
            libelle = context.getString(R.string.edge_label_speed),
            valeur = edge.vitesse,
        )
        return CompactModel(
            // La FC sans « bpm » pour la même raison : la rangée écrit ses trois chiffres au corps
            // du plus à l'étroit, et c'était elle.
            cases = listOf(vitesse, edge.puissance, edge.coeur.copy(unite = null)),
            profil = FieldModels.profile(context, snapshot, settings, preview, nowMillis = nowMillis),
        )
    }
}
