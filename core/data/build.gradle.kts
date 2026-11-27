plugins {
    alias(libs.plugins.gcode.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.gcode.core.data"
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    api(project(":gemini"))
    api(project(":github"))
    api(project(":git"))
    implementation(project(":core:common"))
    implementation(libs.javax.inject)
    api(libs.androidx.datastore.preferences)
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.androidx.datastore.preferences.core)
    testImplementation(libs.androidx.room.testing)
}
