package io.github.jmallus.guidage.core

/** Ce que la carte montre : sa portée, et jusqu'où devant le coureur elle pose ses chevrons (m). */
data class PorteeCarte(val rangeMeters: Double, val chevronMeters: Double) {
    companion object {
        fun of(zoom: MapZoom) = PorteeCarte(zoom.rangeMeters, zoom.chevronMeters)
    }
}
