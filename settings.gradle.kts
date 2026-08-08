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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext est publié sur GitHub Packages, qui exige toujours une authentification
        // (même pour un paquet public). Renseigner gpr.user / gpr.key dans local.properties
        // ou ~/.gradle/gradle.properties, ou les variables d'env GITHUB_ACTOR / GITHUB_TOKEN.
        maven {
            name = "karoo-ext"
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                    ?: ""
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
                    ?: ""
            }
        }
    }
}

rootProject.name = "guidage-karoo"
include(":app", ":core")
