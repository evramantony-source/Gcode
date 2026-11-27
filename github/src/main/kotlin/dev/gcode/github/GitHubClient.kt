package dev.gcode.github

import dev.gcode.core.model.RepoRef
import dev.gcode.github.api.ReposApi
import dev.gcode.github.api.UsersApi
import dev.gcode.github.http.GitHubHttp
import dev.gcode.github.model.CreateRepoRequest
import dev.gcode.github.model.ForkRequest
import dev.gcode.github.model.GhBranch
import dev.gcode.github.model.GhContent
import dev.gcode.github.model.GhLicense
import dev.gcode.github.model.GhOrg
import dev.gcode.github.model.GhRepo
import dev.gcode.github.model.GhSearchResult
import dev.gcode.github.model.GhUser
import dev.gcode.github.model.UpdateRepoRequest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/** GitHub REST client. Each area is a small interface so tests can fake only what they use. */
class GitHubClient(val http: GitHubHttp) : UsersApi, ReposApi {

    override suspend fun me(): GhUser = http.get("user", GhUser.serializer(), useEtag = false)

    override suspend fun orgs(): List<GhOrg> = http.getPaged("user/orgs", ListSerializer(GhOrg.serializer()), maxPages = 5)

    override fun tokenScopes(): Set<String>? = http.lastOAuthScopes

    override suspend fun myRepos(maxPages: Int): List<GhRepo> = http.getPaged(
        "user/repos",
        ListSerializer(GhRepo.serializer()),
        mapOf("affiliation" to "owner,collaborator,organization_member", "sort" to "pushed", "direction" to "desc"),
        maxPages = maxPages,
    )

    override suspend fun orgRepos(org: String, maxPages: Int): List<GhRepo> =
        http.getPaged("orgs/${seg(org)}/repos", ListSerializer(GhRepo.serializer()), mapOf("sort" to "pushed"), maxPages = maxPages)

    override suspend fun searchRepos(query: String, perPage: Int): GhSearchResult<GhRepo> = http.get(
        "search/repositories",
        GhSearchResult.serializer(GhRepo.serializer()),
        mapOf("q" to query, "per_page" to perPage.toString()),
    )

    override suspend fun repo(ref: RepoRef): GhRepo = http.get(path(ref), GhRepo.serializer())

    override suspend fun createRepo(request: CreateRepoRequest, org: String?): GhRepo =
        http.send("POST", if (org == null) "user/repos" else "orgs/${seg(org)}/repos", request, CreateRepoRequest.serializer(), GhRepo.serializer())

    override suspend fun fork(ref: RepoRef, organization: String?, name: String?): GhRepo =
        http.send("POST", "${path(ref)}/forks", ForkRequest(organization, name), ForkRequest.serializer(), GhRepo.serializer())

    override suspend fun deleteRepo(ref: RepoRef) {
        http.delete(path(ref))
    }

    override suspend fun setVisibility(ref: RepoRef, private: Boolean): GhRepo =
        http.send("PATCH", path(ref), UpdateRepoRequest(private = private), UpdateRepoRequest.serializer(), GhRepo.serializer())

    override suspend fun gitignoreTemplates(): List<String> = http.get("gitignore/templates", ListSerializer(String.serializer()))

    override suspend fun licenses(): List<GhLicense> = http.get("licenses", ListSerializer(GhLicense.serializer()), mapOf("per_page" to "100"))

    override suspend fun branches(ref: RepoRef): List<GhBranch> = http.getPaged("${path(ref)}/branches", ListSerializer(GhBranch.serializer()))

    override suspend fun deleteBranch(ref: RepoRef, branch: String) {
        http.delete("${path(ref)}/git/refs/heads/${branch.split('/').joinToString("/") { seg(it) }}")
    }

    override suspend fun content(ref: RepoRef, path: String, gitRef: String?): List<GhContent> {
        val response = http.send("GET", http.url("${path(ref)}/contents/${encodePath(path)}", mapOf("ref" to gitRef)), null)
        return when (val element = http.decode(response.body, kotlinx.serialization.json.JsonElement.serializer())) {
            is JsonArray -> http.decode(response.body, ListSerializer(GhContent.serializer()))
            is JsonObject -> listOf(http.decode(response.body, GhContent.serializer()))
            else -> error("Unexpected contents response: $element")
        }
    }

    override suspend fun rawFile(ref: RepoRef, path: String, gitRef: String?): String =
        http.send("GET", http.url("${path(ref)}/contents/${encodePath(path)}", mapOf("ref" to gitRef)), null, accept = "application/vnd.github.raw+json").body

    companion object {
        fun path(ref: RepoRef) = "repos/${ref.owner}/${ref.name}"

        fun seg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun encodePath(path: String): String = path.trim('/').split('/').joinToString("/") { seg(it) }
    }
}
