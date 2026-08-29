// Rend les planches de docs/planches.html en images, pour que GitHub puisse les afficher :
// son interface web montre le source d'un fichier HTML, pas la page.
//
// Usage : npm i -D playwright && node docs/rendre-planches.mjs
//
// À relancer après toute retouche de la planche, et à commiter avec elle — sans quoi les
// images montrent une version que le fichier n'a plus.

import { chromium } from "playwright";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));

// Chaque planche est prise par son sélecteur. Le facteur d'échelle est doublé : GitHub
// affiche les images à la moitié de leur largeur sur les écrans denses.
const PLANCHES = [
  { nom: "tableau-de-bord", selecteur: "#cDouble" },
  { nom: "planche-i-taille-reelle", selecteur: ".scale-stage" },
  { nom: "planche-ii-zones", selecteur: "#annotStage" },
  { nom: "planche-iii-portees", selecteur: "#ranges" },
  { nom: "planche-iv-bandeau-de-cote", selecteur: "#plancheCote" },
  { nom: "planche-v-legende", selecteur: "#plancheLegende" },
];

const navigateur = await chromium.launch();
const page = await navigateur.newPage({
  viewport: { width: 1080, height: 1200 },
  deviceScaleFactor: 2,
  colorScheme: "light",
});

const erreurs = [];
page.on("pageerror", (e) => erreurs.push(e.message));

await page.goto("file://" + join(here, "planches.html"), { waitUntil: "networkidle" });
// Les fontes système peuvent arriver après le premier rendu ; la page se redessine alors.
await page.waitForTimeout(1000);

if (erreurs.length) {
  console.error("La planche a levé des erreurs :", erreurs);
  process.exit(1);
}

for (const { nom, selecteur } of PLANCHES) {
  const chemin = join(here, "planches", nom + ".png");
  await page.locator(selecteur).screenshot({ path: chemin });
  console.log(nom + ".png");
}

await navigateur.close();
