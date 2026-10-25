plugins {
    alias(libs.plugins.clarity.kotlin.multiplatform)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
    }
}
