# Guidage — extension Hammerhead Karoo

Extension Karoo (Karoo 2 / Karoo 3) qui enrichit le **guidage d'itinéraire** : elle lit
l'itinéraire chargé dans le Karoo et en tire un profil altimétrique à venir, des informations
sur la prochaine côte, la distance au prochain point d'intérêt, et des annonces à l'écran.

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
| **Tableau de bord** | graphique, plein écran | Une page tenant tout l'écran : vitesse, cadence et puissance sur 3 secondes, transmission en schéma, fréquence cardiaque, minicarte orientée cap en haut sur fond de carte hors ligne, distance parcourue, pente, distance restante, heure d'arrivée, et le profil de la côte en cours. Vitesse, puissance et fréquence cardiaque prennent la couleur de leur zone. Une pression change l'échelle de la carte. |
| **Profil à venir** | graphique | Le profil altimétrique de la portion devant vous (portée réglable de 1 à 15 km), rempli en couleur selon la pente, côtes de l'itinéraire surlignées avec leur pente moyenne, dénivelé positif restant sur la fenêtre. |
| **Prochaine côte** | graphique | Avant la côte : distance jusqu'à son pied, longueur, pente moyenne, dénivelé. Dans la côte : distance et dénivelé restants jusqu'au sommet, avec barre de progression. Disponible aussi comme valeur numérique (distance) pour d'autres usages. |
| **Prochain point d'intérêt** | numérique | Distance jusqu'au prochain POI de l'itinéraire (eau, ravitaillement, contrôle…), formatée dans vos unités. |

« Profil à venir » et « Prochaine côte » s'adaptent à la taille et à l'alignement configurés
dans le profil de page. Tous affichent un aperçu réaliste dans l'écran d'édition des pages.

### Annonces in-ride

Pendant l'enregistrement d'une sortie :

- **Côte** — « Côte dans 300 m — 1,8 km à 6,5 % • +120 m »
- **Sommet** — « Sommet dans 200 m — Encore +35 m »
- **Point d'intérêt** — « Fontaine — Dans 500 m »

Chaque annonce n'est émise qu'une fois par côte / par point, et les distances de
déclenchement sont réglables. Les côtes plus courtes que 200 m sont ignorées.

### Action bonus

L'action **« Annoncer la prochaine côte »** peut être assignée à un bouton de commande
(via les réglages Karoo) pour afficher à la demande le résumé de la côte suivante.

## Réglages

L'application « Guidage » du launcher affiche l'état courant (itinéraire, distance restante,
prochaine côte, prochain point) et permet de régler :

- la portée du profil affiché (1 à 15 km) ;
- la coloration du profil selon la pente ;
- l'activation et la distance d'annonce des côtes, des sommets et des points d'intérêt.

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

## Architecture

```
core/                        module JVM pur, testable — aucune dépendance Android
  Polyline.kt                décodage des polylignes Google (précision 5 et 1)
  ElevationProfile.kt        profil altimétrique : interpolation, extraction, D+
  Route.kt                   itinéraire, côtes, POI, état de guidage
  Guidance.kt                côte en cours/à venir, prochain POI, fenêtre de profil
  AlertEngine.kt             décide quelles annonces déclencher, sans répétition
  Format.kt                  formatage distances / dénivelés / pentes (métrique & impérial)

app/                         extension Android
  karoo/KarooFlows.kt        ponts callback → Flow de karoo-ext
  karoo/GuidanceProvider.kt  assemble l'état de guidage depuis les événements Karoo
  extension/                 service d'extension, champs de données, alertes
  ui/                        rendu des champs (Canvas + Glance) et écran de réglages
  settings/                  persistance des réglages
```

### D'où viennent les données

| Donnée | Source Karoo |
| --- | --- |
| Itinéraire, profil, côtes, POI | événement `OnNavigationState` |
| Position sur l'itinéraire | `DISTANCE_TO_DESTINATION` (distance totale − distance restante) |
| Pente instantanée | `ELEVATION_GRADE` |
| Unités du coureur | événement `UserProfile` |
| État de la sortie | événement `RideState` |

## Limites connues

- Les champs n'affichent quelque chose qu'avec une **navigation active** : itinéraire chargé
  ou navigation vers un point. Sans navigation, ils indiquent « Pas d'itinéraire ».
- Le profil altimétrique et la liste des côtes sont fournis par Karoo OS depuis karoo-ext 1.1.6 ;
  un Karoo à jour est nécessaire.
- En navigation **vers un point** (et non sur un itinéraire enregistré), Karoo ne fournit pas la
  longueur du trajet : elle est déduite du profil altimétrique. Sans profil, la position le long
  du trajet ne peut pas être calculée et les champs restent vides.
- L'APK est signé avec la clé de debug : à remplacer par une vraie signature pour une diffusion
  publique.
