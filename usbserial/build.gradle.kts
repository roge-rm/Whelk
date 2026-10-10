// usb-serial-for-android v3.10.0 by Mike Wakerly and others (MIT, see LICENSE):
// drivers for USB serial adapters. Copied in as it's only published on JitPack.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.hoho.android.usbserial"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 27
        consumerProguardFiles("proguard-rules.pro")
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}
