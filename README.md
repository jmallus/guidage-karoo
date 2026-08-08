# Guidage — extension Hammerhead Karoo

Extension Karoo (Karoo 2 / Karoo 3) qui enrichit le **guidage d'itinéraire** : elle lit
l'itinéraire chargé dans le Karoo et en tire un profil altimétrique à venir, des informations
sur la prochaine côte, la distance au prochain point d'intérêt, et des annonces à l'écran.

Tout est calculé **sur l'appareil**, à partir des données que Karoo OS fournit déjà :
aucune connexion réseau, aucun compte, rien à synchroniser.

## Ce que ça ajoute sur le vélo

### Champs de données

| Champ | Type | Contenu |
| --- | --- | --- |
| **Profil à venir** | graphique | Le profil altimétrique de la portion devant vous (portée réglable de 1 à 15 km), rempli en couleur selon la pente, côtes de l'itinéraire surlignées avec leur pente moyenne, dénivelé positif restant sur la fenêtre. |
| **Prochaine côte** | graphique | Avant la côte : distance jusqu'à son pied, longueur, pente moyenne, dénivelé. Dans la côte : distance et dénivelé restants jusqu'au sommet, avec barre de progression. Disponible aussi comme valeur numérique (distance) pour d'autres usages. |
| **Prochain point d'intérêt** | numérique | Distance jusqu'au prochain POI de l'itinéraire (eau, ravitaillement, contrôle…), formatée dans vos unités. |

Les deux champs graphiques s'adaptent à la taille et à l'alignement configurés dans le profil
de page, et affichent un aperçu réaliste dans l'écran d'édition des pages.

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
- Un jeton GitHub avec la portée `read:packages` : la bibliothèque `karoo-ext` est publiée sur
  GitHub Packages, qui exige une authentification même pour les paquets publics.

Renseignez le jeton dans `local.properties` (non versionné) :

```properties
gpr.user=votre-login-github
gpr.key=ghp_xxxxxxxxxxxxxxxxxxxx
```

ou via les variables d'environnement `GITHUB_ACTOR` / `GITHUB_TOKEN`.

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
