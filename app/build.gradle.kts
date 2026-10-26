plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.vinkje"
    compileSdk = 36
    // Must match buildToolsVersion in flake.nix: the Nix SDK is read-only.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.vinkje"
        minSdk = 29
        targetSdk = 36
        // Release builds get the version from the tag: -PversionName=1.2.3 (see .github/workflows/release.yml).
        versionName = (findProperty("versionName") as String?) ?: "0.0.0-dev"
        versionCode = versionName!!.substringBefore('-').split('.').map { it.toInt() }
            .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
            .coerceAtLeast(1)
    }

    signingConfigs {
        // The release key lives outside the repo; CI writes it from repository secrets.
        val keystore = System.getenv("RELEASE_KEYSTORE")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
        // The release build (minified) with the debug key and the emulator login, to test
        // what R8 did: ./gradlew :app:installStaging
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            isDebuggable = true // for run-as; R8 still runs
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    sourceSets["staging"].kotlin.srcDir("src/debug/kotlin")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":deck-api"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.compose.material.icons)
    implementation(libs.browser)
    implementation(libs.reorderable)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.launcher)
}
