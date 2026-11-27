import org.gradle.api.JavaVersion

/** Shared build constants for every module. */
object GCodeBuildConfig {
    const val COMPILE_SDK = 37

    /** Android 17 minor SDK release (platform 37.2). Compose 1.13 requires 37.1 or newer. */
    const val COMPILE_SDK_MINOR = 2
    const val TARGET_SDK = 37
    const val MIN_SDK = 26
    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
}
