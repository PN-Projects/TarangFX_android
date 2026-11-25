// Fallback: Google's official, byte-identical mirror of Maven Central (Google Cloud Storage).
// Gradle only falls through to it when Maven Central reports an artifact as missing, which some
// CI runners intermittently saw for the KSP plugin. Same publishers, same artifacts, independent
// CDN; operated by Google, which already serves google().
val mavenCentralGoogleMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
    }
}
dependencyResolutionManagement {
    // Only first-party / well-known repositories; no dynamic or untrusted repos.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(mavenCentralGoogleMirror) { name = "MavenCentralGoogleMirror" }
    }
}
rootProject.name = "TarangFX"
include(":app")
