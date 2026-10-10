import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from ../Keys/whelk-keystore.properties, beside the
// project rather than in it. Without that file the release build is unsigned.
val signingProperties: Properties? = rootProject.file("../Keys/whelk-keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
val releaseStoreFile = signingProperties?.getProperty("storeFile")

android {
    namespace = "com.rm.whelk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rm.whelk"
        minSdk = 27
        targetSdk = 37
        versionCode = 100
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingProperties?.getProperty("storePassword")
                keyAlias = signingProperties?.getProperty("keyAlias")
                keyPassword = signingProperties?.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            optimization {
                enable = true
                keepRules {
                    files.add(file("proguard-rules.pro"))
                }
            }
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += listOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":terminal"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    // Biometric brings an old Fragment that can't take the request codes activity results use.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.documentfile)
    implementation(libs.jb.compose.runtime)
    implementation(libs.jb.compose.foundation)
    implementation(libs.jb.compose.ui)
    implementation(libs.jb.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
}
