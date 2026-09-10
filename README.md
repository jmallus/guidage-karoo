# Champs de données et guidage - Hammerhead Karoo

Extension **Karoo 3** qui enrichit le **guidage d'itinéraire** : elle lit
l'itinéraire chargé dans le Karoo et en tire **sept champs de données** — un tableau de bord
plein écran avec minicarte sur fond de carte embarqué, le profil à venir, la prochaine côte,
l'espacement des ravitaillements, le coût du reste en kilojoules — et des annonces à l'écran.

Tout est calculé **sur l'appareil**, à partir des données que Karoo OS fournit déjà :
aucune connexion réseau, aucun compte, rien à synchroniser.

<img src="docs/captures/carte-300m.png" alt="Le tableau de bord plein écran : effort en haut, transmission à gauche et minicarte à droite, fréquence cardiaque et distance restante côte à côte, profil de ce qui arrive sur tout le bas" width="300">

**Toutes les images de ce README sortent du rendu de l'appareil.** Elles ne sont pas des
maquettes : le [simulateur](docs/developpement.md#le-simulateur) appelle les mêmes classes que
le champ Karoo, dans le même `Canvas` d'Android, et le CI les régénère à chaque changement
d'affichage. Elles ne peuvent donc pas dériver du code.

Chaque champ est montré à **478 × 642 px**, la place que le Karoo 3 lui accorde réellement.

## Ce que ça ajoute sur le vélo

### Champs de données

| Champ | Type | Contenu |
| --- | --- | --- |
| **Tableau de bord** | graphique, plein écran | Une page tenant tout l'écran : vitesse, cadence et puissance sur 3 secondes, transmission en schéma, fréquence cardiaque, minicarte orientée cap en haut sur fond de carte hors ligne, distance restante à côté du cœur, et sur tout le bas de l'écran le profil de **ce qui arrive**, à échelle régulière et sur la portée réglée. Le verdict du soir a quitté cette page : il a sa propre case de bilan, où l'heure d'arrivée se lit en grand à côté du coucher. Vitesse, puissance et fréquence cardiaque prennent la couleur de leur zone. Une pression sur le **haut** change l'échelle de la carte, une pression sur le **bas** la portée du profil. |
| **Profil à venir** | graphique | Tout ce qui reste à parcourir, **à échelle comprimée au loin** : la rampe dans trois cents mètres et le col de la fin dans la même bande. Rempli en couleur selon la pente, côtes surlignées avec leur pente moyenne, dénivelé positif restant. |
| **Prochaine côte** | graphique | Avant la côte : distance jusqu'à son pied, longueur, pente moyenne, dénivelé. Dans la côte : distance et dénivelé restants jusqu'au sommet, avec barre de progression. Disponible aussi comme valeur numérique (distance) pour d'autres usages. |
| **Prochain point d'intérêt** | numérique | Distance jusqu'au prochain POI de l'itinéraire (eau, ravitaillement, contrôle…), formatée dans vos unités. |
| **Suivant la sortie** | graphique | Un champ dont la moitié basse change avec ce que fait la sortie — montée, descente, ravitaillement, roulage — la moitié haute restant fixe. |
| **Réserve** | graphique, **pleine page** | Après quel point de ravitaillement il n'y a plus rien. La ligne porte l'itinéraire entier : points passés en gris, prochain en blanc, dernier utile cerclé de jaune, et à sa droite un segment rouge qui ne porte rien. |
| **Autonomie** | graphique, **pleine page** | Les deux réserves qui s'épuisent sur une seule page : la réserve d'eau en haut, le budget d'effort en bas. On ne s'arrête qu'une fois, et c'est en voyant les deux ensemble qu'on décide de s'arrêter à ce point-ci ou de tenir jusqu'au suivant. Demande en outre un capteur de puissance pour sa moitié basse. |

S'y ajoutent **dix cases de bilan**, décrites plus bas : elles tiennent dans une case
ordinaire, là où les précédentes demandent une bande ou une page entière.

Tous s'adaptent à la **taille** que le profil de page leur alloue ; « Prochaine côte » suit en
outre l'**alignement** configuré. Les seize champs graphiques affichent un aperçu réaliste dans
l'écran d'édition des pages — « Prochain point d'intérêt » n'en a pas besoin, c'est le Karoo
qui le dessine.

### Le bilan de la sortie

Dix cases qui disent ce que la sortie **vaut depuis le départ**. Elles tiennent chacune dans
une case ordinaire — une page en porte dix — et c'est là toute leur raison d'être : le Karoo
publie déjà la plupart de ces nombres, mais **un par champ**, si bien qu'une moyenne et son
maximum coûtent deux emplacements et que le coureur fait la soustraction de tête. Ici les deux
voyagent ensemble, et la case dit en plus ce que leur voisinage veut dire.

| Case | Grand chiffre | À côté | Ce que la case ajoute |
| --- | --- | --- | --- |
| **FC moyenne** | fréquence moyenne | maximum de la sortie | aplat de la couleur de la zone où tombe la moyenne |
| **Puissance moyenne** | moyenne | normalisée | le mot que leur écart forme : lisse, roulante ou hachée |
| **Arrivée** | heure estimée | coucher du soleil | aplat vert, jaune ou rouge, et la marge en toutes lettres |
| **Dénivelé** | déjà monté | restant | une barre qui montre la part faite |
| **Intensité** | facteur d'intensité | TSS | le mot qui les nomme, coloré selon le niveau |
| **Zones** | zone dominante | temps qu'on y a passé | la barre empilée des cinq zones, dans leur ordre |
| **Réserve W′** | part restante | kilojoules | une jauge qui se vide, et la puissance critique retenue |
| **Dérive PW/FC** | dérive aérobie | temps d'effort mesuré | le verdict : solide, ça dérive, ou cuit |
| **Arrêts** | temps en selle | temps arrêté | la part perdue aux haltes, en barre |
| **Batterie** | charge | charge projetée à l'arrivée | aplat vert, jaune ou rouge, et la décharge par heure |

Deux d'entre elles ne sont **pas** des nombres du Karoo rangés autrement, mais des calculs de
l'extension, faits localement et sans réseau comme tout le reste :

- **Réserve W′** applique le modèle de puissance critique dans sa forme différentielle
  (Froncioni ; Clarke et Skiba, 2013). La puissance critique vient de la FTP réglée sur
  l'appareil et la réserve du poids — 300 J/kg — et les deux se corrigent dans les réglages.
  À lire comme une jauge relative, non comme un nombre de joules exact : la FTP n'est pas la
  puissance critique, et 300 J/kg est une moyenne de population.
- **Dérive PW/FC** compare le rapport puissance / fréquence cardiaque de la seconde moitié de
  la sortie à celui de la première. S'il faut plus de battements pour les mêmes watts, ça se
  paie. Aucun compteur ne l'affiche en roulant. Elle se calcule sur le temps d'effort seul et
  se tait sous une heure.

Les dix, posées comme sur une page — au quart de la sortie simulée, puis à son terme. Trois
d'entre elles n'existent pas encore au quart : la dérive se tait, les haltes n'ont pas eu
lieu, la charge n'a pas assez descendu pour qu'on lui connaisse une pente.

<table>
  <tr>
    <td align="center"><img src="docs/captures/champ-bilan.png" width="300" alt="Les dix cases de bilan au quart de la sortie"><br><b>Au quart de la sortie</b></td>
    <td align="center"><img src="docs/captures/champ-bilan-fin.png" width="300" alt="Les dix cases de bilan à l'arrivée"><br><b>À l'arrivée</b></td>
  </tr>
</table>

### À quoi ils ressemblent

Les deux pleines pages, au même instant de la sortie simulée :

<table>
  <tr>
    <td align="center"><img src="docs/captures/champ-autonomie.png" width="180" alt="Autonomie"><br><b>Autonomie</b></td>
    <td align="center"><img src="docs/captures/champ-reserve.png" width="180" alt="Réserve"><br><b>Réserve</b></td>
  </tr>
</table>

Les champs de bande, qui se posent sur un rang d'une page ordinaire :

<table>
  <tr>
    <td align="center"><img src="docs/captures/champ-profil.png" width="300" alt="Profil à venir"><br><b>Profil à venir</b></td>
    <td align="center"><img src="docs/captures/champ-contexte.png" width="300" alt="Suivant la sortie"><br><b>Suivant la sortie</b></td>
  </tr>
  <tr>
    <td align="center" colspan="2"><img src="docs/captures/champ-cote.png" width="300" alt="Prochaine côte"><br><b>Prochaine côte</b></td>
  </tr>
</table>

Et le tableau de bord à ses trois portées de carte, puis avec le profil à la place de la
carte, puis hors itinéraire — le chemin de rejointe s'écrit en rouge :

<table>
  <tr>
    <td align="center"><img src="docs/captures/carte-300m.png" width="150" alt="Portée 300 m"><br>300 m</td>
    <td align="center"><img src="docs/captures/carte-500m.png" width="150" alt="Portée 500 m"><br>500 m</td>
    <td align="center"><img src="docs/captures/carte-1000m.png" width="150" alt="Portée 1 km"><br>1 km</td>
    <td align="center"><img src="docs/captures/profil.png" width="150" alt="Profil au lieu de la carte"><br>Profil</td>
    <td align="center"><img src="docs/captures/hors-itineraire.png" width="150" alt="Hors itinéraire"><br>Hors itinéraire</td>
  </tr>
</table>

**Quatre d'entre eux publient aussi une valeur numérique**, réutilisable dans n'importe quel
champ ou enregistrée dans le fichier de la sortie : la distance au pied ou au sommet
(« Prochaine côte »), au prochain point (« Prochain point d'intérêt ») ; la longueur de la
prochaine traversée sans ravitaillement (« Réserve ») ; et les kilojoules restants
(« Autonomie »).

Deux champs sont marqués **pleine page**. Ils fonctionnent posés sur un demi-rang, mais ne
portent pas une valeur : une répartition — l'espacement des ravitaillements, les deux réserves
qui s'épuisent. Réduits à une bande, il ne leur reste que leurs deux chiffres, c'est-à-dire ce
que les champs numériques disent déjà. Leur mise en page change au-delà d'un rapport
hauteur/largeur d'un dixième au-dessus du carré.

### Les deux échelles du profil

Le champ **« Profil à venir »** répond à *qu'est-ce qui reste*. Son échelle horizontale n'est
pas proportionnelle : la distance est projetée par un logarithme translaté, fin sur les deux
cents premiers mètres et de plus en plus comprimé ensuite, si bien que la bande couvre **tout
ce qui reste**, du premier mètre à l'arrivée. Sur cent vingt kilomètres restants, les deux
cents premiers mètres occupent 10,8 % de la largeur et les vingt derniers kilomètres 2,8 % —
le proche pèse quatre fois le lointain. Une portée réglable y serait l'aveu d'un choix
impossible : à cinq kilomètres on voit la rampe qui arrive mais plus la journée, à quinze on
voit la journée mais la rampe tient dans deux pixels.

Le **bandeau du tableau de bord** répond à l'autre question — *qu'est-ce qui arrive* — et
demande donc l'autre échelle. Une sortie l'a montré : sur une journée entière, la compression
écrase les côtes contre le fond de la fenêtre et l'on ne se rend plus compte de ce qui vient.
Il porte donc une échelle **régulière** sur une portée franche, 5, 10, 20 ou 50 km, dix par
défaut, que l'appui sur le bas de l'écran fait défiler.

Les deux exécutent le même modèle et le même rendu. Ils ne divergent pas par négligence :
c'est la question posée qui diffère, et elle ne se pose pas au même moment.

Cette compression ne se voit pas d'elle-même — un œil qui suppose une échelle régulière lit
un faux relief. Ce sont les graduations sous l'axe qui la disent : leur espacement inégal est
le seul aveu que la bande ne soit pas plate, et c'est pourquoi leurs traits subsistent même
sur un champ trop court pour porter les chiffres.

Le lointain est une **crête** et non une courbe : une colonne de pixels y couvre parfois deux
kilomètres, dont on retient le point le plus haut. Un sommet ne peut donc pas disparaître
entre deux colonnes, mais un col suivi d'une descente courte s'y lit comme un plateau. À cette
échelle, c'est ce qu'on veut savoir.

### L'heure d'arrivée

Celle du Karoo extrapole la moyenne de la sortie, ce qui revient à supposer qu'un col se monte
à la vitesse d'un faux plat : sur un parcours qui garde ses côtes pour la fin, l'heure annoncée
recule de minute en minute.

L'extension en mesure deux, séparément — la vitesse sur terrain roulant en km/h, la vitesse
ascensionnelle en montée en mètres par heure — puis les applique au terrain qui reste, tel que
le profil le décrit : le dénivelé d'un côté, la distance hors montées de l'autre, jamais les
deux pour le même mètre. Les deux allures s'oublient doucement, sur un quart d'heure, car
celle de la sixième heure n'est pas celle de la première.

L'heure porte la marge qu'on reconnaît à l'estimation — « arrivée 19:44 ± 13 » — calculée sur
la régularité observée de chacune des deux allures et sur ce qui reste à faire. Elle se
resserre en approchant. Tant que l'allure n'est pas assez observée, le champ affiche l'heure du
Karoo sans marge : mieux vaut la sienne qu'une heure tirée de trente secondes de roulage.

Sur le tableau de bord, elle s'écrit dans la bande du soir, au pied de l'écran, sur
une frise qui la place face au coucher du soleil : c'est à lui qu'on la compare de tête en fin
de journée, et le mot au-dessus — **OUI**, **JUSTE**, **NON** — fait la comparaison à votre
place, sur la fourchette et non sur la seule moyenne. Sans position ni coucher, la bande le
dit ; elle ne cède sa place à rien d'autre, pour que la mise en page ne bouge pas en route.

### Annonces in-ride

Pendant l'enregistrement d'une sortie :

- **Point d'intérêt** — « Fontaine — Dans 500 m »
- **Dernier ravitaillement** — « Lavoir — rien avant 42 km »

Chaque annonce n'est émise qu'une fois par point, et la distance de déclenchement est
réglable.

La seconde est la première retournée. « Prochaine eau dans 500 m » ne dit pas s'il faut s'y
arrêter ; « rien avant 42 km » le dit, et c'est la même donnée. Elle se déclenche sur le
dernier point où l'on peut encore remplir un bidon avant une longue traversée — quinze
kilomètres au moins, sans quoi ce n'en est pas une.

Comptent comme ravitaillement l'eau, les postes de ravitaillement, les épiceries, les
commerces, les stations-service, la restauration, les bars, les cafés et les haltes. Le
contrôle de cyclosportive n'en est pas : il oblige à s'arrêter, mais rien ne dit qu'on y
trouve à boire. Le [réglage](#réglages) « ne compter que les points d'eau » réduit la liste
à l'eau seule, pour qui roule en autonomie complète — et il vaut aussi pour les champs, une
voix qui nommerait un point que l'écran ne montre pas étant pire que pas de voix.

Les côtes n'en déclenchent pas. Une annonce au pied et une avant le sommet couvriraient
l'écran au moment précis où l'on regarde le bandeau de profil pour savoir ce qui reste à
monter. La bande est là en permanence et porte déjà le rang de la côte et la distance au
sommet : l'annonce ne dirait rien de plus, elle le dirait par-dessus.

### Action bonus

L'action **« Annoncer la prochaine côte »** peut être assignée à un bouton de commande
(via les réglages Karoo) pour afficher à la demande le résumé de la côte suivante.

## Réglages

L'application « Guidage » du launcher affiche l'état courant (itinéraire, distance restante,
prochaine côte, prochain point), puis les réglages, rangés par ce qu'ils touchent. Chaque
ligne nomme les champs concernés : un réglage dont on ne sait pas ce qu'il change se laisse
dans son état d'usine, ce qui revient à ne pas l'avoir écrit.

**Tableau de bord**

- minicarte plutôt que profil dans la moitié haute ;
- coloration du profil selon la pente.

**Ravitaillement**

- ne compter que les points d'eau. Décoché — c'est le défaut — commerces, stations-service,
  cafés et haltes comptent aussi. Le choix vaut pour « Réserve », « Autonomie », « Suivant la
  sortie » **et les annonces** : une voix qui nommerait un dernier ravitaillement que l'écran
  ne montre pas serait pire que pas de voix.

**Réserve anaérobie**

- déduire la puissance critique et la réserve W′ de la FTP et du poids réglés sur le Karoo.
  C'est le défaut, et la case « Bilan · réserve W′ » marche donc sans qu'on touche à rien.
  Décoché, deux curseurs prennent le relais, pour qui a mesuré les siens — un test de
  puissance critique en donne de bien meilleurs que la règle du pouce.

**Annonces**

- activation et distance d'annonce des points d'intérêt.

En bas, la carte **« Place allouée au champ »** relève, pour chaque champ posé, les dimensions
que le système lui a réellement accordées — voir
[Développement](docs/developpement.md#la-place-allouée-à-un-champ).

Les changements sont pris en compte immédiatement, sans redémarrer l'extension.

## Installer

C'est le chemin normal, et il ne demande ni ordinateur ni compilation : l'APK est construit
par le CI et publié en Release.

1. Ouvrir **https://github.com/jmallus/guidage-karoo/releases** dans le navigateur du
   **téléphone** — pas du Karoo, qui n'en a pas.
2. Choisir la dernière version `vX.Y.Z`, puis appui long sur le lien `guidage-karoo.apk`.
3. **Partager** le lien vers l'application **Hammerhead Companion**. L'écran d'installation
   s'affiche sur le Karoo.

Après l'installation, les champs apparaissent dans **Profils → une page → ajouter un champ de
données → extension Guidage**.

Le fond de carte voyage **dans** l'APK et se déballe au premier démarrage : rien à copier sur
l'appareil.

Ce chemin ne sert **qu'une fois** : les versions suivantes s'installent depuis le Karoo, par un
appui long sur l'icône de l'extension puis **Mise à jour**.

## Licence et attribution

Le fond de carte embarqué dans l'APK est dérivé de données **OpenStreetMap** :

> © les contributeurs OpenStreetMap — https://www.openstreetmap.org/copyright

Ces données sont sous **ODbL 1.0**. Le fichier `.gkmap` produit par `tools/` en est une base
de données dérivée au sens de cette licence : sa redistribution, y compris à l'intérieur d'un
APK, y reste soumise. Les extraits régionaux viennent de [Geofabrik](https://download.geofabrik.de).

L'attribution est portée en trois endroits, parce qu'aucun ne suffit seul : le fichier
[`NOTICE`](NOTICE), les notes de chaque Release, et le pied de l'écran de réglages de
l'application — le seul que le coureur voie.

Le reste des emprunts — couleurs de zones, contraste APCA, icônes — est détaillé dans le
[`NOTICE`](NOTICE).

## Limites connues

- **Essayée sur un Karoo 3 seulement.** Rien n'y interdit le Karoo 2 — `minSdk 26`, et
  karoo-ext couvre les deux — mais aucun relevé n'en vient : les tailles de champ, la densité
  d'écran et les couleurs ont toutes été mesurées sur un Karoo 3.
- Les champs n'affichent quelque chose qu'avec une **navigation active** : itinéraire chargé
  ou navigation vers un point. Sans navigation, ils indiquent « Pas d'itinéraire ».
- Le profil altimétrique et la liste des côtes sont fournis par Karoo OS depuis karoo-ext 1.1.9 ;
  un Karoo à jour est nécessaire.
- L'heure d'arrivée calculée demande trois minutes de roulage, et deux minutes de montée
  quand il reste du dénivelé. Avant cela — et en l'absence de profil altimétrique — le champ
  affiche celle du Karoo, sans marge.
- En navigation **vers un point** (et non sur un itinéraire enregistré), Karoo ne fournit pas la
  longueur du trajet : elle est déduite du profil altimétrique. Sans profil, la position le long
  du trajet ne peut pas être calculée et les champs restent vides.
- La mise à jour depuis le Karoo suppose une Release publiée (un tag `vX.Y.Z`) : les
  constructions intermédiaires, publiées sous la Release préliminaire `latest`, restent
  invisibles pour l'appareil. C'est voulu.
- La moitié basse d'« Autonomie » demande un **capteur de puissance** et quelques minutes de
  roulage. Sans eux, rien n'est annoncé — ce qui vaut mieux qu'un chiffre inventé. Les rayures
  de chemin de la minicarte demandent de leur côté le fond de carte embarqué.
