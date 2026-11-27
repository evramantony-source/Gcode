package dev.gcode.github

import com.google.common.truth.Truth.assertThat
import dev.gcode.core.model.RepoRef
import dev.gcode.github.http.GitHubApiException
import dev.gcode.github.http.GitHubHttp
import dev.gcode.github.model.CreateIssueRequest
import dev.gcode.github.model.CreatePullRequest
import dev.gcode.github.model.MergePullRequest
import dev.gcode.github.model.UpdateIssueRequest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class CollabApiTest {
    private val server = MockWebServer()
    private lateinit var client: GitHubClient
    private val repo = RepoRef("octo", "app")

    @Before
    fun setUp() {
        server.start()
        client = GitHubClient(GitHubHttp(OkHttpClient(), token = { "gho_x" }, baseUrl = server.url("/").toString(), sleep = {}))
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private val pullJson = """{"number":7,"title":"Fix build","state":"open","mergeable":true,"mergeable_state":"clean",
        "head":{"ref":"fix","sha":"abc123"},"base":{"ref":"main","sha":"def456"},"html_url":"https://github.com/octo/app/pull/7"}"""

    @Test
    fun createsListsAndMergesPullRequests() = runTest {
        server.enqueue(json(pullJson, 201))
        server.enqueue(json("[$pullJson]"))
        server.enqueue(json("""{"sha":"merge1","merged":true,"message":"Pull Request successfully merged"}"""))

        val created = client.createPull(repo, CreatePullRequest("Fix build", head = "fix", base = "main", body = "Details"))
        assertThat(created.number).isEqualTo(7)
        val post = server.takeRequest()
        assertThat(post.method).isEqualTo("POST")
        assertThat(post.target).isEqualTo("/repos/octo/app/pulls")
        assertThat(post.body!!.utf8()).isEqualTo("""{"title":"Fix build","head":"fix","base":"main","body":"Details"}""")

        val list = client.pulls(repo, state = "open", base = "main")
        assertThat(list.single().head.sha).isEqualTo("abc123")
        assertThat(server.takeRequest().target).isEqualTo("/repos/octo/app/pulls?state=open&base=main&sort=updated&direction=desc&per_page=30")

        val merged = client.mergePull(repo, 7, MergePullRequest(sha = "abc123", mergeMethod = "squash"))
        assertThat(merged.merged).isTrue()
        val put = server.takeRequest()
        assertThat(put.method).isEqualTo("PUT")
        assertThat(put.target).isEqualTo("/repos/octo/app/pulls/7/merge")
        assertThat(put.body!!.utf8()).isEqualTo("""{"sha":"abc123","merge_method":"squash"}""")
    }

    @Test
    fun notMergeableIs405() = runTest {
        server.enqueue(json("""{"message":"Pull Request is not mergeable"}""", 405))
        val e = assertThrows(GitHubApiException::class.java) { kotlinx.coroutines.runBlocking { client.mergePull(repo, 7, MergePullRequest()) } }
        assertThat(e.status).isEqualTo(405)
    }

    @Test
    fun issueListDropsPullRequests() = runTest {
        server.enqueue(
            json(
                """[{"number":1,"title":"Crash on start","labels":[{"name":"bug"}]},
                   {"number":2,"title":"A PR","pull_request":{"url":"x"}}]""",
            ),
        )
        val issues = client.issues(repo, state = "all")
        assertThat(issues.map { it.number }).containsExactly(1)
        assertThat(issues.single().labels.single().name).isEqualTo("bug")
    }

    @Test
    fun createsUpdatesAndCommentsOnIssues() = runTest {
        server.enqueue(json("""{"number":3,"title":"New"}""", 201))
        server.enqueue(json("""{"number":3,"title":"New","state":"closed","state_reason":"completed"}"""))
        server.enqueue(json("""{"id":9,"body":"Thanks"}""", 201))

        client.createIssue(repo, CreateIssueRequest("New", body = "Steps", labels = listOf("bug")))
        assertThat(server.takeRequest().body!!.utf8()).isEqualTo("""{"title":"New","body":"Steps","labels":["bug"]}""")

        val closed = client.updateIssue(repo, 3, UpdateIssueRequest(state = "closed", stateReason = "completed"))
        assertThat(closed.stateReason).isEqualTo("completed")
        val patch = server.takeRequest()
        assertThat(patch.method).isEqualTo("PATCH")
        assertThat(patch.body!!.utf8()).isEqualTo("""{"state":"closed","state_reason":"completed"}""")

        assertThat(client.comment(repo, 3, "Thanks").id).isEqualTo(9)
        val comment = server.takeRequest()
        assertThat(comment.target).isEqualTo("/repos/octo/app/issues/3/comments")
        assertThat(comment.body!!.utf8()).isEqualTo("""{"body":"Thanks"}""")
    }
}
