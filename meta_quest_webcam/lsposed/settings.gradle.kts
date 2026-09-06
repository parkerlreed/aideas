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
        // Classic Xposed API (compileOnly stubs). Only host that serves api:82.
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "QuestUsbCamUnlock"
include(":app")
