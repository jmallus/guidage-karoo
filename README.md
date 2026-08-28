# Guidage — extension Hammerhead Karoo

Extension Karoo (Karoo 2 / Karoo 3) qui enrichit le **guidage d'itinéraire** : elle lit
l'itinéraire chargé dans le Karoo et en tire un profil altimétrique à venir, des informations
sur la prochaine côte, la distance au prochain point d'intérêt, et une annonce à l'écran à
l'approche de celui-ci.

Tout est calculé **sur l'appareil**, à partir des données que Karoo OS fournit déjà :
aucune connexion réseau, aucun compte, rien à synchroniser.

<img src="docs/planches/tableau-de-bord.png" alt="Le champ plein écran : effort en haut, transmission et fréquence cardiaque à gauche, minicarte à droite, distance et arrivée en bas" width="340">

Le champ plein écran ci-dessus est reconstruit à la taille qu'il occupe sur l'écran du
Karoo 3. Les [planches](docs/planches.md) en montrent le détail — chaque zone, les trois
portées de la carte, la légende du fond — et permettent de juger d'une retouche d'affichage
sans construire un APK.

## Ce que ça ajoute sur le vélo

### Champs de données

| Champ | Type | Contenu |
| --- | --- | --- |
| **Tableau de bord** | graphique, plein écran | Une page tenant tout l'écran : vitesse, cadence et puissance sur 3 secondes, transmission en schéma, fréquence cardiaque, minicarte orientée cap en haut sur fond de carte hors ligne, distance parcourue, pente, distance restante, heure d'arrivée **avec sa marge**, et le profil de la côte en cours. Vitesse, puissance et fréquence cardiaque prennent la couleur de leur zone. Une pression change l'échelle de la carte. |
| **Profil à venir** | graphique | Le profil altimétrique de la portion devant vous (portée réglable de 1 à 15 km), rempli en couleur selon la pente, côtes de l'itinéraire surlignées avec leur pente moyenne, dénivelé positif restant sur la fenêtre. |
| **Prochaine côte** | graphique | Avant la côte : distance jusqu'à son pied, longueur, pente moyenne, dénivelé. Dans la côte : distance et dénivelé restants jusqu'au sommet, avec barre de progression. Disponible aussi comme valeur numérique (distance) pour d'autres usages. |
| **Prochain point d'intérêt** | numérique | Distance jusqu'au prochain POI de l'itinéraire (eau, ravitaillement, contrôle…), formatée dans vos unités. |

« Profil à venir » et « Prochaine côte » s'adaptent à la taille et à l'alignement configurés
dans le profil de page. Tous affichent un aperçu réaliste dans l'écran d'édition des pages.

### L'heure d'arrivée

Celle du Karoo extrapole la moyenne de la sortie, ce qui revient à supposer qu'un col se monte
à la vitesse d'un faux plat : sur un parcours qui garde ses côtes pour la fin, l'heure annoncée
recule de minute en minute.

L'extension en mesure deux, séparément — la vitesse sur terrain roulant en km/h, la vitesse
ascensionnelle en montée en mètres par heure — puis les applique au terrain qui reste, tel que
le profil le décrit : le dénivelé d'un côté, la distance hors montées de l'autre, jamais les
deux pour le même mètre. Les deux allures s'oublient doucement, sur un quart d'heure, car
celle de la sixième heure n'est pas celle de la première.

Le libellé porte la marge qu'on reconnaît à l'estimation — « ARRIVÉE ± 5 MIN » — calculée sur
la régularité observée de chacune des deux allures et sur ce qui reste à faire. Elle se
resserre en approchant. Tant que l'allure n'est pas assez observée, le champ affiche l'heure du
Karoo sans marge : mieux vaut la sienne qu'une heure tirée de trente secondes de roulage.

### Annonces in-ride

Pendant l'enregistrement d'une sortie :

- **Point d'intérêt** — « Fontaine — Dans 500 m »

Chaque annonce n'est émise qu'une fois par point, et la distance de déclenchement est
réglable.

Les côtes n'en déclenchent plus. Elles en avaient deux — une au pied, une avant le sommet —
qui couvraient l'écran au moment précis où l'on regarde le bandeau de profil pour savoir ce
qui reste à monter. La bande est là en permanence et porte déjà le rang de la côte et la
distance au sommet : l'annonce ne disait rien de plus, elle le disait par-dessus.

### Action bonus

L'action **« Annoncer la prochaine côte »** peut être assignée à un bouton de commande
(via les réglages Karoo) pour afficher à la demande le résumé de la côte suivante.

## Réglages

L'application « Guidage » du launcher affiche l'état courant (itinéraire, distance restante,
prochaine côte, prochain point) et permet de régler :

- la portée du profil affiché (1 à 15 km) ;
- la coloration du profil selon la pente ;
- l'activation et la distance d'annonce des points d'intérêt.

Les changements sont pris en compte immédiatement, sans redémarrer l'extension.

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

### Construire et installer

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

L'APK release est signé avec la clé de debug pour pouvoir être installé directement sur le
Karoo (mode développeur activé, appareil connecté en USB ou en ADB Wi-Fi).

Après l'installation, les champs apparaissent dans **Profils → page → ajouter un champ de
données → Guidage**.

### Tests

La logique de guidage (décodage des polylignes, profil, côtes, POI, alertes, formatage) vit
dans le module `:core`, sans dépendance Android : elle se teste sans SDK ni appareil.

```bash
./gradlew :core:test
```

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

Une fenêtre s'ouvre et joue une sortie fictive de douze kilomètres — deux côtes, une
descente, un point d'eau — à huit fois la vitesse réelle. La carte défile sous le ruban, la
marque de position glisse dessus et les chevrons filent devant elle, le bandeau de côte
avance, les aplats de zone changent de couleur avec l'effort.

### La taille réelle

La fenêtre s'ouvre à la taille **physique** de l'écran du Karoo 3 — 31,3 mm de large sur
52,2 mm de haut, deux pouces et demi de diagonale. C'est tout l'intérêt du banc d'essai :
juger d'un corps de police ou d'une épaisseur de trait sur une image trois fois trop grande
ne dit rien de ce qu'on lira en roulant.

Encore faut-il connaître la densité de l'écran hôte, et **Java ne sait pas la mesurer** :
sur macOS il déclare soixante-douze points au pouce quel que soit l'écran, valeur héritée de
la typographie et sans rapport avec la réalité, qui tourne plutôt autour de 110 à 130 sur un
portable récent. La fenêtre s'ouvre donc à une taille plausible mais fausse tant qu'on ne la
lui a pas dite :

```
./gradlew :app:simulateur -Pguidage.ppp=125
```

Pour trouver la valeur : divisez la largeur de votre écran **en points** (celle que le
système affiche dans ses réglages d'affichage) par sa largeur **en pouces**. Sur un MacBook
Pro 14 pouces en résolution par défaut : 1512 points pour 12,05 pouces, soit 125.

À défaut, la **règle graduée** à gauche de l'image est à la même échelle qu'elle. Posez-y une
vraie règle : si les millimètres coïncident, le tableau de bord est à sa taille physique ;
sinon, `+` et `-` l'ajustent par pas de cinq pour cent, et la ligne du bas affiche la largeur
obtenue.

### La taille du champ

Le champ plein écran n'a pas les 480 × 800 points de l'écran : le Karoo garde une bande de
158 points en haut pour l'heure et la batterie, et un liseré d'un point de chaque côté. La
place réellement laissée, relevée sur un Karoo 3, est de **478 × 642** — soit près d'un
cinquième de la hauteur en moins.

Sur l'appareil la question ne se pose pas : `ViewConfig.viewSize` donne la place allouée et
le rendu s'y ajuste. Le banc d'essai, lui, doit la connaître, et il l'a longtemps ignorée —
il montrait une mise en page plus haute que la vraie : rangs plus espacés, chiffres plus
grands que ce qu'on lira en roulant.

La valeur est figée dans `Simulateur`, et se change sans recompiler pour un autre appareil
ou un autre gabarit de page :

```
./gradlew :app:simulateur -Pguidage.hauteur=744
```

Pour la relever, **sans rien brancher** : poser le champ **« Tableau de bord »** (dans
le sélecteur, « Guidage » est le nom de l'extension, pas celui du champ) sur une page, ouvrir
cette page une fois, puis lancer l'application Guidage depuis le launcher du Karoo. La carte
« Place allouée au champ », en bas de l'écran de configuration, donne les dimensions telles
que le système les a réellement accordées — pour chaque champ posé, et en séparant l'édition
de la sortie, car rien ne garantit qu'elles coïncident.

Avec `adb`, la même chose se lit au journal :

```bash
adb logcat -s GuidageExtension:D | grep "champ ouvert"
```

Dans les deux cas s'affiche aussi le **corps natif** en sp, celui que le Karoo emploie pour un
champ numérique de cette taille : c'est la référence typographique de l'appareil, plus sûre
que celle d'une maquette.

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

**Ce qu'il montre est le code de l'appareil.** Ce n'est pas une seconde écriture de
l'affichage : le simulateur assemble l'état d'une sortie, puis appelle le constructeur de
modèle et le rendu de l'extension — les mêmes classes exactement, dessinant dans le même
`Canvas` d'Android. C'est ce qui le distingue des [planches](docs/planches.md), portées en
JavaScript et qui, elles, peuvent dériver.

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

## Architecture

```
core/                        module JVM pur, testable — aucune dépendance Android
  Polyline.kt                décodage des polylignes Google (précision 5 et 1)
  ElevationProfile.kt        profil altimétrique : interpolation, extraction, D+
  Route.kt                   itinéraire, côtes, POI, état de guidage
  Guidance.kt                côte en cours/à venir, prochain POI, fenêtre de profil
  AlertEngine.kt             décide quelles annonces déclencher, sans répétition
  Pacing.kt                  apprend les deux allures, en déduit l'arrivée et sa marge
  Format.kt                  formatage distances / dénivelés / pentes (métrique & impérial)

app/                         extension Android
  karoo/KarooFlows.kt        ponts callback → Flow de karoo-ext
  karoo/GuidanceProvider.kt  assemble l'état de guidage depuis les événements Karoo
  extension/                 service d'extension, champs de données, alertes
  extension/DashboardModels  construit ce qu'affiche le tableau de bord — partagé avec le simulateur
  ui/                        rendu des champs (Canvas + Glance) et écran de réglages
  settings/                  persistance des réglages
  src/test/…/sim/            le simulateur de bureau, hors de l'APK
```

### D'où viennent les données

| Donnée | Source Karoo |
| --- | --- |
| Itinéraire, profil, côtes, POI | événement `OnNavigationState` |
| Position sur l'itinéraire | `DISTANCE_TO_DESTINATION` (distance totale − distance restante) |
| Pente instantanée | `ELEVATION_GRADE` |
| Allure du coureur | mesurée en roulant sur `SMOOTHED_3S_AVERAGE_SPEED` et `ELEVATION_GRADE` |
| Unités du coureur | événement `UserProfile` |
| État de la sortie | événement `RideState` |

## Limites connues

- Les champs n'affichent quelque chose qu'avec une **navigation active** : itinéraire chargé
  ou navigation vers un point. Sans navigation, ils indiquent « Pas d'itinéraire ».
- Le profil altimétrique et la liste des côtes sont fournis par Karoo OS depuis karoo-ext 1.1.6 ;
  un Karoo à jour est nécessaire.
- L'heure d'arrivée calculée demande trois minutes de roulage, et deux minutes de montée
  quand il reste du dénivelé. Avant cela — et en l'absence de profil altimétrique — le champ
  affiche celle du Karoo, sans marge.
- En navigation **vers un point** (et non sur un itinéraire enregistré), Karoo ne fournit pas la
  longueur du trajet : elle est déduite du profil altimétrique. Sans profil, la position le long
  du trajet ne peut pas être calculée et les champs restent vides.
- L'APK est signé avec la clé de debug : à remplacer par une vraie signature pour une diffusion
  publique.
