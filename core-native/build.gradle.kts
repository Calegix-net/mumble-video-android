plugins {
    alias(libs.plugins.android.library)
}

// The real client core. Kotlin owns the sockets (TLS control, UDP media), the audio devices and the
// state; C/C++ comes in over JNI for Opus now and for mumble-core's video transport later.

android {
    namespace = "com.calegix.mumblevideo.client"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Phones, plus the x86_64 emulator.
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The known-answer vectors serve the JVM tests and the on-device JNI test alike.
    packaging {
        resources {
            // BouncyCastle's jars each carry the same licence and OSGi metadata (the test APK packs them).
            excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    sourceSets {
        named("androidTest") { resources.directories.add("src/test/resources") }
    }
}

dependencies {
    api(project(":core-api"))
    implementation(project(":core-proto"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
