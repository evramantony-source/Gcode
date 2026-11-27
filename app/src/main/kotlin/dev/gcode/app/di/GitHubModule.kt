package dev.gcode.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gcode.core.data.secret.SecretKey
import dev.gcode.core.data.secret.SecretStore
import dev.gcode.git.WorkspaceManager
import dev.gcode.github.GitHubClient
import dev.gcode.github.api.ActionsApi
import dev.gcode.github.api.ContentsApi
import dev.gcode.github.api.ReleasesApi
import dev.gcode.github.api.ReposApi
import dev.gcode.github.api.SecretsApi
import dev.gcode.github.api.UsersApi
import dev.gcode.github.http.GitHubHttp
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GitHubModule {
    @Provides
    @Singleton
    fun gitHubHttp(client: OkHttpClient, secrets: SecretStore) = GitHubHttp(client, token = { secrets.get(SecretKey.GITHUB_TOKEN) })

    @Provides
    @Singleton
    fun gitHubClient(http: GitHubHttp) = GitHubClient(http)

    @Provides
    fun usersApi(client: GitHubClient): UsersApi = client

    @Provides
    fun reposApi(client: GitHubClient): ReposApi = client

    @Provides
    fun actionsApi(client: GitHubClient): ActionsApi = client

    @Provides
    fun releasesApi(client: GitHubClient): ReleasesApi = client

    @Provides
    fun secretsApi(client: GitHubClient): SecretsApi = client

    @Provides
    fun contentsApi(client: GitHubClient): ContentsApi = client

    @Provides
    @Singleton
    fun workspaceManager(@ApplicationContext context: Context) = WorkspaceManager(File(context.filesDir, "workspace").apply { mkdirs() })
}
