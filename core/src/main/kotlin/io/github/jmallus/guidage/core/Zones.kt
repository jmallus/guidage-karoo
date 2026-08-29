package io.github.jmallus.guidage.core

/**
 * Une zone d'effort, telle que le coureur l'a réglée sur son Karoo.
 *
 * Les bornes sont celles de l'appareil et non des pourcentages recalculés : deux coureurs
 * de même FTP peuvent avoir découpé leurs zones différemment.
 */
data class ZoneRange(val min: Int, val max: Int)

/**
 * Zones d'effort et couleurs associées.
 *
 * La palette est celle du Karoo, pour que le tableau de bord parle le même langage que les
 * écrans natifs : un fond jaune veut dire tempo ici comme ailleurs. Valeurs relevées dans
 * Barberfish (jpweytjens/barberfish, Apache 2.0).
 */
object Zones {

    /** Puissance : sept zones, de la récupération au neuromusculaire. */
    val POWER_COLORS = listOf(
        0xFF1A8C3A.toInt(), // 1 — récupération active (vert foncé)
        0xFF40D078.toInt(), // 2 — endurance (vert menthe)
        0xFFF0D800.toInt(), // 3 — tempo (jaune)
        0xFFF08868.toInt(), // 4 — seuil (saumon)
        0xFFF06020.toInt(), // 5 — VO2 max (orange)
        0xFFD01020.toInt(), // 6 — anaérobie (rouge)
        0xFF9020A0.toInt(), // 7 — neuromusculaire (violet)
    )

    /** Fréquence cardiaque : cinq zones, sous-ensemble de la même palette. */
    val HEART_RATE_COLORS = listOf(
        0xFF1A8C3A.toInt(),
        0xFF40D078.toInt(),
        0xFFF0D800.toInt(),
        0xFFF08868.toInt(),
        0xFFD01020.toInt(),
    )

    /**
     * Couleur d'une pente en %, ou null pour une descente.
     *
     * Le Karoo n'a pas de palette de pente à lui : il reprend celle des zones de puissance,
     * du vert foncé au violet, avec ses propres seuils. Le profil de montée parle donc le
     * même langage que les cases d'effort, ce qui est tout l'intérêt — un violet veut dire
     * « ça va faire mal » partout sur l'écran.
     *
     * Les descentes ne sont pas colorées : la palette du Karoo ne descend pas sous zéro.
     */
    fun gradeColor(grade: Double): Int? = when {
        grade >= 20.0 -> POWER_COLORS[6] // violet
        grade >= 14.0 -> POWER_COLORS[5] // rouge
        grade >= 11.0 -> POWER_COLORS[4] // orange
        grade >= 8.0 -> POWER_COLORS[3] // saumon
        grade >= 5.0 -> POWER_COLORS[2] // jaune
        grade >= 2.0 -> POWER_COLORS[1] // vert menthe
        grade >= 0.0 -> POWER_COLORS[0] // vert foncé
        else -> null
    }

    /** Vitesse au-dessus de la moyenne de la sortie. */
    const val ABOVE_AVERAGE = 0xFF00A661.toInt()

    /** Vitesse en dessous de la moyenne de la sortie. */
    const val BELOW_AVERAGE = 0xFFAF090B.toInt()

    /**
     * Numéro de zone (à partir de 1) pour une valeur donnée.
     *
     * Au-delà de la dernière borne, la zone la plus haute est retenue plutôt que rien :
     * un sprint au-dessus du plafond reste un sprint.
     */
    fun zoneOf(value: Double, zones: List<ZoneRange>): Int {
        if (zones.isEmpty()) return 0
        val index = zones.indexOfFirst { value <= it.max }
        return if (index < 0) zones.size else index + 1
    }

    /** Couleur de fond pour une puissance, ou null faute de zones configurées. */
    fun powerColor(watts: Double, zones: List<ZoneRange>): Int? =
        POWER_COLORS.getOrNull(zoneOf(watts, zones) - 1)

    /** Couleur de fond pour une fréquence cardiaque, ou null faute de zones configurées. */
    fun heartRateColor(bpm: Double, zones: List<ZoneRange>): Int? =
        HEART_RATE_COLORS.getOrNull(zoneOf(bpm, zones) - 1)

    /**
     * Couleur de fond pour la vitesse, comparée à la moyenne de la sortie.
     *
     * En début de sortie la moyenne n'a pas de sens (elle vaut la vitesse instantanée) :
     * tant qu'elle est nulle, la case reste neutre.
     */
    fun speedColor(speed: Double, averageSpeed: Double?): Int? {
        if (averageSpeed == null || averageSpeed <= 0.0) return null
        return if (speed >= averageSpeed) ABOVE_AVERAGE else BELOW_AVERAGE
    }
}
