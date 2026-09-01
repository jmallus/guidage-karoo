plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Chaque construction CI porte un numéro de version supérieur au précédent : sans cela,
// Android refuse d'installer une mise à jour par-dessus une version de même numéro.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull() ?: 0

/**
 * Le nom de version suit le tag quand la construction en vient d'un, le numéro de
 * construction sinon.
 *
 * Il valait auparavant toujours « 1.0.<numéro de construction> ». La Release « v1.0.1 »
 * livrait donc un APK qui s'annonce « 1.0.293 » dans les réglages du Karoo — le seul
 * numéro que le coureur puisse lire, et il ne désignait aucune Release.
 *
 * Le `versionCode`, lui, continue de suivre le numéro de construction : c'est celui-là
 * qu'Android compare pour accepter une mise à jour, et il doit croître y compris entre deux
 * constructions non taguées, que le nom de version ne distingue pas.
 *
 * Le motif exige un chiffre après le « v » : `latest` et `carte` sont eux aussi des tags de
 * ce dépôt — posés par la Release de dernière construction et par le fond de carte — et un
 * APK nommé d'après eux ne voudrait rien dire.
 */
val nomDeVersion = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" }
    ?.let { Regex("""v(\d[\w.\-+]*)""").matchEntire(it) }
    ?.groupValues?.get(1)
    ?: "1.0.$buildNumber"

/**
 * La clé de signature des Releases, ou `null` si elle n'est pas fournie.
 *
 * Elle était versionnée dans le dépôt, avec son mot de passe : tenable tant qu'il restait
 * privé, plus du tout maintenant qu'il est public. Une clé lisible par tous laisserait
 * n'importe qui signer un APK qu'Android installerait par-dessus celui-ci sans broncher — et
 * le Karoo va désormais chercher ses mises à jour tout seul, ce qui donne à ce défaut un
 * chemin tout tracé.
 *
 * Elle arrive donc par l'environnement, décodée d'un secret par le CI, hors de l'arbre de
 * travail. À défaut, la construction retombe sur la clé de debug : suffisant pour installer
 * sur son propre appareil, et sans conséquence sur les Releases, que le CI ne publie pas
 * quand le secret manque.
 *
 * Le fichier est obtenu par `project.file(...)`, et surtout pas en écrivant `java.io.File(...)` :
 * dans un script Gradle Kotlin, `java` en position d'**expression** désigne l'extension du
 * plugin Java, pas le paquet — la construction échoue alors sur « Unresolved reference: io »,
 * et pas seulement pour `:app`, puisqu'une erreur de configuration fait tomber la construction
 * entière, `:core:test` compris. En position de *type*, il n'y a pas de collision : l'annotation
 * ci-dessous est donc correcte telle quelle.
 */
val cleDeSignature: java.io.File? = System.getenv("GUIDAGE_KEYSTORE")
    ?.takeIf { it.isNotBlank() }
    ?.let { project.file(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "io.github.jmallus.guidage"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jmallus.guidage"
        minSdk = 26
        targetSdk = 34
        versionCode = 1 + buildNumber
        versionName = nomDeVersion
    }

    signingConfigs {
        val cle = cleDeSignature
        if (cle != null) {
            // Un secret GitHub qui n'existe pas n'est pas une variable *absente* : elle est
            // bien là, valant chaîne vide. Un « ?: » ne teste que null et la laisse donc
            // passer — l'alias valait "", et la signature échouait sur « No key with alias
            // '' found in keystore » alors que le magasin, lui, s'ouvrait très bien.
            //
            // Le mot de passe de clé retombe sur celui du magasin : en PKCS12 la clé n'en a
            // pas de distinct, si bien que GUIDAGE_KEY_PASSWORD n'a rien à apprendre de plus
            // et peut rester non défini.
            val motDePasse = System.getenv("GUIDAGE_KEYSTORE_PASSWORD")
            create("guidage") {
                storeFile = cle
                storePassword = motDePasse
                keyAlias = System.getenv("GUIDAGE_KEY_ALIAS")
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: "guidage"
                keyPassword = System.getenv("GUIDAGE_KEY_PASSWORD")
                    ?.takeIf { it.isNotEmpty() } ?: motDePasse
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(
                if (cleDeSignature != null) "guidage" else "debug",
            )
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // Le fond de carte entre dans l'APK tel quel, sans compression. Il est déjà dense —
        // des écarts en varint ne se compriment plus guère — et laisser aapt le déflater
        // n'économise presque rien tout en obligeant l'appareil à le décompresser
        // entièrement au premier démarrage, quarante méga-octets durant.
        noCompress += "gkmap"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Robolectric lit les ressources fusionnées de l'application : sans elles, les
            // libellés du tableau de bord et les icônes des cases manqueraient à l'appel.
            isIncludeAndroidResources = true
        }
    }
}

/**
 * Le simulateur de bureau.
 *
 * Il est logé dans un test JUnit, et ce n'est pas un détournement mais le seul chemin : le
 * tableau de bord se dessine dans un `Canvas` d'Android, qui n'existe pas sur une machine de
 * bureau. Robolectric le fournit — le vrai, celui de Skia — mais seulement sous un lanceur de
 * tests. La fenêtre ne s'ouvre donc que si on la demande, et le CI, qui exécute les mêmes
 * tests, ne la voit jamais.
 */
val simulateurDemande = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') == "simulateur"
}

tasks.withType<Test>().configureEach {
    if (simulateurDemande) {
        systemProperty("guidage.simulateur", "1")
        // La densité de l'écran hôte, que Java ne sait pas mesurer : sur macOS il déclare
        // soixante-douze points au pouce quel que soit l'écran. Sans elle, la fenêtre s'ouvre
        // à une taille plausible mais fausse ; avec, elle s'ouvre à celle de l'appareil.
        //     ./gradlew :app:simulateur -Pguidage.ppp=125
        (project.findProperty("guidage.ppp") as String?)?.let { systemProperty("guidage.ppp", it) }
        // La hauteur laissée au champ, bandeau d'état du Karoo déduit. Sur l'appareil elle
        // vient de ViewConfig ; ici il faut la dire, le temps de l'avoir relevée.
        //     ./gradlew :app:simulateur -Pguidage.hauteur=744
        (project.findProperty("guidage.hauteur") as String?)?.let {
            systemProperty("guidage.hauteur", it)
        }
        // Le lanceur de tests tourne « sans écran », et le premier JFrame lève alors une
        // HeadlessException. Poser la propriété d'ici ne suffit pas : le plugin Android
        // repose la sienne, et l'ordre des deux ne se maîtrise pas. Le simulateur s'en
        // charge donc lui-même, au début de sa boucle — mais il lui faut pour cela le droit
        // d'aller effacer la réponse que java.awt a déjà retenue, d'où cette ouverture.
        jvmArgs("--add-opens=java.desktop/java.awt=ALL-UNNAMED")
        filter { includeTestsMatching("io.github.jmallus.guidage.sim.SimulateurTest") }
        // Une fenêtre qu'on rouvre est une fenêtre qui doit se rouvrir, même si rien n'a
        // changé depuis la dernière fois.
        outputs.upToDateWhen { false }
        testLogging { showStandardStreams = true }
    }
}

tasks.register("simulateur") {
    group = "guidage"
    description = "Joue une sortie simulée dans une fenêtre, avec le rendu de l'appareil."
    dependsOn("testDebugUnitTest")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.karoo.ext)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Interface de configuration (hors sortie)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Champs de données in-ride (RemoteViews)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.preview)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // La fenêtre du simulateur, hors de l'APK : « android.jar » n'a pas de java.awt.
    testImplementation(project(":sim"))
}
