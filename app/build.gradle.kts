plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Chaque construction CI porte un numéro de version supérieur au précédent : sans cela,
// Android refuse d'installer une mise à jour par-dessus une version de même numéro.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull() ?: 0

android {
    namespace = "io.github.jmallus.guidage"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jmallus.guidage"
        minSdk = 26
        targetSdk = 34
        versionCode = 1 + buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // Clé de signature stable, versionnée avec le projet : la clé de debug est
        // régénérée à chaque exécution du CI, et Android refuse alors la mise à jour
        // par-dessus une version signée différemment.
        create("guidage") {
            storeFile = file("guidage.keystore")
            storePassword = "guidage"
            keyAlias = "guidage"
            keyPassword = "guidage"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("guidage")
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
