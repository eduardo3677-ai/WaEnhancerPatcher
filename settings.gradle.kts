pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://raw.githubusercontent.com/HighCapable/maven-repository/main/repository/releases")
        maven("https://raw.githubusercontent.com/HighCapable/maven-repository/main/repository/snapshots")
    }
}

plugins {
    id("com.highcapable.gropify") version "1.0.2" apply false
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
        maven("https://jitpack.io")
        maven("https://raw.githubusercontent.com/HighCapable/maven-repository/main/repository/releases")
        maven("https://raw.githubusercontent.com/HighCapable/maven-repository/main/repository/snapshots")
    }
}

rootProject.name = "WaEnhancer Patcher"
include(":app")
