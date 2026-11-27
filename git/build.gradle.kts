plugins {
    alias(libs.plugins.gcode.jvm.library)
}

dependencies {
    implementation(project(":core:model"))
}
