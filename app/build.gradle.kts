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

// mosh-client for each ABI, built from source by tools/mosh/build.sh into
// build/native/jniLibs at the top of the project, and kept there between builds.
val moshAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val moshOut: Provider<Directory> = rootProject.layout.buildDirectory.dir("native/jniLibs")

android {
    namespace = "com.rm.whelk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rm.whelk"
        minSdk = 27
        targetSdk = 37
        versionCode = 603
        versionName = "0.6.3"
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
    ndkVersion = "28.2.13676358"
    sourceSets {
        getByName("main") {
            jniLibs.directories.add(moshOut.get().asFile.absolutePath)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // mosh-client runs as a program, so it has to be unpacked to the native library folder.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":terminal"))
    implementation(project(":usbserial"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    // Biometric brings an old Fragment that can't take the request codes activity results use.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.documentfile)
    implementation(libs.yubikit.android)
    implementation(libs.yubikit.fido)
    implementation(libs.jb.compose.runtime)
    implementation(libs.jb.compose.foundation)
    implementation(libs.jb.compose.ui)
    implementation(libs.jb.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
}

val ndkPath: Provider<String> = androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath }
val moshTasks = moshAbis.map { abi ->
    tasks.register<Exec>("buildMosh-$abi") {
        group = "build"
        description = "Builds mosh-client for $abi"
        val script = rootProject.file("tools/mosh/build.sh")
        val out = moshOut
        val ndk = ndkPath
        inputs.file(script)
        outputs.file(out.map { it.file("$abi/libmosh-client.so") })
        executable(script.absolutePath)
        environment("MOSH_JOBS", "10")
        argumentProviders.add(CommandLineArgumentProvider { listOf(abi, ndk.get(), out.get().asFile.absolutePath) })
    }
}
tasks.named("preBuild") { dependsOn(moshTasks) }
