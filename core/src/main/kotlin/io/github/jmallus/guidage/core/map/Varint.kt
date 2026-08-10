package io.github.jmallus.guidage.core.map

/**
 * Entiers de taille variable, en zigzag.
 *
 * Le fond de carte est fait de suites de points très proches les uns des autres : écrits
 * en écart au point précédent, ces nombres tiennent presque toujours sur un ou deux
 * octets là où un entier fixe en prendrait quatre. Le zigzag ramène les écarts négatifs
 * dans les petits entiers positifs, sans quoi un pas de −1 coûterait dix octets.
 */
object Varint {

    fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)

    fun unZigZag(value: Int): Int = (value ushr 1) xor -(value and 1)

    /** Écrit un entier non signé, sept bits par octet, le huitième signalant la suite. */
    fun write(out: MutableList<Byte>, value: Int) {
        var remaining = value
        while (true) {
            val chunk = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining == 0) {
                out.add(chunk.toByte())
                return
            }
            out.add((chunk or 0x80).toByte())
        }
    }

    fun writeSigned(out: MutableList<Byte>, value: Int) = write(out, zigZag(value))

    /**
     * Lit un entier à partir de [cursor], qu'il avance.
     *
     * Le curseur est un objet plutôt qu'une valeur de retour supplémentaire : la lecture
     * d'un tronçon enchaîne des centaines d'appels, et rendre une paire à chaque fois
     * n'apporterait rien.
     */
    fun read(bytes: ByteArray, cursor: Cursor): Int {
        var result = 0
        var shift = 0
        while (true) {
            require(shift <= 28) { "entier variable trop long à l'offset ${cursor.offset}" }
            require(cursor.offset < bytes.size) { "fin de données prématurée" }
            val byte = bytes[cursor.offset++].toInt()
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }

    fun readSigned(bytes: ByteArray, cursor: Cursor): Int = unZigZag(read(bytes, cursor))

    /** Position de lecture, avancée au fil des appels. */
    class Cursor(var offset: Int = 0)
}
