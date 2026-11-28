plugins {
    alias(libs.plugins.gcode.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":gemini"))
    api(project(":git"))
    api(project(":github"))
    api(project(":mcp"))
    implementation(libs.jsoup)
    implementation(project(":core:common"))
    implementation(libs.javax.inject)

    testImplementation(libs.okhttp.mockwebserver)
}
