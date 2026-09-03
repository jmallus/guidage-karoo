# Développement et publication

Ce que le [README](../README.md) n'a pas à porter : compiler le projet, le faire tourner sur
une machine de bureau, et publier une version. Rien ici n'est nécessaire pour installer
l'extension ni pour rouler avec.

## Compiler

### Prérequis

- Android Studio (ou le SDK Android en ligne de commande) et un JDK 17+.
- Un jeton GitHub (voir ci-dessous) — nécessaire uniquement pour construire l'APK, pas pour
  lancer les tests du module `:core`.

### Le jeton GitHub (obligatoire)

**Pourquoi.** La bibliothèque `io.hammerhead:karoo-ext` n'est publiée que sur **GitHub
Packages**, dont le registre Maven refuse les requêtes anonymes — *même pour un paquet
public*. C'est une contrainte de GitHub, pas de Hammerhead. Sans jeton, Gradle échoue avec
`Could not resolve io.hammerhead:karoo-ext` (HTTP 401). Le dépôt Maven correspondant est
déjà déclaré dans `settings.gradle.kts` ; il ne manque que vos identifiants.

**Créer le jeton.**

1. github.com → votre avatar → **Settings**
2. tout en bas du menu de gauche → **Developer settings**
3. **Personal access tokens → Tokens (classic)** → *Generate new token (classic)*
4. cochez **uniquement la portée `read:packages`** — rien d'autre n'est utile ici
5. choisissez une expiration (1 an par exemple) et notez la date : la compilation cassera le
   jour où le jeton expirera
6. copiez la valeur `ghp_…` affichée : GitHub ne la remontrera jamais

> Les jetons **fine-grained** (l'autre onglet) ne conviennent pas : ils ne donnent pas accès
> aux paquets d'une autre organisation, et `karoo-ext` appartient à `hammerheadnav`.
> Il faut bien un jeton *classic*.

**Où le déclarer.** Deux emplacements possibles, avec les mêmes deux lignes :

```properties
gpr.user=votre-login-github
gpr.key=ghp_xxxxxxxxxxxxxxxxxxxx
```

- `local.properties`, à la racine du projet — non versionné, déjà listé dans `.gitignore` ;
- `~/.gradle/gradle.properties` — **le plus sûr** : le fichier vit hors du dépôt, il ne *peut*
  structurellement pas partir dans un commit, et il sert à tous vos projets.

> ⛔ **Jamais dans le `gradle.properties` du projet.** Ce fichier-là est versionné : le jeton
> se retrouverait publié au premier `git push`. Le nom ressemble à celui du fichier
> utilisateur, c'est le piège classique — s'il est à la racine du dépôt, ce n'est pas le bon.
>
> Si ça arrive : révoquez immédiatement le jeton sur GitHub (le supprimer du dépôt ne suffit
> pas, il reste consultable dans l'historique), puis regénérez-en un.

À défaut, les variables d'environnement `GITHUB_ACTOR` / `GITHUB_TOKEN` sont utilisées :
c'est ce mécanisme qu'emploie le workflow CI, via les secrets de dépôt `GPR_USER` / `GPR_KEY`
(sans ces secrets, le job de construction de l'APK s'ignore de lui-même).

**Si ça casse.** Un `401 Unauthorized` ou un `Could not resolve io.hammerhead:karoo-ext` au
moment du *sync* signale un jeton absent, mal collé ou expiré. Régénérez-le, recollez-le,
puis relancez avec `./gradlew --refresh-dependencies :app:assembleRelease`.

Ne commitez jamais le jeton : GitHub révoque automatiquement ceux qu'il détecte dans un
dépôt, mais mieux vaut ne pas en arriver là.

### Construire soi-même

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

> ⚠️ **Cet APK-là n'embarque pas le fond de carte.** Le téléchargement depuis la Release
> `carte` n'existe que dans le workflow du CI. L'extension fonctionnera, mais la minicarte
> restera vide et affichera « Fond de carte absent ». Pour un APK complet, passer par le CI —
> voir [Installer](../README.md#installer).

Localement, l'APK release est signé avec la **clé de debug** — suffisant pour installer sur son
propre Karoo (mode développeur activé, appareil connecté en USB ou en ADB Wi-Fi), mais pas
pour remplacer une version venue d'une Release, qu'Android refuse d'écraser avec une signature
différente. La clé des Releases ne vit pas dans le dépôt ; voir
[La clé de signature](#la-clé-de-signature).

Pour reconstruire sans machine, l'onglet **Actions → build → Run workflow** fait le même
travail, fond de carte compris.

### Tests

La logique de guidage — polylignes, profil, côtes, POI, alertes, allure apprise, budget
d'effort, virages, revêtement, réserve, format de fond de carte — vit dans le module `:core`,
sans dépendance Android : elle se teste sans SDK Android, sans appareil et **sans jeton**.

```bash
./gradlew :core:test :tools:test     # 232 tests, aucun prérequis
./gradlew :app:testDebugUnitTest     # 33 tests de plus, sous Robolectric ; demande le jeton
```

Les tests d'`:app` rendent les champs entiers et vérifient qu'à chaque portée la trace se
voit : une compilation ne dirait rien d'un écran noir, qui compile parfaitement. Les 265
tournent à chaque poussée.

## Le simulateur

Avant d'envoyer un APK vers le Karoo, on peut voir le tableau de bord **en mouvement** sur
une machine de bureau. À la racine du dépôt, là où se trouve `gradlew` :

```
./gradlew :app:simulateur
```

Il faut pour cela ce que demande déjà la compilation de l'APK — un JDK 17, le SDK Android et
le [jeton GitHub](#le-jeton-github-obligatoire), puisque `:app` dépend de `karoo-ext` — plus
deux choses : une **session graphique**, la fenêtre étant une fenêtre, et du **réseau au
premier lancement**, Robolectric téléchargeant une fois pour toutes son image d'Android.
Les lancements suivants se passent de réseau.

Le premier prend une minute ou deux : le module se compile, puis les tests de contrôle
passent avant que la fenêtre s'ouvre. C'est voulu — si le rendu est cassé, on l'apprend
avant de le regarder.

Une fenêtre s'ouvre et joue une sortie fictive de trente-deux kilomètres — trois côtes dont
un col, deux descentes, trois points de ravitaillement groupés au début puis vingt-six
kilomètres sans rien — à seize fois la vitesse réelle. La carte défile sous le ruban, la
marque de position glisse dessus et les chevrons filent devant elle, le bandeau de côte
avance, les aplats de zone changent de couleur avec l'effort.

Elle part **en fin d'après-midi**, à l'heure qui place l'arrivée estimée quelques minutes
avant le coucher du soleil. Ce n'est pas un détail de décor : le verdict d'« Avant la nuit »
se joue là, et une sortie partie le matin n'aurait montré qu'un « oui » à dix heures de
marge, c'est-à-dire rien du champ. L'heure se déduit d'un jour fixe et du coucher calculé
pour ce jour-là, de sorte que les images de contrôle ne changent pas d'une exécution à
l'autre.

La fenêtre se pilote au clavier :

| Touche | Effet |
| --- | --- |
| **clic** sur l'image | faire tourner la portée de la carte, comme l'appui du doigt sur le champ |
| `espace` | arrêt sur image |
| `←` `→` | reculer / avancer de dix secondes de lecture |
| `↑` `↓` | doubler / diviser la vitesse de lecture |
| `z` | la même chose au clavier — 300 m, 500 m, 1 km |
| `p` | passer de la carte au profil, et retour |
| `h` | simuler la sortie d'itinéraire : le tracé passe au rouge |
| `r` | revenir au départ |
| `+` `-` | ajuster l'échelle par pas de 5 %, la règle en témoin |
| `échap` | quitter |

**Les dix champs graphiques à la fois.** Les pleines pages d'abord — tableau de bord,
autonomie, avant la nuit, réserve, virages, revêtement — puis les champs de bande — profil à
venir, suivant la sortie, prochaine côte, budget d'effort —, en colonnes, tous alimentés par
le même instant de la sortie et tracés **au même facteur**. C'est la seule disposition qui
permette de juger des tailles de texte d'un champ à l'autre : les mettre chacun à sa taille
confortable donnerait
des chiffres qui paraissent comparables sans l'être. Le champ « Prochain point d'intérêt »
n'y figure pas — il est numérique, et c'est le Karoo qui le dessine.

L'horloge du champ « Suivant la sortie » est celle de la sortie jouée, non celle de la
machine : sa bascule attend qu'un état se confirme, et à seize fois la vitesse réelle une
hystérésis de trois secondes en durerait moins d'une demie.

**Ce qu'il montre est le code de l'appareil.** Ce n'est pas une seconde écriture de
l'affichage : le simulateur assemble l'état d'une sortie, puis appelle les constructeurs de
modèle et les rendus de l'extension — les mêmes classes exactement, dessinant dans le même
`Canvas` d'Android. C'est ce qui le sépare d'une maquette, et c'est pourquoi ses images
servent d'illustration à ce README plutôt qu'un dessin fait à part, qui dériverait.

Le `Canvas` d'Android n'existe pas sur une machine de bureau ; c'est Robolectric qui le
fournit, avec le vrai moteur Skia — mais seulement sous un lanceur de tests. D'où la forme
du simulateur : un test JUnit qui ouvre une fenêtre, et ne l'ouvre que si on la demande.

Ce qu'il ne simule pas : la chaîne karoo-ext elle-même — souscriptions, cadence de
rafraîchissement du système — et la carte hors ligne réelle, remplacée par un décor engendré
autour du coureur. La mise en page, les couleurs, les zones, les chevrons et le bandeau de
côte, eux, sont ceux de l'appareil.

Les mêmes tests tournent **sans fenêtre** à chaque poussée : ils vérifient que chaque portée
dessine bien la trace, ce qu'une compilation ne dirait pas — un écran noir compile très bien.
Les images de contrôle sont écrites dans `app/build/simulateur/`.

### Le banc d'essai à la taille réelle

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

### La place allouée à un champ

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

### Régénérer les captures

Elles se refont **toutes seules**. Le workflow `captures` part à chaque poussée sur `main` qui
touche ce qui décide de l'affichage — les rendus, les constructeurs de modèle, les libellés, le
parcours d'aperçu, le simulateur. Il exécute les tests du simulateur, range les PNG dans
`docs/captures/` et les commite. Si les images sont identiques à celles du dépôt, il s'arrête
sans rien écrire.

Un changement dans `core/` ou dans le convertisseur de cartes ne déplace aucun pixel et ne le
déclenche donc pas. Au besoin, **Actions → captures → Run workflow** le force à la main.

Le dossier est vidé avant d'être rempli : une capture ne doit pas survivre au champ qu'elle
montrait.

## Architecture

```
core/                        module JVM pur, testable — aucune dépendance Android
  Polyline.kt                décodage des polylignes Google (précision 5 et 1)
  ElevationProfile.kt        profil altimétrique : interpolation, extraction, D+
  Route.kt                   itinéraire, côtes, POI, état de guidage
  Guidance.kt                côte en cours/à venir, prochain POI, fenêtre de profil
  FisheyeScale.kt            l'échelle du profil : fine devant, comprimée au loin
  AlertEngine.kt             décide quelles annonces déclencher, sans répétition
  Pacing.kt                  apprend les deux allures, en déduit l'arrivée et sa marge
  Resupply.kt                la réserve : dernier point avant la prochaine traversée
  EffortBudget.kt            le coût du reste en kilojoules, et le dépensé en regard
  Bends.kt                   les virages lus sur la polyligne, rayon et sens
  RideContext.kt             ce que la sortie fait, et donc ce que le champ montre
  Surfaces.kt                le revêtement, en posant le tracé sur le fond de carte
  Contrast.kt                APCA : encre noire ou blanche sur un aplat de zone
  Format.kt                  formatage distances / dénivelés / pentes (métrique & impérial)
  map/                       le format de fond de carte : écriture, lecture, découpe

tools/                       convertisseur OpenStreetMap, hors de l'APK, exécuté par le CI

app/                         extension Android
  karoo/KarooFlows.kt        ponts callback → Flow de karoo-ext
  karoo/GuidanceProvider.kt  assemble l'état de guidage depuis les événements Karoo
  karoo/RideDataProvider.kt  les relevés chiffrés, et l'allure apprise au fil de la sortie
  extension/                 service d'extension, les onze champs, alertes
  extension/*Models.kt       construisent ce qu'affichent les champs — partagés avec le simulateur
  ui/                        rendu des champs (Canvas + Glance) et écran de réglages
  settings/                  persistance des réglages
  src/test/…/sim/            le simulateur de bureau, hors de l'APK

sim/                         la fenêtre Swing du simulateur, hors de l'APK
```

Les `*Models.kt` d'`extension/` sont la pièce qui tient l'ensemble : le champ Karoo et le
simulateur de bureau appellent **les mêmes**, et il n'existe donc nulle part une seconde
écriture de l'affichage qui pourrait dériver de la première.

### D'où viennent les données

| Donnée | Source Karoo |
| --- | --- |
| Itinéraire, profil, côtes, POI | événement `OnNavigationState` |
| Position sur l'itinéraire | `DISTANCE_TO_DESTINATION` (distance totale − distance restante) |
| Pente instantanée | `ELEVATION_GRADE` |
| Allure du coureur | mesurée en roulant sur `SMOOTHED_3S_AVERAGE_SPEED`, `ELEVATION_GRADE` et `SMOOTHED_3S_AVERAGE_POWER` |
| Effort déjà produit | `ENERGY_OUTPUT`, que le Karoo intègre lui-même |
| Zones et unités du coureur | événement `UserProfile` |
| État de la sortie | événement `RideState` |

## Publier

### La mise à jour depuis le Karoo

Après la première installation, les versions suivantes s'installent depuis le Karoo :
appui long sur l'icône de l'extension dans le menu, puis **Mise à jour**. La fiche affiche au
passage la version, les changements et les captures des champs.

Cela tient à une ligne du manifeste Android :

```xml
<meta-data
    android:name="io.hammerhead.karooext.MANIFEST_URL"
    android:value="https://github.com/jmallus/guidage-karoo/releases/latest/download/manifest.json" />
```

Karoo OS lit ce `manifest.json` — publié par le CI à côté de l'APK, au format
[`KarooAppManifest`](https://github.com/hammerheadnav/karoo-ext) — compare son
`latestVersionCode` à la version installée, et propose la mise à jour le cas échéant.

Deux choses méritent d'être dites :

- **C'est Karoo OS qui va chercher le fichier, pas l'extension.** Celle-ci ne déclare toujours
  *aucune* permission : ni `INTERNET`, ni `REQUEST_INSTALL_PACKAGES`. La garantie de
  fonctionnement hors ligne est intacte, et c'est la raison d'avoir écarté un vérificateur
  embarqué.
- **`releases/latest` désigne la dernière Release non préliminaire**, donc le dernier tag
  `vX.Y.Z`. La Release `latest`, qui suit chaque poussée sur `main`, est marquée préliminaire
  et reste invisible pour l'appareil. Le Karoo ne voit que ce qui est publié à dessein.

### Quelle Release prendre

| Release | Ce que c'est |
| --- | --- |
| **`v1.0`, `v1.0.1`, …** | Versions figées, publiées par un tag. Rien ne les écrase. C'est ce qu'il faut prendre pour rouler. |
| **`Guidage — dernière construction`** (`latest`) | La dernière construction, **quelle que soit la branche poussée**. Marquée *pre-release*, réécrite à chaque poussée. Pour essayer un correctif à chaud, pas pour partir en sortie. |
| **`Fond de carte`** (`carte`) | Le `.gkmap` seul, sans l'application. Utile pour vérifier son poids ou le copier à la main ; inutile sinon, puisque l'APK l'embarque déjà. |

Les notes de chaque Release portent le **numéro de version, le commit et la taille du fond de
carte embarqué** : de quoi savoir exactement ce qu'on installe.

Publier une version est décrit [plus bas](#publier-une-version).

### La clé de signature

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

### Publier une version

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
