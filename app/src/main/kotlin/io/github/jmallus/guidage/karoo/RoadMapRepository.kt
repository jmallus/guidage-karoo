package io.github.jmallus.guidage.karoo

import android.content.Context
import io.github.jmallus.guidage.core.GeoPoint
import io.github.jmallus.guidage.core.map.ByteSource
import io.github.jmallus.guidage.core.map.MapBounds
import io.github.jmallus.guidage.core.map.RoadMapReader
import io.github.jmallus.guidage.core.map.RoadSegment
import io.github.jmallus.guidage.core.map.toMicroDegrees
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.cos

/** Source adossée à un fichier, lue par morceaux plutôt que chargée d'un bloc. */
private class FileSource(private val file: File) : ByteSource, AutoCloseable {
    private val handle = RandomAccessFile(file, "r")

    override val size: Long get() = handle.length()

    override fun read(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        synchronized(handle) {
            handle.seek(offset)
            handle.readFully(bytes)
        }
        return bytes
    }

    override fun close() = handle.close()
}

/**
 * Le fond de carte de l'appareil : où il est, comment on l'interroge.
 *
 * Le fichier voyage dans l'APK et se déballe tout seul au premier usage. Le coureur n'a
 * donc rien à copier ni à désigner : installer l'extension installe la carte. C'est ce qui
 * évite à la fois le câble USB et les permissions de stockage, qu'Android restreint depuis
 * sa version 11 au point qu'un fichier déposé dans « Téléchargements » n'est plus lisible.
 *
 * Le déballage a un coût : la carte occupe deux fois sa taille sur l'appareil, une fois
 * compressée dans l'APK et une fois déballée. À trente méga-octets sur les trente-deux
 * giga-octets du Karoo, c'est sans importance, et cela évite de dépendre de la façon dont
 * Android range les ressources.
 */
class RoadMapRepository(private val context: Context) {

    private var source: FileSource? = null
    private var reader: RoadMapReader? = null
    private var loadedFrom: Long = -1

    /** Dernière fenêtre lue, pour ne pas relire le fichier à chaque rafraîchissement. */
    private var cachedBounds: MapBounds? = null
    private var cachedRoads: List<RoadSegment> = emptyList()

    val file: File get() = File(context.filesDir, MAP_FILE_NAME)

    val isAvailable: Boolean get() = file.isFile && file.length() > 0

    /** Taille du fond de carte déballé, en octets, ou 0 s'il n'y en a pas. */
    val installedSize: Long get() = if (isAvailable) file.length() else 0L

    /**
     * Déballe la carte de l'APK si ce n'est pas déjà fait.
     *
     * Le fichier est écrit de côté puis renommé : un déballage interrompu — batterie vide,
     * extension arrêtée — ne laisse pas une carte tronquée qui planterait à la lecture.
     * Renvoie false quand l'APK n'embarque pas de carte, ce qui est un cas normal : la
     * construction a pu se faire avant qu'un fond n'ait été fabriqué.
     */
    @Synchronized
    fun unpackIfNeeded(): Boolean {
        if (isAvailable) return true
        val temporary = File(context.filesDir, "$MAP_FILE_NAME.partiel")
        return try {
            context.assets.open(MAP_ASSET_NAME).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (RoadMapReader.open(FileSource(temporary)) == null) {
                temporary.delete()
                false
            } else {
                temporary.renameTo(file)
            }
        } catch (error: Exception) {
            // Absence d'asset comprise : l'extension marche sans fond de carte.
            temporary.delete()
            false
        }
    }

    /**
     * Voies autour d'un point, dans un rayon donné.
     *
     * Le résultat est gardé tant que le coureur reste franchement à l'intérieur de la
     * zone déjà lue : à trente kilomètres à l'heure, il faut une bonne minute pour en
     * sortir, et relire le fichier chaque seconde ne montrerait rien de plus.
     */
    @Synchronized
    fun roadsAround(centre: GeoPoint, radiusMeters: Double): List<RoadSegment> {
        val current = reader() ?: return emptyList()
        val needed = boundsAround(centre, radiusMeters)
        cachedBounds?.let { cached ->
            if (cached.contains(needed)) return cachedRoads
        }

        val generous = boundsAround(centre, radiusMeters * CACHE_MARGIN)
        cachedRoads = current.segmentsIn(generous)
        cachedBounds = generous
        return cachedRoads
    }

    @Synchronized
    private fun reader(): RoadMapReader? {
        if (!isAvailable && !unpackIfNeeded()) return null
        val stamp = if (isAvailable) file.lastModified() else -1
        if (stamp != loadedFrom) {
            close()
            loadedFrom = stamp
            if (stamp >= 0) {
                source = FileSource(file).also { reader = RoadMapReader.open(it) }
            }
        }
        return reader
    }

    @Synchronized
    private fun close() {
        runCatching { source?.close() }
        source = null
        reader = null
        loadedFrom = -1
        cachedBounds = null
        cachedRoads = emptyList()
    }

    private fun boundsAround(centre: GeoPoint, radiusMeters: Double): MapBounds {
        val latitudeSpan = radiusMeters / METERS_PER_DEGREE_LATITUDE
        val cosine = cos(Math.toRadians(centre.lat)).let { if (abs(it) < 0.01) 0.01 else abs(it) }
        val longitudeSpan = radiusMeters / (METERS_PER_DEGREE_LATITUDE * cosine)
        return MapBounds(
            minLatitude = (centre.lat - latitudeSpan).toMicroDegrees(),
            minLongitude = (centre.lng - longitudeSpan).toMicroDegrees(),
            maxLatitude = (centre.lat + latitudeSpan).toMicroDegrees(),
            maxLongitude = (centre.lng + longitudeSpan).toMicroDegrees(),
        )
    }

    private fun MapBounds.contains(other: MapBounds): Boolean =
        minLatitude <= other.minLatitude &&
            minLongitude <= other.minLongitude &&
            maxLatitude >= other.maxLatitude &&
            maxLongitude >= other.maxLongitude

    companion object {
        const val MAP_FILE_NAME = "carte.gkmap"

        /** Nom de la carte embarquée dans l'APK, déposée là par la construction. */
        const val MAP_ASSET_NAME = "carte.gkmap"

        /** On lit plus large que nécessaire pour ne pas relire à chaque pas. */
        private const val CACHE_MARGIN = 2.5

        private const val METERS_PER_DEGREE_LATITUDE = 110_540.0
    }
}
