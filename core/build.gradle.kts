// Connections, identities and the rest of the data, the sync file, SSH, Telnet
// and keys. Plain Kotlin with no Android code, tested on the JVM:
// heavy build ./gradlew :core:test
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Android 8.1 runs Java 11 bytecode, and the code only uses what's in Android's library.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    api(libs.sshj)
    api(libs.bcprov)
    api(libs.bcpkix)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.sshd.core)
}
