import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Outillage hors appareil : conversion d'un extrait OpenStreetMap vers le format de fond
// de carte lu par l'extension. Ne part jamais dans l'APK — il tourne dans le CI, sur une
// machine qui a du réseau, de la mémoire et du disque, ce que le Karoo n'a pas.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Convertit un ou plusieurs flux GeoJSON en un fichier de fond de carte.
//   ./gradlew :tools:convertRoadMap --args="sortie.gkmap entree1.geojsonseq entree2.geojsonseq"
tasks.register<JavaExec>("convertRoadMap") {
    group = "guidage"
    description = "Convertit des voies OpenStreetMap en fond de carte"
    mainClass.set("io.github.jmallus.guidage.tools.ConvertRoadMapKt")
    classpath = sourceSets["main"].runtimeClasspath
    // Sans cela, Gradle lance le processus depuis tools/ et les chemins relatifs passés
    // en argument se résolvent une marche trop bas.
    workingDir = rootProject.projectDir
    // Une région entière tient en mémoire le temps de l'indexation.
    maxHeapSize = "6g"
}
