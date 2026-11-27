package dev.gcode.github

import dev.gcode.core.model.RepoRef
import dev.gcode.github.api.ActionsApi
import dev.gcode.github.api.ContentsApi
import dev.gcode.github.api.IssuesApi
import dev.gcode.github.api.PullsApi
import dev.gcode.github.api.ReleasesApi
import dev.gcode.github.api.ReposApi
import dev.gcode.github.api.SecretsApi
import dev.gcode.github.api.UsersApi
import dev.gcode.github.crypto.SealedBox
import dev.gcode.github.model.CommentRequest
import dev.gcode.github.model.CreateIssueRequest
import dev.gcode.github.model.CreatePullRequest
import dev.gcode.github.model.CreateReleaseRequest
import dev.gcode.github.model.GhComment
import dev.gcode.github.model.GhIssue
import dev.gcode.github.model.GhPull
import dev.gcode.github.model.GhPullFile
import dev.gcode.github.model.MergePullRequest
import dev.gcode.github.model.MergeResult
import dev.gcode.github.model.UpdateIssueRequest
import dev.gcode.github.model.DispatchRequest
import dev.gcode.github.model.DispatchResponse
import dev.gcode.github.model.GhArtifact
import dev.gcode.github.model.GhJob
import dev.gcode.github.model.GhJobsPage
import dev.gcode.github.model.GhArtifactsPage
import dev.gcode.github.model.GhPublicKey
import dev.gcode.github.model.GhRelease
import dev.gcode.github.model.GhReleaseAsset
import dev.gcode.github.model.GhRunsPage
import dev.gcode.github.model.GhSecretInfo
import dev.gcode.github.model.GhSecretsPage
import dev.gcode.github.model.GhWorkflow
import dev.gcode.github.model.GhWorkflowRun
import dev.gcode.github.model.GhWorkflowsPage
import dev.gcode.github.model.PutContentRequest
import dev.gcode.github.model.PutContentResponse
import dev.gcode.github.model.PutSecretRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.util.Base64
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
class GitHubClient(val http: GitHubHttp) : UsersApi, ReposApi, ActionsApi, ReleasesApi, SecretsApi, ContentsApi, PullsApi, IssuesApi {

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

    // ---------------------------------------------------------------- Actions

    override suspend fun workflows(ref: RepoRef): List<GhWorkflow> =
        http.get("${path(ref)}/actions/workflows", GhWorkflowsPage.serializer(), mapOf("per_page" to "100")).workflows

    override suspend fun dispatch(ref: RepoRef, workflow: String, gitRef: String, inputs: Map<String, String>): DispatchResponse {
        val response = http.sendNoContent("POST", "${path(ref)}/actions/workflows/${seg(workflow)}/dispatches", DispatchRequest(gitRef, inputs.ifEmpty { null }), DispatchRequest.serializer())
        return if (response.body.isBlank()) DispatchResponse() else http.decode(response.body, DispatchResponse.serializer())
    }

    override suspend fun runs(ref: RepoRef, workflow: String?, branch: String?, headSha: String?, event: String?, perPage: Int): List<GhWorkflowRun> {
        val base = if (workflow != null) "${path(ref)}/actions/workflows/${seg(workflow)}/runs" else "${path(ref)}/actions/runs"
        return http.get(base, GhRunsPage.serializer(), mapOf("branch" to branch, "head_sha" to headSha, "event" to event, "per_page" to perPage.toString())).runs
    }

    override suspend fun run(ref: RepoRef, runId: Long): GhWorkflowRun = http.get("${path(ref)}/actions/runs/$runId", GhWorkflowRun.serializer())

    override suspend fun jobs(ref: RepoRef, runId: Long): List<GhJob> =
        http.get("${path(ref)}/actions/runs/$runId/jobs", GhJobsPage.serializer(), mapOf("per_page" to "100")).jobs

    override suspend fun jobLog(ref: RepoRef, jobId: Long): String =
        http.send("GET", http.url("${path(ref)}/actions/jobs/$jobId/logs"), null, useEtag = false).body

    override suspend fun artifacts(ref: RepoRef, runId: Long): List<GhArtifact> =
        http.get("${path(ref)}/actions/runs/$runId/artifacts", GhArtifactsPage.serializer(), mapOf("per_page" to "100"), useEtag = false).artifacts

    override suspend fun downloadArtifact(ref: RepoRef, artifactId: Long, dest: java.io.File, progress: (Long, Long) -> Unit) =
        http.download(http.url("${path(ref)}/actions/artifacts/$artifactId/zip"), dest, progress)

    override suspend fun cancelRun(ref: RepoRef, runId: Long) {
        http.sendNoContent<Unit>("POST", "${path(ref)}/actions/runs/$runId/cancel", null, null)
    }

    override suspend fun rerunFailedJobs(ref: RepoRef, runId: Long) {
        http.sendNoContent<Unit>("POST", "${path(ref)}/actions/runs/$runId/rerun-failed-jobs", null, null)
    }

    // ---------------------------------------------------------------- Releases

    override suspend fun releases(ref: RepoRef): List<GhRelease> = http.getPaged("${path(ref)}/releases", ListSerializer(GhRelease.serializer()), maxPages = 3)

    override suspend fun createRelease(ref: RepoRef, request: CreateReleaseRequest): GhRelease =
        http.send("POST", "${path(ref)}/releases", request, CreateReleaseRequest.serializer(), GhRelease.serializer())

    override suspend fun uploadAsset(release: GhRelease, file: java.io.File, name: String, contentType: String): GhReleaseAsset {
        // upload_url is an RFC 6570 template: https://uploads.github.com/repos/o/r/releases/1/assets{?name,label}
        val base = release.uploadUrl.substringBefore('{')
        val url = http.url(base, mapOf("name" to name))
        val body = file.asRequestBody(contentType.toMediaType())
        return http.decode(http.upload(url, body).body, GhReleaseAsset.serializer())
    }

    // ---------------------------------------------------------------- Secrets

    override suspend fun secretNames(ref: RepoRef): List<GhSecretInfo> =
        http.get("${path(ref)}/actions/secrets", GhSecretsPage.serializer(), mapOf("per_page" to "100"), useEtag = false).secrets

    override suspend fun publicKey(ref: RepoRef): GhPublicKey = http.get("${path(ref)}/actions/secrets/public-key", GhPublicKey.serializer(), useEtag = false)

    override suspend fun putSecret(ref: RepoRef, name: String, value: String) {
        require(SECRET_NAME.matches(name)) { "Secret names use letters, digits and underscores, and cannot start with a digit or GITHUB_" }
        val key = publicKey(ref)
        val sealed = SealedBox.seal(value.toByteArray(Charsets.UTF_8), Base64.getDecoder().decode(key.key))
        http.sendNoContent("PUT", "${path(ref)}/actions/secrets/${seg(name)}", PutSecretRequest(Base64.getEncoder().encodeToString(sealed), key.keyId), PutSecretRequest.serializer())
    }

    // ---------------------------------------------------------------- Contents

    override suspend fun putFile(ref: RepoRef, path: String, content: ByteArray, message: String, branch: String?): String? {
        val existing = runCatching { content(ref, path, branch).singleOrNull()?.sha }.getOrNull()
        val request = PutContentRequest(message, Base64.getEncoder().encodeToString(content), branch, existing)
        val response = http.send("PUT", "${path(ref)}/contents/${encodePath(path)}", request, PutContentRequest.serializer(), PutContentResponse.serializer())
        return response.commit?.sha
    }

    // ---------------------------------------------------------------- Pull requests

    override suspend fun pulls(ref: RepoRef, state: String, base: String?, head: String?, perPage: Int): List<GhPull> = http.get(
        "${path(ref)}/pulls",
        ListSerializer(GhPull.serializer()),
        mapOf("state" to state, "base" to base, "head" to head, "sort" to "updated", "direction" to "desc", "per_page" to perPage.coerceIn(1, 100).toString()),
    )

    override suspend fun pull(ref: RepoRef, number: Int): GhPull = http.get("${path(ref)}/pulls/$number", GhPull.serializer(), useEtag = false)

    override suspend fun createPull(ref: RepoRef, request: CreatePullRequest): GhPull =
        http.send("POST", "${path(ref)}/pulls", request, CreatePullRequest.serializer(), GhPull.serializer())

    override suspend fun mergePull(ref: RepoRef, number: Int, request: MergePullRequest): MergeResult =
        http.send("PUT", "${path(ref)}/pulls/$number/merge", request, MergePullRequest.serializer(), MergeResult.serializer())

    override suspend fun pullFiles(ref: RepoRef, number: Int, maxPages: Int): List<GhPullFile> =
        http.getPaged("${path(ref)}/pulls/$number/files", ListSerializer(GhPullFile.serializer()), maxPages = maxPages)

    override suspend fun updatePullState(ref: RepoRef, number: Int, open: Boolean): GhPull = http.send(
        "PATCH", "${path(ref)}/pulls/$number", UpdateIssueRequest(state = if (open) "open" else "closed"), UpdateIssueRequest.serializer(), GhPull.serializer(),
    )

    // ---------------------------------------------------------------- Issues and comments

    override suspend fun issues(ref: RepoRef, state: String, labels: String?, perPage: Int): List<GhIssue> = http.get(
        "${path(ref)}/issues",
        ListSerializer(GhIssue.serializer()),
        mapOf("state" to state, "labels" to labels, "sort" to "updated", "direction" to "desc", "per_page" to perPage.coerceIn(1, 100).toString()),
    ).filterNot { it.isPullRequest }

    override suspend fun issue(ref: RepoRef, number: Int): GhIssue = http.get("${path(ref)}/issues/$number", GhIssue.serializer(), useEtag = false)

    override suspend fun createIssue(ref: RepoRef, request: CreateIssueRequest): GhIssue =
        http.send("POST", "${path(ref)}/issues", request, CreateIssueRequest.serializer(), GhIssue.serializer())

    override suspend fun updateIssue(ref: RepoRef, number: Int, request: UpdateIssueRequest): GhIssue =
        http.send("PATCH", "${path(ref)}/issues/$number", request, UpdateIssueRequest.serializer(), GhIssue.serializer())

    override suspend fun comments(ref: RepoRef, number: Int, maxPages: Int): List<GhComment> =
        http.getPaged("${path(ref)}/issues/$number/comments", ListSerializer(GhComment.serializer()), maxPages = maxPages)

    override suspend fun comment(ref: RepoRef, number: Int, body: String): GhComment =
        http.send("POST", "${path(ref)}/issues/$number/comments", CommentRequest(body), CommentRequest.serializer(), GhComment.serializer())

    companion object {
        private val SECRET_NAME = Regex("^(?!GITHUB_)(?![0-9])[A-Za-z0-9_]+$")

        fun path(ref: RepoRef) = "repos/${ref.owner}/${ref.name}"

        fun seg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun encodePath(path: String): String = path.trim('/').split('/').joinToString("/") { seg(it) }
    }
}
