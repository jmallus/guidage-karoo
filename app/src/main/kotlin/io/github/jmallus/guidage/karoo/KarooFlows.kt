package io.github.jmallus.guidage.karoo

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/**
 * Ponts entre les consommateurs à callback de karoo-ext et les Flow Kotlin.
 */

/** Flux d'états de streaming pour un type de donnée Karoo. */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val listenerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
        trySendBlocking(event.state)
    }
    awaitClose { removeConsumer(listenerId) }
}

/** Flux d'événements système typés (état de navigation, profil utilisateur, état de sortie…). */
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> = callbackFlow {
    val listenerId = addConsumer<T> { trySend(it) }
    awaitClose { removeConsumer(listenerId) }
}

/** Valeur numérique d'un type de donnée, ou null quand la donnée n'est pas disponible. */
fun KarooSystemService.streamValueFlow(dataTypeId: String): Flow<Double?> =
    streamDataFlow(dataTypeId).map { (it as? StreamState.Streaming)?.dataPoint?.singleValue }

/**
 * Tous les champs d'un type de donnée, pour les types qui en portent plusieurs
 * (les données de côte, par exemple, arrivent groupées dans un seul point).
 */
fun KarooSystemService.streamFieldsFlow(dataTypeId: String): Flow<Map<String, Double>> =
    streamDataFlow(dataTypeId).map {
        (it as? StreamState.Streaming)?.dataPoint?.values ?: emptyMap()
    }
