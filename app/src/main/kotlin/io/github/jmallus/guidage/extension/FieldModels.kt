package io.github.jmallus.guidage.extension

import android.content.Context
import io.github.jmallus.guidage.R
import io.github.jmallus.guidage.core.ArrivalEstimate
import io.github.jmallus.guidage.core.Format
import io.github.jmallus.guidage.core.GraphZoom
import io.github.jmallus.guidage.core.Guidance
import io.github.jmallus.guidage.core.GuidanceState
import io.github.jmallus.guidage.core.Pacing
import io.github.jmallus.guidage.core.ProfileWindow
import io.github.jmallus.guidage.karoo.GuidanceSnapshot
import io.github.jmallus.guidage.karoo.RideData
import io.github.jmallus.guidage.settings.GuidageSettings
import io.github.jmallus.guidage.ui.PreviewData
import io.github.jmallus.guidage.ui.ProfileFieldModel

import kotlin.math.min

/**
 * Ce qu'affiche le champ « Profil à venir », et l'heure d'arrivée qu'en tirent les autres.
 *
 * Extrait des champs eux-mêmes pour la même raison que l'avait été le tableau de bord : une
 * seule construction sert à l'extension et au banc d'essai, de sorte que ce que montre celui-ci
 * est ce que montrera l'appareil. Recopier ces quarante lignes dans le simulateur reviendrait
 * à écrire l'affichage une seconde fois — et deux écritures d'une même chose finissent
 * toujours par diverger sans que rien ne le signale.
 */
object FieldModels {

    /**
     * Le profil à venir : tout ce qui reste de l'itinéraire.
     *
     * [preview] est vrai dans le sélecteur de champs du Karoo, où l'on substitue un parcours
     * d'exemple : un champ figé sur « — » ne dit rien de ce qu'il donnera en roulant.
     */
    fun profile(
        context: Context,
        snapshot: GuidanceSnapshot,
        settings: GuidageSettings,
        preview: Boolean,
        /**
         * La portée demandée, ou null pour tout ce qui reste à l'échelle comprimée.
         *
         * Les deux appelants ne posent pas la même question. Le champ « Profil à venir »
         * demande ce qui reste de la journée, et la compression est faite pour ça. Le bandeau
         * du tableau de bord, lui, est regardé pour savoir ce qui **arrive** : sur cent
         * kilomètres restants, la compression y écrase la rampe des trois cents mètres qui
         * vient contre le fond de l'écran, et le relevé d'une sortie réelle a montré qu'on
         * n'y voyait plus rien monter.
         */
        zoom: GraphZoom? = null,
    ): ProfileFieldModel {
        val state = substituted(snapshot, preview)
        val route = state.route
        val along = state.distanceAlongRoute
        if (route == null || along == null) {
            return ProfileFieldModel(
                window = ProfileWindow(emptyList(), 0.0, 0.0, 0.0, 0.0),
                emptyMessage = context.getString(R.string.field_no_route),
                units = snapshot.units,
            )
        }
        val portee = zoom?.lookaheadMeters

        // La position est arrondie pour éviter de redessiner à chaque mètre parcouru.
        val quantized = (along / POSITION_STEP_METERS).toInt() * POSITION_STEP_METERS
        // La fenêtre commence un peu avant le coureur : sa marque était collée au bord gauche,
        // où elle se confondait avec le cadre — on ne savait plus si la silhouette commençait
        // sous les roues ou si elle était coupée. Sous l'échelle comprimée, ces cent vingt
        // mètres occupent près d'un dixième de la largeur : de quoi détacher la marque, et
        // montrer la pente dont on sort.
        //
        // À portée fixée, le recul est une part de la portée — un cinquième — et non plus
        // ces cent vingt mètres : le bandeau montre alors ce qu'on vient de monter, en blanc,
        // et la marque se tient franchement dans la bande, comme sur le profil natif.
        val units = snapshot.units

        // Le bandeau se cadre sur la côte qu'on monte, du pied au sommet, et revient à sa
        // portée une fois le sommet passé — le ClimbPro du Karoo. Le champ « Profil à venir »
        // n'en fait rien : il répond à « qu'est-ce qui reste », et une côte n'en est qu'une part.
        if (portee != null) {
            val status = Guidance.climbStatus(route, quantized)?.takeIf { it.onClimb }
            if (status != null) {
                val cote = status.climb
                // Une fenêtre qui glisse sur la côte, et non la côte entière : c'est ce qu'il
                // faut pour que chaque tronçon de cent mètres ait la place de sa case de pente.
                // Cadré du pied au sommet, un col de six kilomètres ne laissait que trois
                // chiffres lisibles devant le coureur. Elle garde un peu de
                // ce qu'on vient de monter derrière la marque, et s'arrête au sommet ; une côte
                // plus courte qu'elle se montre en entier.
                val debut = if (cote.length <= CLIMB_WINDOW_METERS) {
                    cote.startDistance
                } else {
                    (quantized - CLIMB_WINDOW_METERS * CLIMB_RECUL_FRACTION)
                        .coerceIn(cote.startDistance, cote.endDistance - CLIMB_WINDOW_METERS)
                }
                val longueur = min(cote.length, CLIMB_WINDOW_METERS)
                return ProfileFieldModel(
                    window = Guidance.profileWindow(route, debut, longueur),
                    climbs = route.climbs,
                    pois = route.pois,
                    // Sans « Sommet dans » : les deux nombres se comprennent sur le bandeau d'une
                    // côte, et la place rendue les laisse grossir.
                    ascentLabel = "${Format.distance(status.distanceToTop, units)} · +${Format.elevation(status.elevationToTop, units)}",
                    rangeLabel = Format.grade(cote.grade),
                    positionDistance = quantized,
                    emptyMessage = context.getString(R.string.field_no_route),
                    compressed = false,
                    units = units,
                    climbZoom = cote,
                )
            }
        }

        val depart = (quantized - RECUL_METERS).coerceAtLeast(0.0)
        val window = if (portee == null) {
            Guidance.profileToFinish(route, depart)
        } else {
            Guidance.profileWindow(route, quantized, portee, lookbehind = portee * RECUL_FRACTION)
        }
        // Le dénivelé et la distance se comptent depuis le coureur, jamais depuis le bord de
        // la fenêtre : celle-ci commence en arrière de lui, et les compter de là ajouterait au
        // « restant » ce qui est déjà fait.
        val ascent = route.profile?.ascentBetween(quantized, window.end)
        val restant = (window.end - quantized).takeIf { it > 0.0 }

        // Le bandeau à portée fixée suit le profil natif : ni dénivelé ni portée en en-tête,
        // le compteur au-dessus de la marque, et les kilomètres du parcours sur l'axe, qui
        // disent la portée à eux seuls. Le champ « Profil à venir » garde ses deux en-têtes —
        // il répond à « qu'est-ce qui reste », et le dénivelé restant en fait partie.
        val bandeau = portee != null
        return ProfileFieldModel(
            window = window,
            climbs = route.climbs,
            pois = route.pois,
            ascentLabel = if (bandeau) null else ascent?.let { "+${Format.elevation(it, units)}" },
            rangeLabel = if (bandeau) null else restant?.let { Format.longDistance(it, units) },
            positionDistance = quantized,
            positionLabel = if (bandeau) Format.longDistanceValue(quantized, units) else null,
            emptyMessage = context.getString(R.string.field_no_route),
            compressed = portee == null,
            units = units,
        )
    }

    /**
     * L'heure d'arrivée déduite de l'allure apprise et du relief qui reste.
     *
     * Elle vivait dans le pied du tableau de bord. Le champ « Avant la nuit » la compare au
     * coucher du soleil, et les deux doivent annoncer la même heure — sans quoi le coureur
     * lirait deux arrivées sur deux pages et ne saurait laquelle croire. Null tant que
     * l'allure n'est pas assez observée.
     */
    fun arrival(state: GuidanceState, rideData: RideData): ArrivalEstimate? = Pacing.arrival(
        pace = rideData.pace,
        terrain = Pacing.terrain(
            profile = state.route?.profile,
            from = state.distanceAlongRoute ?: 0.0,
            remainingDistance = rideData.distanceRemaining
                ?: state.distanceRemaining
                ?: 0.0,
        ),
    )

    /** Le parcours d'exemple, quand le champ est affiché hors sortie. */
    private fun substituted(snapshot: GuidanceSnapshot, preview: Boolean): GuidanceState =
        if (preview && !snapshot.state.navigating) {
            GuidanceState(PreviewData.route, PreviewData.DISTANCE_ALONG_ROUTE, null, null)
        } else {
            snapshot.state
        }

    private const val POSITION_STEP_METERS = 10.0

    /** De combien la fenêtre du profil commence avant le coureur (m), sous l'échelle comprimée. */
    private const val RECUL_METERS = 120.0

    /** Le même recul à portée fixée, en part de la portée. */
    private const val RECUL_FRACTION = 0.2

    /**
     * La portée du zoom de côte : six tronçons de cent mètres (m), le gros plan du Climber
     * du Karoo, où cinq ou six cases de pente tiennent sous le profil.
     */
    private const val CLIMB_WINDOW_METERS = 600.0

    /** La part de cette fenêtre laissée derrière le coureur : un tronçon. */
    private const val CLIMB_RECUL_FRACTION = 1.0 / 6.0
}
