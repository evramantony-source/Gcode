package dev.gcode.git

import dev.gcode.core.model.RepoRef

/** Remote URL helpers. JGit operations arrive in Phase 3. */
object GitRemotes {
    /** Username GitHub accepts alongside a token as the password over HTTPS. */
    const val TOKEN_USERNAME = "x-access-token"

    fun httpsCloneUrl(repo: RepoRef): String = "https://github.com/${repo.fullName}.git"

    /** Extracts the repository from a github.com HTTPS or SSH remote URL. */
    fun parseGitHubRemote(url: String): RepoRef? {
        val trimmed = url.trim()
        val path = when {
            trimmed.startsWith("https://github.com/") -> trimmed.removePrefix("https://github.com/")
            trimmed.startsWith("git@github.com:") -> trimmed.removePrefix("git@github.com:")
            trimmed.startsWith("ssh://git@github.com/") -> trimmed.removePrefix("ssh://git@github.com/")
            else -> return null
        }
        return RepoRef.parse(path.removeSuffix("/"))
    }
}
