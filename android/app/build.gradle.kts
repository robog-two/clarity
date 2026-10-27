// AGP 9 has built-in Kotlin support: no org.jetbrains.kotlin.android plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.screenshot)
}

android {
    namespace = "net.robog.clarity"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "net.robog.clarity"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // CI passes -Pclarity.versionCode=<run number> -Pclarity.versionName=0.1.<run number>.
        versionCode = providers.gradleProperty("clarity.versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("clarity.versionName").getOrElse("0.1.0-dev")
    }

    // Release signing comes only from the environment (GitHub Actions secrets). The keystore
    // is never committed or kept on developer machines; without these vars, release builds
    // are simply unsigned.
    val releaseSigning = providers.environmentVariable("CLARITY_SIGNING_STORE_FILE").orNull?.let { storePath ->
        signingConfigs.create("release") {
            storeFile = file(storePath)
            storeType = "PKCS12"
            storePassword = providers.environmentVariable("CLARITY_SIGNING_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("CLARITY_SIGNING_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("CLARITY_SIGNING_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // TODO: enable R8 with keep rules once the app has real features
            signingConfig = releaseSigning
        }
    }

    // Screenshot previews (app/src/screenshotTest); also needs the flag in gradle.properties.
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    implementation("net.robog.clarity:shared") // substituted by the root build's :shared (jvm variant)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment) // FragmentActivity hosts the BiometricPrompt
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    implementation(libs.metrox.viewmodel.compose)

    implementation(libs.tink.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)

    screenshotTestImplementation(libs.compose.screenshot.validation.api)
    screenshotTestImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.okhttp.mockwebserver)
}
