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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(rootDir.resolve("tools/maven-repository")) } // für unser lokales libtorrent4j-BBR-Jar (2.1.0-39-beam-bbr1)
        google()
        mavenCentral()
    }
}

rootProject.name = "Beam"
include(":app")
include(":beam-core")
include(":beam-desktop")
include(":beam-relay")
