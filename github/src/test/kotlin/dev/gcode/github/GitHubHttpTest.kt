package dev.gcode.github

import com.google.common.truth.Truth.assertThat
import dev.gcode.core.model.RepoRef
import dev.gcode.github.http.GitHubApiException
import dev.gcode.github.http.GitHubHttp
import dev.gcode.github.model.CreateRepoRequest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class GitHubHttpTest {
    private val server = MockWebServer()
    private val sleeps = mutableListOf<Long>()
    private lateinit var client: GitHubClient

    @Before
    fun setUp() {
        server.start()
        client = GitHubClient(GitHubHttp(OkHttpClient(), token = { "gho_abc" }, baseUrl = server.url("/").toString(), sleep = { sleeps += it }))
    }

    @After
    fun tearDown() = server.close()

    private fun repoJson(name: String) =
        """{"id":1,"name":"$name","full_name":"octo/$name","owner":{"login":"octo"},"private":false,"html_url":"https://github.com/octo/$name","default_branch":"main"}"""

    @Test
    fun `sends auth, version and accept headers and follows link pagination`() = runTest {
        val next = server.url("/user/repos?page=2&per_page=100")
        server.enqueue(MockResponse.Builder().addHeader("Link", "<$next>; rel=\"next\", <$next>; rel=\"last\"").body("[${repoJson("a")}]").build())
        server.enqueue(MockResponse.Builder().body("[${repoJson("b")}]").build())
        val repos = client.myRepos()
        assertThat(repos.map { it.name }).containsExactly("a", "b").inOrder()
        val first = server.takeRequest()
        assertThat(first.headers["Authorization"]).isEqualTo("Bearer gho_abc")
        assertThat(first.headers["X-GitHub-Api-Version"]).isEqualTo("2026-03-10")
        assertThat(first.headers["Accept"]).isEqualTo("application/vnd.github+json")
        assertThat(first.url.queryParameter("affiliation")).isEqualTo("owner,collaborator,organization_member")
        assertThat(server.takeRequest().url.queryParameter("page")).isEqualTo("2")
    }

    @Test
    fun `uses etag conditional requests and serves the cached body on 304`() = runTest {
        server.enqueue(MockResponse.Builder().addHeader("ETag", "\"v1\"").body(repoJson("a")).build())
        server.enqueue(MockResponse.Builder().code(304).build())
        val ref = RepoRef("octo", "a")
        assertThat(client.repo(ref).name).isEqualTo("a")
        assertThat(client.repo(ref).name).isEqualTo("a")
        server.takeRequest()
        assertThat(server.takeRequest().headers["If-None-Match"]).isEqualTo("\"v1\"")
    }

    @Test
    fun `waits and retries on a secondary rate limit with retry-after`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).addHeader("Retry-After", "3").body("""{"message":"You have exceeded a secondary rate limit"}""").build())
        server.enqueue(MockResponse.Builder().body(repoJson("a")).build())
        assertThat(client.repo(RepoRef("octo", "a")).name).isEqualTo("a")
        assertThat(sleeps).containsExactly(3_000L)
    }

    @Test
    fun `parses validation errors and missing scopes`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(422).body("""{"message":"Repository creation failed.","errors":[{"resource":"Repository","code":"custom","field":"name","message":"name already exists on this account"}]}""").build(),
        )
        val e = runCatching { client.createRepo(CreateRepoRequest("dup")) }.exceptionOrNull() as GitHubApiException
        assertThat(e.isValidation).isTrue()
        assertThat(e.details).containsExactly("name already exists on this account")

        server.enqueue(MockResponse.Builder().code(403).addHeader("X-OAuth-Scopes", "repo").addHeader("X-Accepted-OAuth-Scopes", "delete_repo").body("""{"message":"Must have admin rights"}""").build())
        val scopes = runCatching { client.deleteRepo(RepoRef("octo", "a")) }.exceptionOrNull() as GitHubApiException
        assertThat(scopes.missingScopes).containsExactly("delete_repo")
    }

    @Test
    fun `create repo posts the expected body to user or org`() = runTest {
        server.enqueue(MockResponse.Builder().code(201).body(repoJson("new")).build())
        client.createRepo(CreateRepoRequest("new", "desc", private = true, autoInit = true, gitignoreTemplate = "Android", licenseTemplate = "apache-2.0"), org = "my-org")
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.url.encodedPath).isEqualTo("/orgs/my-org/repos")
        val body = req.body!!.utf8()
        assertThat(body).contains("\"private\":true")
        assertThat(body).contains("\"auto_init\":true")
        assertThat(body).contains("\"gitignore_template\":\"Android\"")
        assertThat(body).contains("\"license_template\":\"apache-2.0\"")
    }

    @Test
    fun `reads raw file contents and records token scopes`() = runTest {
        server.enqueue(MockResponse.Builder().addHeader("X-OAuth-Scopes", "repo, workflow, read:org").body("hello world").build())
        assertThat(client.rawFile(RepoRef("octo", "a"), "src/Main Activity.kt", "dev")).isEqualTo("hello world")
        val req = server.takeRequest()
        assertThat(req.headers["Accept"]).isEqualTo("application/vnd.github.raw+json")
        assertThat(req.url.encodedPath).isEqualTo("/repos/octo/a/contents/src/Main%20Activity.kt")
        assertThat(req.url.queryParameter("ref")).isEqualTo("dev")
        assertThat(client.tokenScopes()).containsExactly("repo", "workflow", "read:org")
    }
}
