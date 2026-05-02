pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9"
}

stonecutter {
    create(rootProject) {
        versions("1.21.10", "1.21.11")
        vcsVersion = "1.21.10"
    }
}
