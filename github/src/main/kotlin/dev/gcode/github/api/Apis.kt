package dev.gcode.github.api

import dev.gcode.core.model.RepoRef
import dev.gcode.github.model.CreateRepoRequest
import dev.gcode.github.model.GhBranch
import dev.gcode.github.model.GhContent
import dev.gcode.github.model.GhLicense
import dev.gcode.github.model.GhOrg
import dev.gcode.github.model.GhRepo
import dev.gcode.github.model.GhSearchResult
import dev.gcode.github.model.GhUser

/** The authenticated user and their organizations. */
interface UsersApi {
    suspend fun me(): GhUser
    suspend fun orgs(): List<GhOrg>

    /** Scopes of a classic/OAuth token from the last response, or null for fine-grained tokens. */
    fun tokenScopes(): Set<String>?
}

interface ReposApi {
    suspend fun myRepos(maxPages: Int = 10): List<GhRepo>
    suspend fun orgRepos(org: String, maxPages: Int = 5): List<GhRepo>
    suspend fun searchRepos(query: String, perPage: Int = 50): GhSearchResult<GhRepo>
    suspend fun repo(ref: RepoRef): GhRepo
    suspend fun createRepo(request: CreateRepoRequest, org: String? = null): GhRepo
    suspend fun fork(ref: RepoRef, organization: String? = null, name: String? = null): GhRepo
    suspend fun deleteRepo(ref: RepoRef)
    suspend fun setVisibility(ref: RepoRef, private: Boolean): GhRepo
    suspend fun gitignoreTemplates(): List<String>
    suspend fun licenses(): List<GhLicense>
    suspend fun branches(ref: RepoRef): List<GhBranch>
    suspend fun deleteBranch(ref: RepoRef, branch: String)

    /** Reads a file (or lists a directory) without cloning. */
    suspend fun content(ref: RepoRef, path: String, gitRef: String? = null): List<GhContent>

    /** Raw text of a file of any size up to 100 MB. */
    suspend fun rawFile(ref: RepoRef, path: String, gitRef: String? = null): String
}
