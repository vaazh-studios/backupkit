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
include(":backupkit-test")
include(":sample:shared")
include(":sample:androidApp")
