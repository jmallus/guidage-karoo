package io.github.jmallus.guidage.tools

import io.github.jmallus.guidage.core.map.RoadKind
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.core.map.RoadSurface
import io.github.jmallus.guidage.core.map.toMicroDegrees
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lecture du GeoJSON par lignes produit par `osmium export`.
 *
 * Un objet JSON complet par ligne : le fichier se lit d'un bout à l'autre sans jamais
 * tenir en mémoire, ce qui compte quand une région en pèse plusieurs giga-octets.
 */
object GeoJsonSeq {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Convertit une ligne en tronçon, ou renvoie null si elle ne nous intéresse pas.
     *
     * Sont écartés : les lignes vides, les géométries autres que des lignes, les valeurs
     * de `highway` qu'on ne garde pas, et les voies réduites à un point une fois les
     * coordonnées arrondies.
     */
    fun toSegment(line: String): RoadSegment? {
        val trimmed = line.trim().removePrefix("")
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null

        val feature = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        val geometry = feature["geometry"]?.jsonObject ?: return null
        if (geometry["type"]?.jsonPrimitive?.content != "LineString") return null

        val properties = feature["properties"]?.jsonObject ?: return null
        val highway = properties["highway"]?.jsonPrimitive?.content ?: return null
        val kind = RoadKind.fromHighwayTag(highway) ?: return null
        val surface = RoadSurface.fromSurfaceTag(properties["surface"]?.jsonPrimitive?.content)

        val coordinates = geometry["coordinates"]?.jsonArray ?: return null
        val latitudes = ArrayList<Int>(coordinates.size)
        val longitudes = ArrayList<Int>(coordinates.size)
        for (point in coordinates) {
            val pair = point.jsonArray
            if (pair.size < 2) continue
            // GeoJSON écrit la longitude avant la latitude.
            val longitude = pair[0].jsonPrimitive.content.toDoubleOrNull() ?: continue
            val latitude = pair[1].jsonPrimitive.content.toDoubleOrNull() ?: continue
            val latitudeMicro = latitude.toMicroDegrees()
            val longitudeMicro = longitude.toMicroDegrees()
            // L'arrondi au micro-degré peut confondre deux points voisins : les garder
            // ferait des segments de longueur nulle, sans effet visible mais qui pèsent.
            if (latitudes.isNotEmpty() &&
                latitudes.last() == latitudeMicro &&
                longitudes.last() == longitudeMicro
            ) {
                continue
            }
            latitudes.add(latitudeMicro)
            longitudes.add(longitudeMicro)
        }

        if (latitudes.size < 2) return null
        return RoadSegment(
            kind = kind,
            surface = surface,
            latitudes = latitudes.toIntArray(),
            longitudes = longitudes.toIntArray(),
        )
    }
}
