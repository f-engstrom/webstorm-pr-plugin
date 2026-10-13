package se.personal.adoprreview.state

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.personal.adoprreview.api.dto.ChangeEntry
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.PullRequest
import java.time.Instant

/** What the user had seen of a PR the last time they opened it. */
@Serializable
data class Snapshot(
    val iterationId: Int,
    val sourceCommit: String? = null,
    /** Latest comment activity by others, as an ISO instant. */
    val lastThreadActivity: String? = null,
    /** Files marked as viewed: path to the object ID that was viewed. */
    val viewedFiles: Map<String, String> = emptyMap(),
)

enum class Badge(val label: String) { NEW("New"), NEW_COMMITS("New commits"), NEW_ACTIVITY("New activity") }

object LastSeen {
    /** Snapshot key; PR IDs are unique per org. */
    fun key(org: String, prId: Int) = "${org.lowercase()}/$prId"

    /** Badges that need no extra requests. [Badge.NEW_ACTIVITY] is added separately via [hasNewActivity]. */
    fun badges(pr: PullRequest, snapshot: Snapshot?): Set<Badge> = when {
        snapshot == null -> setOf(Badge.NEW)
        pr.lastMergeSourceCommit?.commitId != null && pr.lastMergeSourceCommit.commitId != snapshot.sourceCommit -> setOf(Badge.NEW_COMMITS)
        else -> emptySet()
    }

    /** Latest time someone other than [myId] wrote or edited a comment. */
    fun latestActivityByOthers(threads: List<CommentThread>, myId: String): Instant? =
        threads.asSequence()
            .flatMap { it.visibleComments }
            .filter { !it.author.id.equals(myId, ignoreCase = true) }
            .mapNotNull { comment -> (comment.lastUpdatedDate ?: comment.publishedDate)?.let { runCatching { Instant.parse(it) }.getOrNull() } }
            .maxOrNull()

    fun hasNewActivity(snapshot: Snapshot, latest: Instant?): Boolean {
        if (latest == null) return false
        val seen = snapshot.lastThreadActivity?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
        return latest.isAfter(seen)
    }

    /** What a "viewed" mark is tied to: the file's current blob, so the mark clears when the file changes. */
    fun viewKey(change: ChangeEntry): String = change.item.objectId ?: "deleted:${change.item.originalObjectId}"
}

/** Per-PR [Snapshot]s, stored in the workspace file (not shared through VCS). */
@Service(Service.Level.PROJECT)
@State(name = "AdoPrReviewLastSeen", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class LastSeenService : SimplePersistentStateComponent<LastSeenService.LastSeenState>(LastSeenState()) {

    class LastSeenState : BaseState() {
        /** JSON map of key to [Snapshot]; BaseState has no good support for nested objects. */
        var snapshots by string()
    }

    private val snapshots: MutableMap<String, Snapshot> by lazy {
        state.snapshots?.let { runCatching { json.decodeFromString<Map<String, Snapshot>>(it) }.getOrNull() }
            .orEmpty().toMutableMap()
    }

    fun get(key: String): Snapshot? = snapshots[key]

    val size: Int get() = snapshots.size

    fun put(key: String, snapshot: Snapshot) {
        snapshots[key] = snapshot
        state.snapshots = json.encodeToString(snapshots.toMap())
    }

    fun update(key: String, change: (Snapshot) -> Snapshot) {
        get(key)?.let { put(key, change(it)) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun getInstance(project: Project): LastSeenService = project.service()
    }
}
