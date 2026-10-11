plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.calegix.mumblevideo.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Deliberately tiny: the only Android type this module may touch is android.view.Surface.
// Everything else is plain Kotlin so the C++ side has a small, stable surface to match.
dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
