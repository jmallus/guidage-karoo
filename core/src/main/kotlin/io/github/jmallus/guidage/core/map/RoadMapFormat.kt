package io.github.jmallus.guidage.core.map

/**
 * Format du fichier de fond de carte.
 *
 * Le besoin est étroit — dessiner des voies dans une fenêtre de deux cents mètres à dix
 * kilomètres, une fois par seconde, sur un appareil de vélo — et aucun format existant ne
 * s'y prête sans traîner ce dont on n'a que faire : étiquettes, surfaces, thèmes,
 * bâtiments. D'où un format propre, réduit à des polylignes classées et indexées.
 *
 * ```
 * Entête, en petit-boutiste :
 *   magie          6 octets   « GKMAP\0 »
 *   version        1 octet    = 1
 *   réservé        1 octet    = 0
 *   minLatitude    int32      micro-degrés
 *   minLongitude   int32
 *   maxLatitude    int32
 *   maxLongitude   int32
 *   cellSize       int32      côté d'une cellule, en micro-degrés
 *   columns        int32
 *   rows           int32
 *   index          (columns × rows + 1) × int32, offsets dans le corps
 *
 * Corps, cellules concaténées ; chaque cellule est une suite de tronçons :
 *   entête         1 octet    classe sur les 5 bits bas, revêtement sur les 3 bits hauts
 *   identifiant    varint     unique dans le fichier
 *   nombre points  varint
 *   premier point  2 varint signés, en écart au coin sud-ouest de la cellule
 *   points suivants 2 varint signés chacun, en écart au point précédent
 * ```
 *
 * L'identifiant coûte un à trois octets par exemplaire et sert au dédoublonnage : un
 * tronçon à cheval sur deux cellules est écrit dans chacune, et une fenêtre couvrant les
 * deux le lirait donc deux fois. Sa position dans le fichier ne peut pas en tenir lieu,
 * les deux exemplaires étant à des endroits différents.
 *
 * L'index est un simple quadrillage régulier plutôt qu'un arbre : les fenêtres demandées
 * sont toujours petites et carrées, un quadrillage y répond en temps constant et se lit
 * sans rien reconstruire en mémoire.
 */
object RoadMapFormat {

    val MAGIC = byteArrayOf('G'.code.toByte(), 'K'.code.toByte(), 'M'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), 0)

    const val VERSION = 1

    const val HEADER_SIZE = 8 + 7 * 4

    /**
     * Côté d'une cellule, en micro-degrés — 0,02° font environ 2,2 km.
     *
     * Assez grand pour que l'index reste léger sur une région entière, assez petit pour
     * qu'à la portée maximale de dix kilomètres on ne lise qu'une trentaine de cellules.
     */
    const val DEFAULT_CELL_SIZE = 20_000

    /**
     * Nombre maximal de points d'un tronçon écrit.
     *
     * Un tronçon est rangé dans toutes les cellules que touche son rectangle englobant :
     * sans découpage, une nationale traversant la région serait recopiée dans des
     * centaines de cellules. Découpée, chacune de ses parties n'en touche que deux ou
     * trois.
     */
    const val MAX_SEGMENT_POINTS = 32

    fun packHeader(kind: RoadKind, surface: RoadSurface): Int =
        (kind.code and 0x1F) or ((surface.code and 0x07) shl 5)

    fun unpackKind(header: Int): RoadKind? = RoadKind.fromCode(header and 0x1F)

    fun unpackSurface(header: Int): RoadSurface = RoadSurface.fromCode((header shr 5) and 0x07)
}

/** Accès en lecture aléatoire aux octets du fichier. */
interface ByteSource {
    val size: Long

    /** Lit [length] octets à partir de [offset]. */
    fun read(offset: Long, length: Int): ByteArray
}

/** Source adossée à un tableau en mémoire, pour les tests et les petits fichiers. */
class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && offset + length <= bytes.size) { "lecture hors des limites" }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}
