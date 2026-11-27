package dev.gcode.core.model

/**
 * A GitHub repository identified by owner and name, e.g. `octocat/hello-world`.
 */
data class RepoRef(val owner: String, val name: String) {
    init {
        require(isValidOwner(owner)) { "Invalid repository owner: '$owner'" }
        require(isValidName(name)) { "Invalid repository name: '$name'" }
    }

    val fullName: String get() = "$owner/$name"

    override fun toString(): String = fullName

    companion object {
        // GitHub logins: alphanumeric or single hyphens, max 39 chars, no leading/trailing hyphen.
        private val OWNER = Regex("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")

        // Repository names: letters, digits, '.', '-', '_' (max 100), and not "." or "..".
        private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")

        fun isValidOwner(owner: String): Boolean = OWNER.matches(owner)

        fun isValidName(name: String): Boolean = NAME.matches(name) && name != "." && name != ".."

        /** Parses `owner/name`, ignoring surrounding whitespace and a trailing `.git`. */
        fun parse(fullName: String): RepoRef? {
            val parts = fullName.trim().removeSuffix(".git").split('/')
            if (parts.size != 2) return null
            val (owner, name) = parts
            return if (isValidOwner(owner) && isValidName(name)) RepoRef(owner, name) else null
        }
    }
}
