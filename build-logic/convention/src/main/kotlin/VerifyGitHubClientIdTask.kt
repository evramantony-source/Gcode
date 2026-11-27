import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails the build with a clear message when the GitHub OAuth App client ID is missing or malformed.
 * The ID is not a secret, but it must never be hard-coded: it comes from the `GCODE_GITHUB_CLIENT_ID`
 * environment variable (a repository variable in CI) or the `gcode.githubClientId` Gradle property.
 */
@DisableCachingByDefault(because = "Validation only; produces no output")
abstract class VerifyGitHubClientIdTask : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val clientId: Property<String>

    @TaskAction
    fun verify() {
        val id = clientId.orNull
        if (id == null) {
            throw GradleException(
                """
                |GitHub OAuth App client ID is missing.
                |
                |GCode needs the client ID of your GitHub OAuth App (with Device Flow enabled) at build time.
                |It is not a secret, but it is never hard-coded. Provide it in one of these ways:
                |  - CI: add a repository variable named GCODE_GITHUB_CLIENT_ID
                |        (GitHub > Settings > Secrets and variables > Actions > Variables).
                |  - Local: export GCODE_GITHUB_CLIENT_ID=<id>, or pass -Pgcode.githubClientId=<id>,
                |           or put gcode.githubClientId=<id> in ~/.gradle/gradle.properties.
                """.trimMargin(),
            )
        }
        if (!CLIENT_ID_PATTERN.matches(id)) {
            throw GradleException(
                "GCODE_GITHUB_CLIENT_ID has an unexpected format (${id.length} characters). " +
                    "Expected the OAuth App 'Client ID' from GitHub (letters, digits, '.', '_' or '-').",
            )
        }
    }

    companion object {
        val CLIENT_ID_PATTERN = Regex("^[A-Za-z0-9._-]{8,64}$")
    }
}
