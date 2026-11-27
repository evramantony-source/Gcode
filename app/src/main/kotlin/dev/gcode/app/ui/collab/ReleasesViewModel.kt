package dev.gcode.app.ui.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gcode.core.data.db.BuildEntity
import dev.gcode.core.data.db.GCodeDatabase
import dev.gcode.github.api.ReleasesApi
import dev.gcode.github.api.ReposApi
import dev.gcode.github.model.CreateReleaseRequest
import dev.gcode.github.model.GhRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** An APK from the build history that can be attached to a release. */
data class ApkChoice(val path: String, val label: String)

data class ReleaseForm(
    val tag: String = "",
    val target: String = "",
    val name: String = "",
    val notes: String = "",
    val generateNotes: Boolean = true,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val apk: ApkChoice? = null,
)

data class ReleasesUiState(
    val target: CollabTarget = CollabTarget(null, null, false),
    val items: List<GhRelease> = emptyList(),
    val loading: Boolean = false,
    val busy: String? = null,
    val apks: List<ApkChoice> = emptyList(),
    val defaultBranch: String? = null,
    val showCreate: Boolean = false,
    /** Release chosen for "Upload APK". */
    val uploadTo: GhRelease? = null,
    val message: String? = null,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReleasesViewModel @Inject constructor(
    targets: CollabTargets,
    private val releases: ReleasesApi,
    private val repos: ReposApi,
    db: GCodeDatabase,
) : ViewModel() {
    private val _state = MutableStateFlow(ReleasesUiState())
    val state: StateFlow<ReleasesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            targets.target.collect { t ->
                _state.update { ReleasesUiState(target = t, apks = it.apks) }
                refresh()
            }
        }
        viewModelScope.launch {
            targets.target.map { it.ref?.fullName }
                .flatMapLatest { name -> if (name == null) emptyFlow() else db.builds().observeForRepo(name) }
                .collect { builds -> _state.update { it.copy(apks = apkChoices(builds)) } }
        }
    }

    private val ref get() = _state.value.target.ref?.takeIf { _state.value.target.signedIn }

    fun refresh() = launch {
        val ref = ref ?: return@launch
        _state.update { it.copy(loading = true) }
        try {
            val list = releases.releases(ref)
            _state.update { it.copy(items = list) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    fun showCreate(show: Boolean) {
        _state.update { it.copy(showCreate = show) }
        if (show && _state.value.defaultBranch == null) launch {
            val ref = ref ?: return@launch
            val branch = repos.repo(ref).defaultBranch
            _state.update { it.copy(defaultBranch = branch) }
        }
    }

    fun create(form: ReleaseForm) = launch {
        val ref = ref ?: return@launch
        _state.update { it.copy(showCreate = false, busy = "Creating ${form.tag}") }
        try {
            val release = releases.createRelease(
                ref,
                CreateReleaseRequest(
                    tagName = form.tag.trim(),
                    targetCommitish = form.target.trim().ifEmpty { null },
                    name = form.name.trim().ifEmpty { form.tag.trim() },
                    body = form.notes.ifBlank { null },
                    draft = form.draft,
                    prerelease = form.prerelease,
                    generateReleaseNotes = form.generateNotes,
                ),
            )
            form.apk?.let { upload(release, it) }
            _state.update { it.copy(message = "Created ${release.tagName}") }
        } finally {
            _state.update { it.copy(busy = null) }
        }
        refresh()
    }

    fun chooseUpload(release: GhRelease?) = _state.update { it.copy(uploadTo = release) }

    fun uploadTo(release: GhRelease, apk: ApkChoice) = launch {
        _state.update { it.copy(uploadTo = null, busy = "Uploading ${File(apk.path).name}") }
        try {
            upload(release, apk)
            _state.update { it.copy(message = "Uploaded ${File(apk.path).name} to ${release.tagName}") }
        } finally {
            _state.update { it.copy(busy = null) }
        }
        refresh()
    }

    private suspend fun upload(release: GhRelease, apk: ApkChoice) {
        val file = File(apk.path)
        require(file.isFile) { "${file.name} is no longer on this device" }
        releases.uploadAsset(release, file, assetName(release, apk), APK_TYPE)
    }

    fun dismiss() = _state.update { it.copy(message = null, error = null) }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e.toString(), loading = false, busy = null) }
        }
    }

    companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"

        fun apkChoices(builds: List<BuildEntity>): List<ApkChoice> = builds.filter { it.apkPath != null && File(it.apkPath!!).isFile }
            .map { ApkChoice(it.apkPath!!, "#${it.runNumber} ${it.branch.orEmpty()} ${it.headSha.take(7)} · ${it.apkLabel ?: File(it.apkPath!!).name}") }

        /** "app-v1.2.0.apk" style names, so assets from different releases don't all read app-debug.apk. */
        fun assetName(release: GhRelease, apk: ApkChoice): String {
            val base = File(apk.path).nameWithoutExtension
            val tag = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "-")
            return if (base.contains(tag)) "$base.apk" else "$base-$tag.apk"
        }
    }
}
