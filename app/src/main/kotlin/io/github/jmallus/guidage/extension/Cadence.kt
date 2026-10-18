package io.github.jmallus.guidage.extension

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/**
 * Laisse passer au plus une valeur par [periodeMs], toujours la plus récente.
 *
 * Un champ se nourrit d'une vingtaine de flux du Karoo, qui publient chacun à son heure : la
 * vitesse, puis la puissance un tiers de seconde plus tard, puis le cœur… Chaque arrivée
 * faisait un modèle neuf, donc une image neuve — le tableau de bord, carte et fond de carte
 * compris, se redessinait ainsi plusieurs fois par seconde, sans relâche, pour un écran que
 * l'œil lit une fois par seconde et que les champs natifs ne rafraîchissent pas plus vite. Une
 * sortie a vu la batterie descendre de quinze pour cent à l'heure au lieu de dix.
 *
 * Les valeurs intermédiaires sont oubliées, non retardées : celle qu'on dessine est la
 * dernière arrivée, et la marque de la carte la rattrape de toute façon le long du tracé.
 */
fun <T> Flow<T>.auPlusUneFoisPar(periodeMs: Long): Flow<T> = conflate().transform { valeur ->
    val debut = System.currentTimeMillis()
    // Le dessin se fait dans `emit` : le temps qu'il prend est décompté de la pause.
    emit(valeur)
    delay((periodeMs - (System.currentTimeMillis() - debut)).coerceAtLeast(0L))
}

/** Un rendu par seconde : le rythme des champs natifs du Karoo. */
const val PERIODE_RENDU_MS = 1_000L
