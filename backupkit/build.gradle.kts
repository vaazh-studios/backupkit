import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.bcv)
}

group = "com.vocabloot"
version = "0.1.0"

kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()

    androidLibrary {
        namespace = "com.vocabloot.backupkit"
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget = JvmTarget.JVM_21 }
        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.play.services.auth)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "backupkit", version.toString())
    pom {
        name = "BackupKit"
        description = "Kotlin Multiplatform backup into the user's own cloud: iCloud Drive on iOS, Google Drive app-data on Android. No server, no accounts."
        inceptionYear = "2026"
        url = "https://github.com/vaazh-studios/backupkit"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "vaazh-studios"
                name = "Vaazh Studios"
                url = "https://github.com/vaazh-studios"
            }
        }
        scm {
            url = "https://github.com/vaazh-studios/backupkit"
            connection = "scm:git:git://github.com/vaazh-studios/backupkit.git"
            developerConnection = "scm:git:ssh://git@github.com/vaazh-studios/backupkit.git"
        }
    }
}

@OptIn(kotlinx.validation.ExperimentalBCVApi::class)
apiValidation {
    klib { enabled = true }
}
