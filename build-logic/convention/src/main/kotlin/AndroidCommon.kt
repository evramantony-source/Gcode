import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project

/** Settings shared by the application and library modules. */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    android.apply {
        compileSdk = GCodeBuildConfig.COMPILE_SDK
        compileSdkMinor = GCodeBuildConfig.COMPILE_SDK_MINOR
        defaultConfig.minSdk = GCodeBuildConfig.MIN_SDK
        compileOptions.sourceCompatibility = GCodeBuildConfig.JAVA_VERSION
        compileOptions.targetCompatibility = GCodeBuildConfig.JAVA_VERSION
        testOptions.unitTests.isReturnDefaultValues = true
        packaging.resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
            "/META-INF/DEPENDENCIES",
        )
    }
    configureKotlinCompiler()
}
