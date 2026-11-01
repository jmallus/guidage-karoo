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

/**
 * Pas de plafond : un rendu dès qu'une valeur arrive, comme avant la version 1.3.8.
 *
 * Le plafond d'un rendu par seconde avait été posé pour la batterie — quinze pour cent à
 * l'heure au lieu de dix sur une sortie. Le coureur a préféré l'écran plus vif. Les valeurs
 * restent fusionnées (`conflate`) : un rendu encore en cours n'empile pas ceux qui arrivent,
 * seule la dernière valeur est dessinée ensuite. Remettre 1 000 rétablit le plafond.
 */
const val PERIODE_RENDU_MS = 0L
