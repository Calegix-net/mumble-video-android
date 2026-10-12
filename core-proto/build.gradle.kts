plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

// The wire messages, generated as protobuf-lite from the protos mumble-core ships (see
// tools/sync-protos.sh). A plain JVM module, so the protobuf plugin never meets AGP.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.protobuf.javalite)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") { option("lite") }
            }
        }
    }
}
