plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.elkelvinx.sashabridge"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.elkelvinx.sashabridge"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        // android.util.Log and SystemClock return defaults instead of throwing in JVM tests,
        // so that the server can run there.
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        // Protocol fixtures shared with the Rust client, available to the unit tests
        // as classpath resources under "fixtures/".
        getByName("test") {
            resources.directories.add("../../protocol")
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    testImplementation(libs.junit)
    // The server tests connect to it as the PC does.
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
}