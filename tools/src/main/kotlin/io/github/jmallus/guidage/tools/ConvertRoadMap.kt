package io.github.jmallus.guidage.tools

import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadMapWriter
import java.io.File

/**
 * Convertit des objets OpenStreetMap — voies, cours d'eau, bois, eau, bâti — en fond de carte.
 *
 *     convertRoadMap sortie.gkmap entree1.geojsonseq [entree2.geojsonseq …]
 *
 * Les entrées sont produites par `osmium export` et peuvent couvrir plusieurs régions :
 * elles sont fondues dans un seul fichier, ce qui évite d'avoir à choisir sa région au
 * guidon quand on roule à cheval sur deux.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage : convertRoadMap sortie.gkmap entrée.geojsonseq [entrée.geojsonseq …]")
        kotlin.system.exitProcess(2)
    }

    val output = File(args[0])
    val inputs = args.drop(1).map(::File)
    inputs.filterNot { it.isFile }.forEach {
        System.err.println("entrée introuvable : ${it.path}")
        kotlin.system.exitProcess(2)
    }

    val writer = RoadMapWriter()
    val counts = LinkedHashMap<RoadKind, Int>()
    var lines = 0L

    for (input in inputs) {
        println("lecture de ${input.path} (${input.length() / 1_048_576} Mo)")
        input.bufferedReader().useLines { sequence ->
            sequence.forEach { line ->
                lines++
                GeoJsonSeq.toSegments(line).forEach { segment ->
                    writer.add(segment)
                    counts.merge(segment.kind, 1, Int::plus)
                }
            }
        }
    }

    val kept = counts.values.sum()
    if (kept == 0) {
        System.err.println("aucun objet retenu sur $lines lignes : entrées vides ou filtre trop strict")
        kotlin.system.exitProcess(1)
    }

    val bytes = writer.build()
    output.parentFile?.mkdirs()
    output.writeBytes(bytes)

    println()
    println("$lines lignes lues, $kept objets retenus, ${writer.segmentCount} tronçons après découpage")
    counts.entries.sortedByDescending { it.value }.forEach { (kind, count) ->
        val marker = when {
            kind.isArea -> " (surface)"
            kind.isTrail -> " (chemin)"
            else -> ""
        }
        println("  %-14s %7d%s".format(kind.name.lowercase(), count, marker))
    }
    println()
    println("écrit ${output.path} — %.1f Mo".format(bytes.size / 1_048_576.0))
}
