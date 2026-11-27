plugins {
    alias(libs.plugins.gcode.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":gemini"))
    implementation(project(":core:common"))
    implementation(libs.javax.inject)
}
