pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Identifiants GitHub Packages, cherchés dans cet ordre :
//   1. local.properties à la racine — non versionné (.gitignore)
//   2. propriétés Gradle : ~/.gradle/gradle.properties (recommandé, hors du dépôt)
//   3. variables d'environnement GITHUB_ACTOR / GITHUB_TOKEN (utilisées par le CI)
//
// Ne JAMAIS les mettre dans le gradle.properties du projet : ce fichier est versionné,
// le jeton partirait dans un commit.
val localProperties = java.util.Properties().apply {
    val file = File(rootDir, "local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun credential(localKey: String, environmentKey: String): String =
    localProperties.getProperty(localKey)
        ?: providers.gradleProperty(localKey).orNull
        ?: System.getenv(environmentKey)
        ?: ""

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext est publié sur GitHub Packages, qui exige toujours une authentification,
        // même pour un paquet public.
        maven {
            name = "karoo-ext"
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = credential("gpr.user", "GITHUB_ACTOR")
                password = credential("gpr.key", "GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "guidage-karoo"
include(":app", ":core", ":tools")
