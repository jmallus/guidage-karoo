# Consignes pour Claude

Ce fichier est lu au démarrage de chaque session. Il porte ce qu'une session nouvelle ne
peut pas deviner et que la précédente a payé pour apprendre.

## La langue

Le projet est **en français** — code, commentaires, messages de commit, libellés, README.
Les commentaires disent *pourquoi*, pas *quoi* : le code dit déjà quoi.

## Branche et pull request

Le propriétaire du dépôt donne ici une permission permanente, qui vaut instruction explicite
au sens de la règle « ne jamais pousser ailleurs sans permission » :

- **Pousser directement sur `main`** ce qui touche `README.md`, `docs/`, `NOTICE`, `core/`,
  `tools/` et les workflows. Ces changements sont vérifiables avant de partir (voir plus bas),
  et une pull request pour une correction de documentation n'apporte rien.
- **Passer par une branche et une pull request** pour tout ce qui touche `app/`.

La raison est concrète, pas cérémonielle : **`:app` ne se compile pas dans le conteneur de
session** — ni Gradle, ni le SDK Android, ni karoo-ext accessible. Un changement dans `app/`
part donc sans avoir jamais été compilé, et le CI est le seul juge. Une PR donne deux choses
qu'une poussée directe ne donne pas : `main` reste vert si le CI tombe, et la session peut
**s'abonner aux événements de la PR** (`subscribe_pr_activity`), ce qui fait remonter les
résultats de CI dans la conversation au lieu d'obliger à les sonder. Il n'existe pas
d'équivalent pour une branche sans PR.

Après fusion, supprimer la branche.

## Ce qui se vérifie ici, et ce qui ne s'y vérifie pas

`:core` et `:tools` se compilent et se testent dans le conteneur, sans Gradle, avec le
compilateur Kotlin du cache :

```bash
KC=$(find /root/.gradle/caches -name 'kotlin-compiler-embeddable-2.0.0.jar' | head -1)
SL=$(find /root/.gradle/caches -name 'kotlin-stdlib-2.0.0.jar' | head -1)
AN=$(find /root/.gradle/caches -name 'annotations-13.0.jar' | head -1)
TR=$(find /root/.gradle/wrapper -name 'trove4j-*.jar' | head -1)
java -cp "$KC:$SL:$AN:$TR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -cp "$SL:$AN" -jvm-target 17 -d /tmp/out $(find core/src/main/kotlin -name '*.kt')
```

Les tests se lancent ensuite avec `org.junit.runner.JUnitCore` et les jars `junit-4.13.2` et
`hamcrest-core-1.3` du même cache. **Faites-le avant de pousser quoi que ce soit dans `core/`.**

`:app` ne se compile pas ici. Ce qui le concerne est donc à relire d'autant plus soigneusement
avant d'être poussé : imports manquants, `when` non exhaustifs et signatures changées ne se
verront qu'au CI.

## Les images du README

Elles sortent du **simulateur**, jamais d'un dessin fait à part. Le workflow `captures` les
régénère à chaque poussée sur `main` touchant l'affichage, et les commite dans
`docs/captures/`. Ne jamais réintroduire une seconde écriture de l'affichage : les anciennes
planches en JavaScript ont dérivé du code et montraient un champ qui n'existait plus.

Les champs arrivent **transparents** — c'est le Karoo qui pose le fond. Les captures sont
aplaties sur `#202224` au moment d'écrire le fichier, jamais avant : les contrôles doivent
continuer de travailler sur l'image telle que le champ la produit.

## Ce qui ne doit jamais entrer dans Git

- **Le jeton GitHub** (`read:packages`, pour karoo-ext sur GitHub Packages). Il vit dans
  `local.properties` ou `~/.gradle/gradle.properties`, **jamais** dans le `gradle.properties`
  du projet, qui est versionné. Un jeton exposé se **révoque**, il ne se supprime pas.
- **Les données cartographiques** — `.pbf`, `.osm`, `.gkmap`. Elles se comptent en centaines
  de méga-octets et se fabriquent par le workflow `fond-de-carte`.
- **La clé de signature.** Elle a été versionnée, avec son mot de passe, tant que le dépôt
  était privé. Il ne l'est plus : la clé des Releases vit dans le secret
  `GUIDAGE_KEYSTORE_B64`, et `*.keystore` est ignoré. Ne jamais l'y réintroduire — le Karoo se
  met à jour tout seul depuis les Releases, si bien qu'une clé publiée donnerait à n'importe
  qui le moyen de pousser un APK que l'appareil accepterait comme le nôtre.

## Attribution

Le fond de carte dérive d'OpenStreetMap, sous ODbL. L'attribution est portée au `NOTICE`, aux
notes de chaque Release, au pied de l'écran de réglages et au README. Ne pas la retirer :
elle est exigée par la licence, et chaque APK redistribue les données.

## Publier

Un tag `vX.Y` déclenche la construction et crée la Release versionnée. **Les poussées de tags
échouent depuis le conteneur de session** (403 du proxy git), de même que le déclenchement
manuel d'un workflow (l'application GitHub n'a pas *Actions : write*). Ces deux gestes sont à
demander au propriétaire, en lui donnant les commandes exactes.

Si la carte doit changer, lancer `fond-de-carte` **d'abord** et attendre sa fin : le job `apk`
télécharge la carte au début de son exécution.

## L'appareil

Karoo 3 uniquement — c'est le seul essayé. Le champ plein écran dispose de **478 × 642 px**,
relevé sur l'appareil, et non des 480 × 800 de l'écran.
