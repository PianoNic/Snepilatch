// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // kotlin.android removed: AGP 9.0 ships built-in Kotlin
    // (https://developer.android.com/build/migrate-to-built-in-kotlin)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt)
}

detekt {
    toolVersion = libs.versions.detekt.get()
    config.setFrom(files("$rootDir/detekt.yml"))
    baseline = file("$rootDir/detekt-baseline.xml")
    buildUponDefaultConfig = true
    autoCorrect = false
    source.setFrom(files("app/src/main/java", "app/src/test/java"))
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}")
}