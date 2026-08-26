import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// La fenêtre du simulateur de bureau, et elle seule.
//
// Elle vit dans son propre module parce qu'elle ne peut pas vivre ailleurs : le rendu du
// tableau de bord s'exécute dans un test unitaire d'Android, compilé contre « android.jar »,
// qui fournit lui-même les classes « java.* » — et qui n'a jamais contenu « java.awt ». Une
// fenêtre écrite du côté du rendu ne compile donc pas, quelle qu'en soit la forme.
//
// Ici, au contraire, le JDK entier est disponible. Le test s'y adosse sans jamais nommer un
// type AWT : il envoie un tableau de pixels et reçoit des touches. C'est tout ce que les deux
// mondes ont à se dire.
//
// Ce module ne part pas dans l'APK : « :app » ne l'utilise qu'en testImplementation.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
