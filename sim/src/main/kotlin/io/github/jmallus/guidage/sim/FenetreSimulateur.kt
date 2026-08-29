package io.github.jmallus.guidage.sim

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalLookAndFeel

/**
 * Un champ à afficher, tel que l'appareil le dessinerait.
 *
 * Les pixels sont donnés en ARGB rangés par lignes, et non en image : la fenêtre vit dans un
 * module qui ne connaît pas Android, et le rendu dans un test compilé contre « android.jar ».
 * Un tableau d'entiers est le plus riche des types que les deux mondes partagent.
 */
class Champ(
    /** Le nom du champ dans le sélecteur du Karoo, écrit au-dessus de son image. */
    val nom: String,
    val pixels: IntArray,
    val largeur: Int,
    val hauteur: Int,
)

/** Ce que le clavier demande au simulateur, lu par la boucle de rendu. */
enum class Commande {
    PAUSE,
    RECULER,
    AVANCER,
    PLUS_VITE,
    MOINS_VITE,
    PORTEE,
    ZONE,
    HORS_ITINERAIRE,
    RECOMMENCER,
    AGRANDIR,
    REDUIRE,
}

/**
 * La fenêtre du simulateur.
 *
 * Elle ne dessine rien du tableau de bord : elle reçoit des images déjà faites et les affiche.
 *
 * Elle ne connaît d'ailleurs rien d'Android — pas même le type de ses images, qui lui
 * arrivent en tableau de pixels. Ce n'est pas de la prudence mais une nécessité : le rendu
 * s'exécute dans un test unitaire d'Android, compilé contre « android.jar », d'où « java.awt »
 * est absent. Les deux mondes ne peuvent donc se parler qu'en types communs — des entiers et
 * une chaîne — et c'est très bien ainsi.
 *
 * Le fil compte aussi. Le rendu appartient au fil du test, l'interface Swing au sien : c'est
 * pourquoi la fenêtre reçoit des pixels au lieu d'aller les chercher.
 */
class FenetreSimulateur(
    agrandissement: Double = 1.0,
    /**
     * Largeur du champ en pixels du Karoo. Deux points de moins que l'écran : le système
     * garde un liseré de chaque côté.
     */
    private val largeurChamp: Int = LARGEUR_ECRAN,
    /**
     * Hauteur du champ en pixels du Karoo, qui n'est pas celle de l'écran : le bandeau d'état
     * en garde une bande en haut. Elle ne sert qu'à dimensionner la fenêtre avant la première
     * image ; ensuite, c'est l'image reçue qui commande.
     */
    private val hauteurChamp: Int = HAUTEUR_ECRAN,
) {

    private val ouverte = AtomicBoolean(true)
    private val commandes = ConcurrentLinkedQueue<Commande>()

    private var echelle = agrandissement

    /**
     * Points logiques par pouce de l'écran hôte.
     *
     * Java ne sait pas mesurer un écran. `Toolkit.getScreenResolution` rend ce que le système
     * déclare, et macOS déclare soixante-douze quel que soit l'écran — une valeur héritée de
     * la typographie, sans rapport avec la densité réelle, qui tourne plutôt autour de cent
     * dix à cent trente points logiques au pouce sur les portables actuels.
     *
     * D'où la règle graduée à côté de l'image, et la propriété qui permet de fixer la valeur
     * une bonne fois : `-Dguidage.ppp=125`. Pour la trouver, diviser la largeur de l'écran en
     * points par sa largeur en pouces.
     */
    private val pointsParPouce: Double =
        System.getProperty(PROPRIETE_PPP)?.toDoubleOrNull()
            ?: Toolkit.getDefaultToolkit().screenResolution.toDouble()

    /** Points logiques par pixel du Karoo pour que l'image ait la taille physique de l'écran. */
    private val tailleReelle: Double = (pointsParPouce / MM_PAR_POUCE) / PIXELS_PAR_MM

    /** Le facteur effectivement appliqué : la taille réelle, corrigée à la main si besoin. */
    private val facteur: Double get() = tailleReelle * echelle

    /**
     * Le tableau de bord à gauche, les autres champs empilés à droite.
     *
     * Tous au **même facteur** : c'est la seule disposition qui permette de juger des tailles
     * de texte d'un champ à l'autre. Les mettre chacun à sa taille confortable donnerait des
     * chiffres qui paraissent comparables et ne le sont pas — le défaut même qu'on a passé la
     * journée à corriger sur la hauteur du champ.
     */
    private val toile = object : JPanel() {
        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            if (affiches.isEmpty()) return
            val plan = graphics as Graphics2D
            plan.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            plan.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)

            val premier = affiches.first()
            val hauteurPrincipale = (premier.second.height * facteur).toInt()
            val y = (height - hauteurPrincipale) / 2
            val x = MARGE_REGLE + 12

            plan.dessiner(premier, x, y)
            dessinerRegle(plan, x, y, hauteurPrincipale)

            var colonne = x + (premier.second.width * facteur).toInt() + ECART_COLONNES
            var haut = y
            var largeurColonne = 0
            affiches.drop(1).forEach { champ ->
                val hauteurChamp = (champ.second.height * facteur).toInt() + ECART_CHAMPS + LIBELLE
                // On repart en haut d'une nouvelle colonne dès que la pile dépasserait le
                // champ principal : six champs empilés feraient une fenêtre trois fois plus
                // haute que l'écran qu'elle prétend montrer.
                if (haut > y && haut - y + hauteurChamp > hauteurPrincipale) {
                    colonne += largeurColonne + ECART_COLONNES
                    haut = y
                    largeurColonne = 0
                }
                plan.dessiner(champ, colonne, haut)
                largeurColonne = max(largeurColonne, (champ.second.width * facteur).toInt())
                haut += hauteurChamp
            }
        }

        /** Le champ, et son nom juste au-dessus : sans lui, on juge une image sans savoir laquelle. */
        private fun Graphics2D.dessiner(champ: Pair<String, BufferedImage>, x: Int, y: Int) {
            val (nom, image) = champ
            val largeur = (image.width * facteur).toInt()
            val hauteur = (image.height * facteur).toInt()
            color = Color(0x94, 0x8C, 0x7C)
            drawString("$nom · ${image.width}×${image.height}", x, y - 5)
            drawImage(image, x, y, largeur, hauteur, null)
        }
    }

    /**
     * La règle graduée en millimètres, contre le bord gauche de l'image.
     *
     * Elle est à l'échelle de l'image et non de l'écran : c'est tout son intérêt. Posez une
     * vraie règle à côté ; si les millimètres coïncident, le tableau de bord est à sa taille
     * physique, et sinon les touches + et - la corrigent. Aucune interrogation du système ne
     * peut remplacer cette vérification-là, aucun système ne sachant dire la taille de son
     * écran en millimètres.
     */
    private fun dessinerRegle(plan: Graphics2D, imageX: Int, imageY: Int, hauteur: Int) {
        val parMm = facteur * PIXELS_PAR_MM
        if (parMm < 0.8) return
        plan.color = Color(0x94, 0x8C, 0x7C)
        plan.font = Font(Font.MONOSPACED, Font.PLAIN, 9)
        val droite = imageX - 5
        var mm = 0
        while (mm * parMm <= hauteur) {
            val y = imageY + (mm * parMm).toInt()
            val longueur = when {
                mm % 10 == 0 -> 9
                mm % 5 == 0 -> 6
                else -> 3
            }
            plan.drawLine(droite - longueur, y, droite, y)
            if (mm % 10 == 0 && mm > 0) {
                val texte = mm.toString()
                plan.drawString(texte, droite - longueur - 3 - plan.fontMetrics.stringWidth(texte), y + 3)
            }
            mm++
        }
    }

    private val etat = JLabel(" ")
    private val aide = JLabel(" ")
    private val cadre = JFrame("Guidage — simulateur")

    @Volatile
    private var affiches: List<Pair<String, BufferedImage>> = emptyList()

    /** Vrai dès que la fenêtre a été redimensionnée sur les champs réellement reçus. */
    private var ajustee = false

    init {
        toile.background = Color(0x20, 0x22, 0x24)
        toile.preferredSize = tailleVoulue()

        etat.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        etat.foreground = Color(0xE8, 0xE4, 0xDA)
        aide.text = ligneAide()
        aide.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        aide.foreground = Color(0x94, 0x8C, 0x7C)

        val pied = JPanel(BorderLayout())
        pied.background = Color(0x15, 0x18, 0x1A)
        pied.add(etat, BorderLayout.NORTH)
        pied.add(aide, BorderLayout.SOUTH)

        cadre.contentPane.background = Color(0x15, 0x18, 0x1A)
        cadre.layout = BorderLayout()
        cadre.add(toile, BorderLayout.CENTER)
        cadre.add(pied, BorderLayout.SOUTH)
        cadre.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        cadre.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) {
                ouverte.set(false)
            }
        })
        cadre.isFocusable = true
        cadre.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                val commande = when (event.keyCode) {
                    KeyEvent.VK_SPACE -> Commande.PAUSE
                    KeyEvent.VK_LEFT -> Commande.RECULER
                    KeyEvent.VK_RIGHT -> Commande.AVANCER
                    KeyEvent.VK_UP -> Commande.PLUS_VITE
                    KeyEvent.VK_DOWN -> Commande.MOINS_VITE
                    KeyEvent.VK_Z -> Commande.PORTEE
                    KeyEvent.VK_P -> Commande.ZONE
                    KeyEvent.VK_H -> Commande.HORS_ITINERAIRE
                    KeyEvent.VK_R -> Commande.RECOMMENCER
                    KeyEvent.VK_PLUS, KeyEvent.VK_EQUALS, KeyEvent.VK_ADD -> Commande.AGRANDIR
                    KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> Commande.REDUIRE
                    KeyEvent.VK_ESCAPE -> {
                        fermer()
                        return
                    }
                    else -> return
                }
                commandes.add(commande)
            }
        })

        // Le clic sur l'image fait tourner la portée, comme l'appui du doigt sur le champ le
        // fait sur le Karoo. C'est la seule commande de l'appareil qui existe pour de bon —
        // tout le reste du clavier est du confort de banc d'essai, sans équivalent en roulant.
        // Le cadre reprend le focus juste après : sans cela, un clic couperait le clavier.
        toile.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                commandes.add(Commande.PORTEE)
                cadre.requestFocus()
            }
        })

        cadre.pack()
        cadre.setLocationRelativeTo(null)
        cadre.isVisible = true
        cadre.requestFocus()
    }

    val estOuverte: Boolean get() = ouverte.get()

    /** Retire la prochaine commande du clavier, ou null s'il n'y en a plus. */
    fun prochaineCommande(): Commande? = commandes.poll()

    /**
     * Affiche les champs, le premier tenant la colonne de gauche.
     *
     * Tous à la fois et non l'un après l'autre : un champ ne se juge pas seul. Le tableau de
     * bord donne l'échelle des chiffres, le profil et la côte doivent s'y accorder, et une
     * touche qui ferait défiler les vues obligerait à comparer de mémoire.
     */
    fun montrer(champs: List<Champ>, ligneEtat: String) {
        if (champs.isEmpty()) return
        affiches = champs.map { it.nom to versImageSwing(it.pixels, it.largeur, it.hauteur) }
        SwingUtilities.invokeLater {
            etat.text = " $ligneEtat"
            if (!ajustee) {
                ajustee = true
                toile.preferredSize = tailleVoulue()
                cadre.pack()
            }
            toile.repaint()
        }
    }

    /**
     * Corrige l'échelle à la main, par pas fins.
     *
     * Les pas étaient grossiers du temps où l'on cherchait seulement une image confortable.
     * Ils servent maintenant à retomber sur la taille physique de l'appareil, la règle en
     * témoin : un pas de vingt-cinq pour cent la dépasse à tous les coups.
     */
    fun agrandir(pas: Double) {
        echelle = (echelle * pas).coerceIn(0.25, 6.0)
        SwingUtilities.invokeLater {
            aide.text = ligneAide()
            toile.preferredSize = tailleVoulue()
            cadre.pack()
            toile.repaint()
        }
    }

    /**
     * La taille de la fenêtre.
     *
     * Avant la première image, seul le champ principal est connu — d'où les dimensions passées
     * au constructeur, qui ne servent qu'à ouvrir la fenêtre à peu près juste. Dès que les
     * champs arrivent, elle se recalcule sur eux et la fenêtre se réajuste une fois.
     */
    private fun tailleVoulue(): Dimension {
        val images = affiches.map { it.second }
        val principal = images.firstOrNull()
        val largeurPrincipale = ((principal?.width ?: largeurChamp) * facteur).toInt()
        val hauteurPrincipale = ((principal?.height ?: hauteurChamp) * facteur).toInt()

        // La même mise en colonnes qu'au dessin, comptée à blanc : la fenêtre doit s'ouvrir à
        // la taille de ce qu'elle affichera, et non l'apprendre en le peignant.
        var largeurAnnexes = 0
        var largeurColonne = 0
        var haut = 0
        images.drop(1).forEach { image ->
            val hauteurChampAnnexe = (image.height * facteur).toInt() + ECART_CHAMPS + LIBELLE
            if (haut > 0 && haut + hauteurChampAnnexe > hauteurPrincipale) {
                largeurAnnexes += largeurColonne + ECART_COLONNES
                haut = 0
                largeurColonne = 0
            }
            largeurColonne = max(largeurColonne, (image.width * facteur).toInt())
            haut += hauteurChampAnnexe
        }
        if (largeurColonne > 0) largeurAnnexes += largeurColonne + ECART_COLONNES

        return Dimension(
            MARGE_REGLE + 12 + largeurPrincipale + largeurAnnexes + 24,
            max(hauteurPrincipale, haut) + LIBELLE + 24,
        )
    }

    /**
     * La ligne d'aide, qui porte aussi la largeur physique obtenue.
     *
     * C'est le seul endroit où l'on peut dire si l'on est à la bonne taille sans sortir une
     * règle : trente et un millimètres, et c'est celle de l'appareil.
     */
    private fun ligneAide(): String {
        val largeurMm = largeurChamp * facteur / (pointsParPouce / MM_PAR_POUCE)
        return " %.1f mm de large (×%.2f, écran déclaré à %.0f ppp) · %s".format(
            largeurMm,
            echelle,
            pointsParPouce,
            AIDE,
        )
    }

    fun fermer() {
        ouverte.set(false)
        SwingUtilities.invokeLater { cadre.dispose() }
    }

    private companion object {
        /** L'écran du Karoo 3, en points : de quoi dimensionner la fenêtre avant la première image. */
        const val LARGEUR_ECRAN = 480
        const val HAUTEUR_ECRAN = 800

        const val AIDE =
            "clic ou z portée · espace pause · ←/→ ±10 s · ↑/↓ vitesse · p carte/profil · " +
                "h hors-itinéraire · r au départ · +/- ajuster · échap quitter"

        /** Largeur de l'écran du Karoo 3, en millimètres : deux pouces et demi de diagonale. */
        const val LARGEUR_MM = 31.34

        const val MM_PAR_POUCE = 25.4

        /** Pixels du Karoo par millimètre de son écran. */
        const val PIXELS_PAR_MM = LARGEUR_ECRAN / LARGEUR_MM

        /** Place réservée à gauche de l'image pour la règle graduée. */
        const val MARGE_REGLE = 34

        /** Blanc entre la colonne du tableau de bord et celle des autres champs. */
        const val ECART_COLONNES = 28

        /** Blanc entre deux champs empilés. */
        const val ECART_CHAMPS = 10

        /** Hauteur réservée au nom écrit au-dessus de chaque champ. */
        const val LIBELLE = 18

        /** Propriété qui fixe la densité de l'écran hôte, faute que le système sache la dire. */
        const val PROPRIETE_PPP = "guidage.ppp"
    }
}

/**
 * Des pixels ARGB en image Swing.
 *
 * Le format ARGB d'Android se lit tel quel dans celui de Swing : il n'y a rien à convertir,
 * seulement à recopier.
 */
private fun versImageSwing(pixels: IntArray, largeur: Int, hauteur: Int): BufferedImage {
    val image = BufferedImage(largeur, hauteur, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, largeur, hauteur, pixels, 0, largeur)
    return image
}

/**
 * Prépare l'ouverture d'une fenêtre, à appeler avant d'en construire une.
 *
 * Deux obstacles, tous deux dus au fait que le simulateur s'exécute sous un lanceur de tests.
 *
 * **L'écran.** Un lanceur de tests tourne « sans écran » : c'est le bon réglage pour tous les
 * autres tests, et c'est précisément ce qui fait lever une `HeadlessException` au premier
 * `JFrame`. Le régler depuis le fichier de construction ne suffit pas — le plugin Android
 * repose sa propre valeur, et l'ordre des deux ne se maîtrise pas. On s'en charge donc ici,
 * en deux temps : la propriété d'abord, puis la réponse que `java.awt` a peut-être déjà
 * retenue, car elle n'est calculée qu'une seule fois, à la première question posée. Après
 * quoi changer la propriété ne change plus rien. L'ouverture du module `java.desktop` que
 * réclame cet effacement est déclarée dans `app/build.gradle.kts`.
 *
 * **Le thème.** Robolectric charge lui-même les classes de l'application, et Swing va chercher
 * le thème de la plateforme **par son nom** — donc en passant par ce chargeur-là. Sur un Mac,
 * `com.apple.laf.AquaLookAndFeel` se retrouve ainsi chargé hors du module `java.desktop` et
 * n'a plus le droit d'accéder à `sun.awt`, ce qui casse net l'ouverture de la fenêtre. Le
 * thème de Java, lui, vit sous `javax.` — que Robolectric laisse au chargeur du système, où
 * il retrouve ses droits. On le pose donc soi-même, et par instance plutôt que par nom : sur
 * un Mac, le thème par défaut est écrit dans un fichier de réglages livré avec la machine
 * virtuelle, qui l'emporterait sur une simple propriété. La fenêtre a ainsi l'air d'une
 * fenêtre Java plutôt que d'une fenêtre macOS ; pour un banc d'essai, c'est sans importance.
 */
fun preparerLAffichage() {
    System.setProperty("java.awt.headless", "false")
    System.setProperty("swing.defaultlaf", METAL)
    runCatching {
        val retenue = GraphicsEnvironment::class.java.getDeclaredField("headless")
        retenue.isAccessible = true
        retenue.set(null, null)
    }.onFailure {
        println("Simulateur : l'état « sans écran » retenu n'a pas pu être effacé (${it.message})")
    }
    runCatching {
        UIManager.setLookAndFeel(MetalLookAndFeel())
    }.onFailure {
        println("Simulateur : le thème Java n'a pas pu être posé (${it.message})")
    }
}

/** Le thème de Java, le seul que le chargeur de classes de Robolectric laisse intact. */
private const val METAL = "javax.swing.plaf.metal.MetalLookAndFeel"
