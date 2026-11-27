plugins {
    alias(libs.plugins.gcode.jvm.library)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    api(libs.jgit)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)
}
