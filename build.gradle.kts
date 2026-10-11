plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(
            rootProject.extensions
                .getByType<VersionCatalogsExtension>()
                .named("libs")
                .findVersion("ktlint")
                .get()
                .requiredVersion,
        )
        android.set(true)
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }

    dependencies {
        add("ktlintRuleset", rootProject.extensions
            .getByType<VersionCatalogsExtension>()
            .named("libs")
            .findLibrary("compose-rules-ktlint")
            .get())
    }
}
