plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.elkelvinx.sashabridge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.elkelvinx.sashabridge"
        minSdk = 29
        targetSdk = 37
        versionCode = 5
        versionName = "2.3"

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

// OrientationLayoutsTest reads the layout files themselves, which Gradle does not count as
// inputs of the unit tests: without this, a change to a layout alone would skip the tests.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/res").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("resources")
}

room {
    // One JSON file per version of the history database: the schema to study, and what the
    // migration tests compare against. Kept in git.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.room.runtime)
    // The phone's own SQLite, through the driver API that Room's migrations and tests share.
    implementation(libs.androidx.sqlite.framework)
    ksp(libs.androidx.room.compiler)
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
    // SQLite for the JVM, to test the history database and its migration without a phone: the
    // JVM build of the driver, which carries the native library for Windows, macOS and Linux.
    // Only the driver itself; the SQLite interfaces it implements come from the app's own.
    testImplementation(libs.androidx.sqlite.bundled.jvm) { isTransitive = false }
}