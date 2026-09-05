rootProject.name = "backupkit"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":backupkit")
include(":sample:shared")
include(":sample:androidApp")
