package io.github.jmallus.guidage.sim

import android.graphics.Bitmap
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

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
 * Le partage est volontaire. Le rendu passe par le moteur graphique Android, qui vit sur le
 * fil du test ; l'interface Swing vit sur le sien. Faire dessiner Skia depuis le fil de
 * l'interface reviendrait à mêler deux mondes qui n'ont aucune raison de s'entendre, pour ne
 * gagner que la disparition d'un tampon.
 */
class FenetreSimulateur(agrandissement: Double = 1.0) {

    private val ouverte = AtomicBoolean(true)
    private val commandes = ConcurrentLinkedQueue<Commande>()

    private var echelle = agrandissement

    private val toile = object : JPanel() {
        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val image = affichee ?: return
            val plan = graphics as Graphics2D
            plan.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
            )
            val largeur = (image.width * echelle).toInt()
            val hauteur = (image.height * echelle).toInt()
            val x = (width - largeur) / 2
            val y = (height - hauteur) / 2
            plan.drawImage(image, x, y, largeur, hauteur, null)
        }
    }

    private val etat = JLabel(" ")
    private val aide = JLabel(AIDE)
    private val cadre = JFrame("Guidage — simulateur")

    @Volatile
    private var affichee: BufferedImage? = null

    init {
        toile.background = Color(0x20, 0x22, 0x24)
        toile.preferredSize = Dimension(
            (Simulateur.LARGEUR * echelle).toInt() + 80,
            (Simulateur.HAUTEUR * echelle).toInt() + 40,
        )

        etat.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        etat.foreground = Color(0xE8, 0xE4, 0xDA)
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
        cadre.pack()
        cadre.setLocationRelativeTo(null)
        cadre.isVisible = true
        cadre.requestFocus()
    }

    val estOuverte: Boolean get() = ouverte.get()

    /** Retire la prochaine commande du clavier, ou null s'il n'y en a plus. */
    fun prochaineCommande(): Commande? = commandes.poll()

    fun montrer(image: Bitmap, ligneEtat: String) {
        affichee = versImageSwing(image)
        SwingUtilities.invokeLater {
            etat.text = " $ligneEtat"
            toile.repaint()
        }
    }

    fun agrandir(facteur: Double) {
        echelle = (echelle * facteur).coerceIn(0.5, 3.0)
        SwingUtilities.invokeLater {
            toile.preferredSize = Dimension(
                (Simulateur.LARGEUR * echelle).toInt() + 80,
                (Simulateur.HAUTEUR * echelle).toInt() + 40,
            )
            cadre.pack()
            toile.repaint()
        }
    }

    fun fermer() {
        ouverte.set(false)
        SwingUtilities.invokeLater { cadre.dispose() }
    }

    private companion object {
        const val AIDE =
            " espace pause · ←/→ ±10 s · ↑/↓ vitesse · z portée · p carte/profil · " +
                "h hors-itinéraire · r au départ · +/- taille · échap quitter"
    }
}

/**
 * Une image Android en image Swing.
 *
 * Le passage par un tableau de pixels est le seul chemin sûr : les deux mondes n'ont aucun
 * tampon en commun, et le format ARGB de l'un se lit tel quel dans celui de l'autre.
 */
fun versImageSwing(bitmap: Bitmap): BufferedImage {
    val largeur = bitmap.width
    val hauteur = bitmap.height
    val pixels = IntArray(largeur * hauteur)
    bitmap.getPixels(pixels, 0, largeur, 0, 0, largeur, hauteur)
    val image = BufferedImage(largeur, hauteur, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, largeur, hauteur, pixels, 0, largeur)
    return image
}
