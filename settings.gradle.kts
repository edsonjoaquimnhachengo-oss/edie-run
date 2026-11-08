pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Repositório do Guardian Project (Tor para Android), usado como reserva.
        maven("https://raw.githubusercontent.com/guardianproject/gpmaven/master")
    }
}

rootProject.name = "EdieRun"
include(":app")
