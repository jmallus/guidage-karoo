package io.github.jmallus.guidage.sim

import android.graphics.Bitmap
import io.github.jmallus.guidage.core.GuidanceZoneType
import io.github.jmallus.guidage.core.MapZoom
import io.github.jmallus.guidage.ui.FieldPalette
import io.github.jmallus.guidage.ui.KarooColors
import io.github.jmallus.guidage.ui.PreviewData
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Le simulateur de bureau, et le contrôle qui le tient droit.
 *
 * Le mode graphique est **natif** et non hérité : c'est ce qui fait que le dessin passe par
 * le vrai Skia d'Android plutôt que par des ébauches vides. La différence n'est pas de
 * finesse mais de nature — en mode hérité, `Paint.getFillPath` ne rend rien et les chevrons
 * disparaîtraient sans que rien ne signale la perte.
 *
 * La langue est le **français**, celle des ressources par défaut du projet. Sans cette
 * mention, Robolectric se dit en anglais et le banc d'essai affiche les libellés de
 * `values-en` : on juge alors la mise en page sur « LAST RESUPPLY BEFORE » quand l'appareil
 * écrira « DERNIER RAVITAILLEMENT AVANT », qui est la moitié plus long. C'est la longueur du
 * texte qui décide des tailles, et donc de tout ce qu'on regarde ici.
 *
 * Le thème est **sombre**, celui du Karoo en sortie. Ce n'est pas un goût : le tableau de
 * bord ne peint pas son fond — il arrive transparent, posé par le Karoo sur le sien — et
 * choisit ses encres d'après le thème du système. Sans cette mention, Robolectric se dit en
 * thème clair et le champ écrit ses chiffres en presque noir sur un écran noir.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SimulateurTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /* ------------------------------------------------------------- la sortie */

    @Test
    fun `la sortie simulee parcourt l'itineraire du depart a l'arrivee`() {
        val sortie = SortieSimulee(PreviewData.route)

        assertTrue("une sortie de trente-deux kilomètres dure plus d'une demi-heure", sortie.duree > 1_600)
        assertEquals(0.0, sortie.a(0.0).distance, 1.0)
        assertEquals(sortie.distanceTotale, sortie.a(sortie.duree).distance, 1.0)

        var precedente = -1.0
        var pas = 0.0
        while (pas <= sortie.duree) {
            val instant = sortie.a(pas)
            assertTrue("la distance recule à $pas s", instant.distance >= precedente)
            assertTrue("vitesse nulle à $pas s", instant.vitesse > 0.0)
            assertTrue("cap hors bornes à $pas s : ${instant.cap}", instant.cap in 0.0..360.0)
            precedente = instant.distance
            pas += 15.0
        }
    }

    @Test
    fun `le coureur monte moins vite qu'il ne descend`() {
        val sortie = SortieSimulee(PreviewData.route)
        // Le raidillon de l'itinéraire d'aperçu est entre les kilomètres 3 et 4, à douze
        // pour cent ; la descente qui suit son sommet est vers le kilomètre 6,5.
        val montee = instantA(sortie, 3_500.0)
        val descente = instantA(sortie, 6_500.0)

        assertTrue("la pente de la montée vaut ${montee.pente} %", montee.pente > 2.0)
        assertTrue("la pente de la descente vaut ${descente.pente} %", descente.pente < -1.0)
        assertTrue(
            "monter à ${montee.vitesse} m/s et descendre à ${descente.vitesse} m/s",
            montee.vitesse < descente.vitesse,
        )
    }

    @Test
    fun `la frequence cardiaque suit l'effort avec retard`() {
        val sortie = SortieSimulee(PreviewData.route)
        // Le faux plat du départ, puis le raidillon. Le kilomètre 2,3 ne conviendrait pas
        // pour le premier : la côte est annoncée à partir de 2,4, mais le terrain s'y dresse
        // déjà à neuf pour cent — plus qu'au kilomètre 4,5, où elle s'assagit.
        val avant = instantA(sortie, 800.0)
        val dansLaCote = instantA(sortie, 3_500.0)

        assertTrue(
            "le cœur passe de ${avant.cardiaque} à ${dansLaCote.cardiaque}",
            dansLaCote.cardiaque > avant.cardiaque + 5,
        )
        assertTrue("fréquence invraisemblable : ${dansLaCote.cardiaque}", dansLaCote.cardiaque < 200)
    }

    /**
     * Le rang du pignon engagé et sa denture disent la même chose.
     *
     * Les deux sont lus par le schéma de la case des vitesses : le rang allume une barre, la
     * denture s'écrit dessous. Rangés à l'envers l'un de l'autre, les deux se contredisent —
     * la barre du grand pignon s'allume et « ×11 » s'écrit — sans que rien ne plante. Ce
     * contrôle relie donc le rang aux dents, dans le sens que dessine le tableau de bord :
     * le n° 1 est le grand pignon.
     */
    @Test
    fun `le pignon engage porte bien la denture annoncee`() {
        val profil = ProfilCoureur()
        val sortie = SortieSimulee(PreviewData.route, profil)

        assertEquals(
            "le pignon n° 1 doit être le plus grand",
            profil.pignons.max(),
            profil.pignons.first(),
        )
        assertEquals(
            "le plateau n° 1 doit être le plus petit",
            profil.plateaux.min(),
            profil.plateaux.first(),
        )

        var rangsVus = 0
        var pas = 0.0
        while (pas <= sortie.duree) {
            val transmission = sortie.a(pas).transmission
            val rangArriere = requireNotNull(transmission.rear) { "pas de pignon à $pas s" }
            val rangAvant = requireNotNull(transmission.front) { "pas de plateau à $pas s" }
            assertEquals(
                "à $pas s, le pignon n° $rangArriere n'a pas la denture annoncée",
                profil.pignons[rangArriere - 1],
                transmission.rearTeeth,
            )
            assertEquals(
                "à $pas s, le plateau n° $rangAvant n'a pas la denture annoncée",
                profil.plateaux[rangAvant - 1],
                transmission.frontTeeth,
            )
            rangsVus++
            pas += 15.0
        }
        assertTrue("aucun rapport relevé", rangsVus > 10)
    }

    /* --------------------------------------------------------------- le décor */

    @Test
    fun `le decor rend les memes voies a chaque appel`() {
        val decor = DecorSimule(PreviewData.location.position)
        val centre = PreviewData.location.position

        val premier = decor.autour(centre, 800.0)
        val second = decor.autour(centre, 800.0)

        assertTrue("le décor ne compte que ${premier.size} tronçons", premier.size > 20)
        assertEquals("le décor change d'un appel à l'autre", premier, second)
    }

    /* --------------------------------------------------------------- le rendu */

    /**
     * Le simulateur peint avec les encres du thème sombre.
     *
     * Le tableau de bord arrive transparent : le Karoo le pose sur son propre fond, noir en
     * sortie, et les encres se choisissent d'après le thème du système. Un banc d'essai qui
     * se croirait en plein jour écrirait ses chiffres en presque noir sur ce noir-là — non
     * pas illisibles au point qu'on crie à la panne, mais gris, ternes, faux. C'est arrivé.
     */
    @Test
    fun `le banc d'essai peint comme le Karoo en sortie`() {
        val palette = FieldPalette.of(context)

        assertEquals("les valeurs ne sont pas en blanc", 0xFFFFFFFF.toInt(), palette.textPrimary)
    }

    /**
     * Les libellés et leurs icônes portent le bleu que le système leur impose.
     *
     * « Powder Blue must be used for label and icon » — c'est une des quatre couleurs que le
     * fichier de Hammerhead donne comme obligatoires, et non recommandées. L'icône a longtemps
     * été verte ici : d'un vert de la colonne UI Green, que le même fichier proscrit dans les
     * champs de données. Deux fautes d'un coup, dans la couleur et dans le rôle.
     */
    @Test
    fun `les titres et leurs icones portent le bleu du systeme`() {
        val palette = FieldPalette.of(context)

        assertEquals("le libellé n'est pas en Powder Blue", KarooColors.POWDER_BLUE, palette.textSecondary)
        assertEquals("l'icône n'est pas en Powder Blue", KarooColors.POWDER_BLUE, palette.iconTint)
        assertEquals("l'erreur n'est pas en UI Red", KarooColors.UI_RED, FieldPalette.REJOIN)
    }

    /**
     * Aucune couleur vive ne se pose sur le tableau de bord hors des aplats de zone.
     *
     * Le rapport engagé a porté un instant le vert que le système réserve à la donnée vive.
     * Il est revenu au blanc des valeurs : le contour porte déjà toute la distinction — creux
     * partout, plein à un seul endroit — et l'écran compte assez de signaux colorés avec les
     * sept aplats de zone. Le contrôle garde trace de cette décision : que ce vert réapparaisse
     * quelque part et il faudra l'avoir voulu.
     */
    @Test
    fun `le tableau de bord ne porte pas le vert des donnees vives`() {
        val simulateur = Simulateur(context)
        val image = simulateur.image(simulateur.sortie.duree * 0.3)

        var verts = 0
        for (pixel in pixelsDe(image)) if (pixel == KarooColors.HIGH_VIS_GREEN) verts++

        assertEquals("le vert vif se pose sur $verts pixels", 0, verts)
    }

    /**
     * Le contrôle du CI : chaque configuration rend une image, et cette image montre la trace.
     *
     * Vérifier que le rendu ne lève pas d'exception ne suffirait pas — une carte entièrement
     * noire n'en lève aucune. On compte donc les pixels du ruban bleu : s'ils manquent, c'est
     * que le tracé n'est pas passé, et l'écran ne sert plus à rien.
     */
    @Test
    fun `chaque configuration rend un tableau de bord ou la trace se voit`() {
        val simulateur = Simulateur(context)
        val dossier = File("build/simulateur").apply { mkdirs() }

        for (portee in MapZoom.entries) {
            simulateur.portee = portee
            simulateur.zone = GuidanceZoneType.MAP
            var vues = 0
            for (part in PARTS_CONTROLEES) {
                val image = simulateur.image(simulateur.sortie.duree * part)
                assertEquals(Simulateur.LARGEUR, image.width)
                assertEquals(Simulateur.HAUTEUR, image.height)
                if (rubanVisible(image)) vues++
                if (part == PARTS_CONTROLEES.first()) {
                    ecrire(image, File(dossier, "carte-${portee.rangeMeters.toInt()}m.png"))
                }
            }
            assertTrue(
                "le ruban ne se voit qu'à $vues moments sur ${PARTS_CONTROLEES.size} " +
                    "à la portée ${portee.rangeMeters.toInt()} m",
                vues >= PARTS_CONTROLEES.size - 1,
            )
        }

        // Le profil en portrait, l'autre zone de guidage.
        simulateur.zone = GuidanceZoneType.PROFILE
        val profil = simulateur.image(simulateur.sortie.duree * 0.3)
        assertTrue("le profil ne montre presque rien", couleursDistinctes(profil) > 12)
        ecrire(profil, File(dossier, "profil.png"))

        // Et le tracé au rouge, quand le Karoo décroche de l'itinéraire.
        simulateur.zone = GuidanceZoneType.MAP
        simulateur.horsItineraire = true
        val decroche = simulateur.image(simulateur.sortie.duree * 0.3)
        assertFalse("le tracé reste bleu alors qu'on est hors itinéraire", rubanVisible(decroche))
        ecrire(decroche, File(dossier, "hors-itineraire.png"))
    }

    /**
     * Les deux champs annexes, que la fenêtre montre à côté du tableau de bord.
     *
     * Ils passent par les mêmes constructions de modèle et les mêmes rendus que l'appareil :
     * ce qu'on vérifie ici, c'est qu'ils dessinent réellement quelque chose à chaque moment de
     * la sortie. Un champ vide ne lève aucune exception et se compile parfaitement — et il
     * n'échouerait qu'à l'écran, où personne ne le regarderait avant une sortie.
     */
    @Test
    fun `les champs annexes se dessinent tout au long de la sortie`() {
        val simulateur = Simulateur(context)
        val dossier = File("build/simulateur").apply { mkdirs() }

        for (part in PARTS_CONTROLEES) {
            val secondes = simulateur.sortie.duree * part
            val profil = simulateur.imageProfil(secondes)
            assertEquals(Simulateur.LARGEUR, profil.width)
            assertEquals(Simulateur.HAUTEUR_PROFIL, profil.height)
            assertTrue(
                "le profil ne montre presque rien à ${(part * 100).toInt()} %",
                couleursDistinctes(profil) > 6,
            )

            val cote = simulateur.imageCote(secondes)
            assertEquals(Simulateur.LARGEUR_ANNEXE, cote.width)
            assertEquals(Simulateur.HAUTEUR_COTE, cote.height)
            assertTrue(
                "la côte ne montre presque rien à ${(part * 100).toInt()} %",
                couleursDistinctes(cote) > 3,
            )

            // Les quatre venus des vues proposées. Le contrôle est le même — ils dessinent —
            // mais volontairement lâche sur le nombre de teintes : un champ de texte en porte
            // moins qu'un profil, et le seuil doit tenir aux quatre moments de la sortie.
            val autres = mapOf(
                "champ-contexte" to simulateur.imageContexte(secondes),
                "champ-revetement" to simulateur.imageRevetement(secondes),
                "champ-effort" to simulateur.imageEffort(secondes),
                "champ-virages" to simulateur.imageVirages(secondes),
                "champ-reserve" to simulateur.imageReserve(secondes),
                "champ-autonomie" to simulateur.imageAutonomie(secondes),
            )
            autres.forEach { (nom, image) ->
                assertTrue(
                    "$nom ne montre presque rien à ${(part * 100).toInt()} %",
                    couleursDistinctes(image) > 2,
                )
            }

            if (part == PARTS_CONTROLEES.first()) {
                ecrire(profil, File(dossier, "champ-profil.png"))
                ecrire(cote, File(dossier, "champ-cote.png"))
                autres.forEach { (nom, image) -> ecrire(image, File(dossier, "$nom.png")) }
            }
        }
    }

    /* -------------------------------------------------------------- la fenêtre */

    /**
     * Joue la sortie dans une fenêtre, jusqu'à ce qu'on la ferme.
     *
     * Ce n'est pas un test : c'est l'application, logée dans un test parce que le moteur
     * graphique d'Android n'est disponible sur une machine de bureau que sous Robolectric.
     * Elle ne s'ouvre donc que sur demande expresse — `./gradlew :app:simulateur` — et reste
     * ignorée partout ailleurs, CI compris.
     */
    @Test
    fun `la fenetre joue la sortie`() {
        Assume.assumeTrue(
            "fenêtre demandée seulement par ./gradlew :app:simulateur",
            System.getProperty(PROPRIETE_FENETRE) != null,
        )

        preparerLAffichage()

        val simulateur = Simulateur(context)
        val fenetre = FenetreSimulateur(
            largeurChamp = Simulateur.LARGEUR,
            hauteurChamp = Simulateur.HAUTEUR,
        )
        var secondes = 0.0
        var acceleration = 16.0
        var enPause = false

        while (fenetre.estOuverte) {
            while (true) {
                val commande = fenetre.prochaineCommande() ?: break
                when (commande) {
                    Commande.PAUSE -> enPause = !enPause
                    Commande.RECULER -> secondes = (secondes - 10 * acceleration).coerceAtLeast(0.0)
                    Commande.AVANCER -> secondes = avancer(secondes, 10 * acceleration, simulateur)
                    Commande.PLUS_VITE -> acceleration = (acceleration * 2).coerceAtMost(64.0)
                    Commande.MOINS_VITE -> acceleration = (acceleration / 2).coerceAtLeast(0.5)
                    Commande.PORTEE -> simulateur.portee = simulateur.portee.next()
                    Commande.ZONE -> simulateur.zone = when (simulateur.zone) {
                        GuidanceZoneType.MAP -> GuidanceZoneType.PROFILE
                        GuidanceZoneType.PROFILE -> GuidanceZoneType.MAP
                    }
                    Commande.HORS_ITINERAIRE ->
                        simulateur.horsItineraire = !simulateur.horsItineraire
                    Commande.RECOMMENCER -> secondes = 0.0
                    // Pas fins : la fenêtre s'ouvre à la taille physique de l'appareil, et
                    // ces touches servent à la corriger à la règle, non à confortablement zoomer.
                    Commande.AGRANDIR -> fenetre.agrandir(1.05)
                    Commande.REDUIRE -> fenetre.agrandir(1 / 1.05)
                }
            }

            if (!enPause) {
                secondes = avancer(secondes, acceleration / IMAGES_PAR_SECONDE, simulateur)
            }
            fenetre.montrer(
                champs(simulateur, secondes),
                ligneEtat(simulateur, secondes, acceleration, enPause),
            )
            Thread.sleep((1_000 / IMAGES_PAR_SECONDE).toLong())
        }
    }

    /* -------------------------------------------------------------- outillage */

    /**
     * Tous les champs graphiques de l'extension, au même instant de la sortie.
     *
     * Le champ « Prochain point d'intérêt » n'y est pas : il est numérique, le Karoo le dessine
     * lui-même à partir d'une valeur, et l'extension n'en produit aucune image à montrer.
     */
    private fun champs(simulateur: Simulateur, secondes: Double): List<Champ> = listOf(
        // Les pleines pages d'abord, chacune tenant sa colonne ; les champs de bande ensuite.
        champ("Tableau de bord", simulateur.image(secondes)),
        champ("Autonomie", simulateur.imageAutonomie(secondes)),
        champ("Réserve", simulateur.imageReserve(secondes)),
        champ("Virages", simulateur.imageVirages(secondes)),
        champ("Revêtement", simulateur.imageRevetement(secondes)),
        champ("Profil à venir", simulateur.imageProfil(secondes)),
        champ("Suivant la sortie", simulateur.imageContexte(secondes)),
        champ("Prochaine côte", simulateur.imageCote(secondes)),
        champ("Budget d'effort", simulateur.imageEffort(secondes)),
    )

    private fun champ(nom: String, image: Bitmap) =
        Champ(nom, pixelsDe(image), image.width, image.height)

    private fun avancer(secondes: Double, pas: Double, simulateur: Simulateur): Double =
        (secondes + pas).coerceIn(0.0, simulateur.sortie.duree)

    private fun ligneEtat(
        simulateur: Simulateur,
        secondes: Double,
        acceleration: Double,
        enPause: Boolean,
    ): String {
        val instant = simulateur.sortie.a(secondes)
        return String.format(
            Locale.FRANCE,
            "%02d:%02d  %5.2f km  %5.1f km/h  %+5.1f %%  %3.0f bpm  %3.0f tr/min  " +
                "portée %4d m  ×%s%s",
            (secondes / 60).toInt(),
            (secondes % 60).toInt(),
            instant.distance / 1_000,
            instant.vitesse * 3.6,
            instant.pente,
            instant.cardiaque,
            instant.cadence,
            simulateur.portee.rangeMeters.toInt(),
            if (acceleration >= 1) acceleration.toInt().toString() else "½",
            if (enPause) "  [pause]" else "",
        )
    }

    /**
     * Le ruban bleu se voit-il ?
     *
     * On ne cherche pas la teinte exacte : elle est composée sur le fond, et l'antialiasing
     * la nuance sur ses bords. Un bleu franc et dominant suffit à distinguer le tracé du
     * décor, qui n'a que des gris très sombres et une pointe de bleu sur l'eau — trop
     * sombre, elle, pour passer le seuil.
     */
    private fun rubanVisible(image: Bitmap): Boolean {
        var bleus = 0
        for (pixel in pixelsDe(image)) {
            val rouge = (pixel shr 16) and 0xFF
            val vert = (pixel shr 8) and 0xFF
            val bleu = pixel and 0xFF
            if (bleu > 170 && bleu - rouge > 60 && bleu - vert > 40) bleus++
        }
        return bleus > 300
    }

    private fun couleursDistinctes(image: Bitmap): Int = pixelsDe(image).toHashSet().size

    /** Les pixels ARGB de l'image, rangés par lignes. */
    private fun pixelsDe(image: Bitmap): IntArray {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return pixels
    }

    /**
     * Écrit une image de contrôle.
     *
     * L'encodage passe par Android lui-même et non par ImageIO : « javax.imageio » n'existe
     * pas plus que « java.awt » dans un test unitaire d'Android.
     */
    private fun ecrire(image: Bitmap, fichier: File) {
        FileOutputStream(fichier).use { sortie ->
            image.compress(Bitmap.CompressFormat.PNG, 100, sortie)
        }
    }

    /** L'instant où le coureur passe à une distance donnée du départ. */
    private fun instantA(sortie: SortieSimulee, metres: Double): InstantSortie {
        var bas = 0.0
        var haut = sortie.duree
        repeat(40) {
            val milieu = (bas + haut) / 2
            if (sortie.a(milieu).distance < metres) bas = milieu else haut = milieu
        }
        val instant = sortie.a((bas + haut) / 2)
        check(abs(instant.distance - metres) < 50) {
            "le coureur ne passe pas à $metres m (trouvé ${instant.distance} m)"
        }
        return instant
    }

    private companion object {
        /** Moments de la sortie où le rendu est contrôlé, en part de sa durée. */
        val PARTS_CONTROLEES = listOf(0.05, 0.25, 0.5, 0.75, 0.95)

        const val IMAGES_PAR_SECONDE = 10
        const val PROPRIETE_FENETRE = "guidage.simulateur"
    }
}
