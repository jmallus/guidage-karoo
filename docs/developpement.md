# Développement et publication

Ce que le [README](../README.md) n'a pas à porter : le réglage fin du banc d'essai, la
signature des APK et la publication d'une version. Rien ici n'est nécessaire pour installer
l'extension ni pour rouler avec.

## Le banc d'essai à la taille réelle

La fenêtre du simulateur s'ouvre à la taille **physique** de l'écran du Karoo 3 — 31,3 mm de
large sur 52,2 mm de haut, soit 2,4 pouces de diagonale. C'est tout l'intérêt du banc d'essai :
juger d'un corps de police ou d'une épaisseur de trait sur une image trois fois trop grande ne
dit rien de ce qu'on lira en roulant.

Encore faut-il connaître la densité de l'écran hôte, et **Java ne sait pas la mesurer** : sur
macOS il déclare soixante-douze points au pouce quel que soit l'écran, valeur héritée de la
typographie et sans rapport avec la réalité, qui tourne plutôt autour de 110 à 130 sur un
portable récent. La fenêtre s'ouvre donc à une taille plausible mais fausse tant qu'on ne la
lui a pas dite :

```bash
./gradlew :app:simulateur -Pguidage.ppp=125
```

Pour trouver la valeur : divisez la largeur de votre écran **en points** — celle que le système
affiche dans ses réglages d'affichage — par sa largeur **en pouces**. Sur un MacBook Pro 14
pouces en résolution par défaut : 1512 points pour 12,05 pouces, soit 125.

À défaut, la **règle graduée** à gauche de l'image est à la même échelle qu'elle. Posez-y une
vraie règle : si les millimètres coïncident, le tableau de bord est à sa taille physique ;
sinon, `+` et `-` l'ajustent par pas de cinq pour cent, et la ligne du bas affiche la largeur
obtenue.

## La place allouée à un champ

Le champ plein écran n'a pas les 480 × 800 points de l'écran : le Karoo garde une bande de
158 points en haut pour l'heure et la batterie, et un liseré d'un point de chaque côté. La
place réellement laissée, relevée sur un Karoo 3, est de **478 × 642** — soit près d'un
cinquième de la hauteur en moins.

Sur l'appareil la question ne se pose pas : `ViewConfig.viewSize` donne la place allouée et le
rendu s'y ajuste. Le banc d'essai, lui, doit la connaître : une hauteur trop généreuse y
montrerait des rangs plus espacés et des chiffres plus grands que ce qu'on lira en roulant.

La valeur, 642, est figée dans `Simulateur`, et se change sans recompiler pour un autre
appareil ou un autre gabarit de page :

```bash
./gradlew :app:simulateur -Pguidage.hauteur=600
```

Seule la taille du plein écran est un relevé. Celles des autres champs se déduisent de la
grille de soixante et restent donc des approximations, jusqu'à ce qu'on les pose sur une page
et qu'on lise la carte « Place allouée au champ ».

### La relever, sans rien brancher

Poser le champ **« Tableau de bord »** — dans le sélecteur, « Guidage » est le nom de
l'extension, pas celui du champ — sur une page, ouvrir cette page une fois, puis lancer
l'application Guidage depuis le launcher du Karoo. La carte **« Place allouée au champ »**, en
bas de l'écran de configuration, donne les dimensions telles que le système les a réellement
accordées : pour chaque champ posé, et en séparant l'édition de la sortie, car rien ne garantit
qu'elles coïncident.

Avec `adb`, la même chose se lit au journal :

```bash
adb logcat -s GuidageExtension:D | grep "champ ouvert"
```

Dans les deux cas s'affiche aussi le **corps natif** en sp, celui que le Karoo emploie pour un
champ numérique de cette taille : c'est la référence typographique de l'appareil, plus sûre que
celle d'une maquette.

## Régénérer les captures

Elles se refont **toutes seules**. Le workflow `captures` part à chaque poussée sur `main` qui
touche ce qui décide de l'affichage — les rendus, les constructeurs de modèle, les libellés, le
parcours d'aperçu, le simulateur. Il exécute les tests du simulateur, range les PNG dans
`docs/captures/` et les commite. Si les images sont identiques à celles du dépôt, il s'arrête
sans rien écrire.

Un changement dans `core/` ou dans le convertisseur de cartes ne déplace aucun pixel et ne le
déclenche donc pas. Au besoin, **Actions → captures → Run workflow** le force à la main.

Le dossier est vidé avant d'être rempli : une capture ne doit pas survivre au champ qu'elle
montrait.

## La clé de signature

L'APK construit localement est signé avec la **clé de debug** — suffisant pour installer sur
son propre Karoo, mais pas pour remplacer une version venue d'une Release, qu'Android refuse
d'écraser avec une signature différente.

La clé des Releases, elle, ne vit **pas dans le dépôt** : celui-ci est public, et une clé
lisible par tous laisserait n'importe qui signer un APK qu'Android installerait par-dessus
celui-ci sans broncher. Elle arrive d'un secret du dépôt, décodée par le CI hors de l'arbre de
travail. Sans ce secret, la construction reste possible et l'APK est signé en debug, mais
**aucune Release n'est publiée** : mieux vaut pas d'APK qu'un APK que le Karoo installera puis
refusera de mettre à jour.

| Secret | |
| --- | --- |
| `GUIDAGE_KEYSTORE_B64` | le magasin encodé en base64 — **obligatoire** |
| `GUIDAGE_KEYSTORE_PASSWORD` | son mot de passe — **obligatoire** |
| `GUIDAGE_KEY_ALIAS` | facultatif ; `guidage` à défaut |
| `GUIDAGE_KEY_PASSWORD` | facultatif ; celui du magasin à défaut, ce qui est le cas en PKCS12, où la clé n'a pas de mot de passe distinct |

Les valeurs facultatives se replient sur leur défaut **aussi quand le secret est vide**, et non
seulement quand il n'existe pas : un secret absent est transmis à la construction comme chaîne
vide, jamais comme variable non définie.

## Publier une version

Un tag `vX.Y.Z` déclenche la construction **et** crée la Release versionnée :

```bash
git fetch origin main
git tag v1.2 <le commit à taguer>
git push origin v1.2
```

Trois précautions, dont deux ont déjà coûté une publication.

**Ne jamais taguer un commit « Régénère les captures ».** Ces commits demandent à GitHub de
sauter la construction, et GitHub applique cette demande aux poussées de tags comme aux
autres : rien ne partirait, aucune Release ne serait créée, et rien ne le signalerait. Or ce
sont précisément eux qui se retrouvent en sommet de `main` juste après une fusion touchant
l'affichage — c'est-à-dire au moment où l'on veut publier.

Taguer le commit de fusion d'avant n'est pas non plus la réponse : les `screenshotUrls` du
manifeste sont figées sur le commit tagué, si bien que la fiche de l'extension montrerait les
captures d'avant le changement. Il faut un commit qui ne demande pas ce saut **et** qui
contienne les captures à jour — au besoin en poussant d'abord une modification de
documentation, ce qui décale le sommet sans rien changer au code.

**La simple mention de cette marque suffit à la déclencher.** GitHub la cherche littéralement,
n'importe où dans le message, sans se soucier du contexte : un message qui en *parle* la
déclenche. En parler dans un message de commit demande donc une périphrase.

**Si la carte doit changer, lancer d'abord le workflow `fond-de-carte`** et attendre sa fin :
le job `apk` télécharge la carte au début de son exécution, et démarrer trop tôt embarquerait
l'ancienne sans rien signaler.

### Ce que la Release porte, et ce que le Karoo lit

La page GitHub de la Release porte le numéro de version, le commit, la taille du fond de carte
embarqué et les instructions d'installation. Elle ne porte **pas** la liste des changements.

Celle-ci alimente le champ `releaseNotes` du `manifest.json` publié à côté de l'APK,
c'est-à-dire ce que le Karoo affiche sur la fiche de l'extension. Elle est composée à partir
des titres des pull requests fusionnées depuis le tag précédent.
