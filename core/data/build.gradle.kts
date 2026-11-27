plugins {
    alias(libs.plugins.gcode.android.library)
}

android {
    namespace = "dev.gcode.core.data"
}

dependencies {
    implementation(project(":core:model"))
}
