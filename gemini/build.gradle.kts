plugins {
    alias(libs.plugins.gcode.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(libs.javax.inject)

    testImplementation(libs.okhttp.mockwebserver)
}
