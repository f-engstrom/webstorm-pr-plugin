package se.personal.adoprreview.api

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.personal.adoprreview.api.dto.ChangeEntry
import se.personal.adoprreview.api.dto.ChangesResponse
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.CommentUpdate
import se.personal.adoprreview.api.dto.ConnectionData
import se.personal.adoprreview.api.dto.Identity
import se.personal.adoprreview.api.dto.Iteration
import se.personal.adoprreview.api.dto.ListResponse
import se.personal.adoprreview.api.dto.NewComment
import se.personal.adoprreview.api.dto.NewPullRequest
import se.personal.adoprreview.api.dto.NewThread
import se.personal.adoprreview.api.dto.PullRequest
import se.personal.adoprreview.api.dto.PullRequestUpdate
import se.personal.adoprreview.api.dto.RepositoryInfo
import se.personal.adoprreview.api.dto.ReviewerVote
import se.personal.adoprreview.api.dto.ThreadStatus
import se.personal.adoprreview.api.dto.ThreadStatusUpdate
import se.personal.adoprreview.api.dto.Vote
import se.personal.adoprreview.auth.AzCliToken
import se.personal.adoprreview.repo.AdoRemote
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

class AdoException(message: String, val status: Int? = null) : Exception(message) {
    val isAuthError: Boolean get() = status == 401 || status == 403 || status == 203
}

enum class PrFilter(val label: String) { REVIEWER("Assigned to me"), AUTHOR("Created by me"), ALL("All active") }

@Service
class AdoClient {

    /** Request counts and the last failure, for the Status tab. Paths only; no query strings or headers. */
    class NetworkStats {
        data class Failure(val at: Instant, val method: String, val path: String, val status: Int?, val message: String)

        private val total = AtomicInteger()
        private val failed = AtomicInteger()
        private val recent = ConcurrentLinkedDeque<Instant>()

        @Volatile
        var lastFailure: Failure? = null
            private set

        fun record(method: String, path: String, status: Int?, failure: String?) {
            val now = Instant.now()
            total.incrementAndGet()
            recent.addLast(now)
            if (failure != null) {
                failed.incrementAndGet()
                lastFailure = Failure(now, method, path, status, failure)
            }
        }

        val totalRequests: Int get() = total.get()
        val failedRequests: Int get() = failed.get()

        fun requestsInLast(minutes: Long): Int {
            val since = Instant.now().minusSeconds(minutes * 60)
            while (recent.peekFirst()?.isBefore(Instant.now().minusSeconds(3600)) == true) recent.pollFirst()
            return recent.count { it.isAfter(since) }
        }
    }

    val stats = NetworkStats()

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    suspend fun me(org: String): ConnectionData =
        get("${AdoRemote.HOST}/${AdoRemote.encode(org)}/_apis/connectionData", apiVersion = null)

    suspend fun listPullRequests(remote: AdoRemote, filter: PrFilter, myId: String): List<PullRequest> {
        val criteria = when (filter) {
            PrFilter.REVIEWER -> "&searchCriteria.reviewerId=$myId"
            PrFilter.AUTHOR -> "&searchCriteria.creatorId=$myId"
            PrFilter.ALL -> ""
        }
        return get<ListResponse<PullRequest>>("${remote.apiUrl}/pullrequests?searchCriteria.status=active$criteria&\$top=200").value
    }

    /** Identities by ID, from the org's identity service (vssps.dev.azure.com). */
    suspend fun identities(org: String, ids: Collection<String>): List<Identity> =
        get<ListResponse<Identity>>("$IDENTITY_HOST/${AdoRemote.encode(org)}/_apis/identities?identityIds=${ids.joinToString(",")}").value

    suspend fun pullRequest(remote: AdoRemote, id: Int): PullRequest = get("${remote.apiUrl}/pullrequests/$id")

    suspend fun repository(remote: AdoRemote): RepositoryInfo = get(remote.apiUrl)

    /** The active PR from [branch], if any. */
    suspend fun activePullRequestFrom(remote: AdoRemote, branch: String): PullRequest? =
        get<ListResponse<PullRequest>>(
            "${remote.apiUrl}/pullrequests?searchCriteria.status=active" +
                "&searchCriteria.sourceRefName=${URLEncoder.encode("refs/heads/$branch", UTF_8)}&\$top=1"
        ).value.firstOrNull()

    suspend fun createPullRequest(remote: AdoRemote, pr: NewPullRequest): PullRequest =
        json.decodeFromString(String(write("POST", "${remote.apiUrl}/pullrequests", pr), UTF_8))

    suspend fun updatePullRequest(remote: AdoRemote, id: Int, update: PullRequestUpdate) {
        write("PATCH", "${remote.apiUrl}/pullrequests/$id", update)
    }

    suspend fun iterations(remote: AdoRemote, prId: Int): List<Iteration> =
        get<ListResponse<Iteration>>("${remote.apiUrl}/pullrequests/$prId/iterations").value

    /** Files changed in [iterationId] compared to iteration [compareTo]; 0 means the common commit of source and target. */
    suspend fun changes(remote: AdoRemote, prId: Int, iterationId: Int, compareTo: Int): List<ChangeEntry> {
        val entries = mutableListOf<ChangeEntry>()
        var skip = 0
        while (true) {
            val page = get<ChangesResponse>(
                "${remote.apiUrl}/pullrequests/$prId/iterations/$iterationId/changes?\$compareTo=$compareTo&\$top=2000&\$skip=$skip"
            )
            entries += page.changeEntries.filter { !it.item.isFolder && it.item.gitObjectType != "tree" }
            if (page.nextTop == 0 || page.changeEntries.isEmpty()) return entries
            skip = page.nextSkip
        }
    }

    /** Raw file bytes at [path] in [commitId]. */
    suspend fun fileContent(remote: AdoRemote, path: String, commitId: String): ByteArray = send(
        withApiVersion(
            "${remote.apiUrl}/items?path=${URLEncoder.encode(path, UTF_8)}" +
                "&versionDescriptor.version=$commitId&versionDescriptor.versionType=commit&\$format=octetStream",
            API_VERSION,
        ),
        accept = "application/octet-stream",
    ) { GET() }

    suspend fun threads(remote: AdoRemote, prId: Int): List<CommentThread> =
        get<ListResponse<CommentThread>>("${remote.apiUrl}/pullrequests/$prId/threads").value

    suspend fun reply(remote: AdoRemote, prId: Int, threadId: Int, parentCommentId: Int, content: String) {
        write("POST", "${remote.apiUrl}/pullrequests/$prId/threads/$threadId/comments", NewComment(content, parentCommentId, commentType = 1))
    }

    suspend fun createThread(remote: AdoRemote, prId: Int, thread: NewThread) {
        write("POST", "${remote.apiUrl}/pullrequests/$prId/threads", thread)
    }

    suspend fun vote(remote: AdoRemote, prId: Int, reviewerId: String, vote: Vote) {
        write("PUT", "${remote.apiUrl}/pullrequests/$prId/reviewers/$reviewerId", ReviewerVote(vote.value))
    }

    suspend fun setLiked(remote: AdoRemote, prId: Int, threadId: Int, commentId: Int, liked: Boolean) {
        val url = withApiVersion("${remote.apiUrl}/pullrequests/$prId/threads/$threadId/comments/$commentId/likes", API_VERSION)
        // A POST without a body gets no Content-Length header from java.net.http, which ADO rejects with 411.
        send(url) {
            if (liked) header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")) else DELETE()
        }
    }

    suspend fun editComment(remote: AdoRemote, prId: Int, threadId: Int, commentId: Int, content: String) {
        write("PATCH", "${remote.apiUrl}/pullrequests/$prId/threads/$threadId/comments/$commentId", CommentUpdate(content))
    }

    suspend fun deleteComment(remote: AdoRemote, prId: Int, threadId: Int, commentId: Int) {
        val url = withApiVersion("${remote.apiUrl}/pullrequests/$prId/threads/$threadId/comments/$commentId", API_VERSION)
        send(url) { DELETE() }
    }

    suspend fun setThreadStatus(remote: AdoRemote, prId: Int, threadId: Int, status: ThreadStatus) {
        write("PATCH", "${remote.apiUrl}/pullrequests/$prId/threads/$threadId", ThreadStatusUpdate(status.value))
    }

    private suspend inline fun <reified B> write(httpMethod: String, url: String, body: B): ByteArray {
        val payload = json.encodeToString(body)
        return send(withApiVersion(url, API_VERSION)) {
            header("Content-Type", "application/json").method(httpMethod, HttpRequest.BodyPublishers.ofString(payload))
        }
    }

    private suspend inline fun <reified T> get(url: String, apiVersion: String? = API_VERSION): T =
        json.decodeFromString(String(send(withApiVersion(url, apiVersion)) { GET() }, UTF_8))

    private suspend fun send(
        url: String,
        accept: String = "application/json",
        retryOnAuth: Boolean = true,
        method: HttpRequest.Builder.() -> HttpRequest.Builder,
    ): ByteArray {
        val token = AzCliToken.getInstance()
        val org = orgOf(url)
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", token.authHeader(org))
            .header("Accept", accept)
            .method()
            .build()
        val path = URI(url).path
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).await()
        } catch (e: IOException) {
            stats.record(request.method(), path, null, e.message ?: e.javaClass.simpleName)
            throw e
        }
        val status = response.statusCode()
        stats.record(request.method(), path, status, if (status in 200..299) null else "HTTP $status")
        // With a bad token ADO may answer with a sign-in page (203 or a redirect) rather than a 401.
        val authFailed = status == 401 || status == 203 || status in 300..399
        if (authFailed && retryOnAuth) {
            token.invalidate(org)
            return send(url, accept, retryOnAuth = false, method)
        }
        if (authFailed) throw AdoException("Azure DevOps rejected the Azure CLI token (HTTP $status). Check which account `az` is logged in as for this org.", 401)
        if (status !in 200..299) throw AdoException(errorMessage(status, String(response.body(), UTF_8)), status)
        return response.body()
    }

    private fun errorMessage(status: Int, body: String): String {
        val message = runCatching { json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
        return when {
            status == 403 -> "Access denied (HTTP 403)${message?.let { ": $it" }.orEmpty()}. The account may lack Code (Read & write) permission."
            message != null -> "$message (HTTP $status)"
            else -> "Azure DevOps request failed (HTTP $status)"
        }
    }

    /** The org is the first path segment of every dev.azure.com and vssps.dev.azure.com URL. */
    private fun orgOf(url: String): String =
        URLDecoder.decode(URI(url).rawPath.split('/').first { it.isNotEmpty() }, UTF_8)

    private fun withApiVersion(url: String, apiVersion: String?): String =
        if (apiVersion == null) url else url + (if ('?' in url) "&" else "?") + "api-version=$apiVersion"

    companion object {
        private const val API_VERSION = "7.1"
        private const val IDENTITY_HOST = "https://vssps.dev.azure.com"
        // encodeDefaults so values like `offset = 1` are always sent; explicitNulls = false omits unset optional fields.
        val json = Json {
            ignoreUnknownKeys = true
            // ADO sometimes sends explicit nulls (e.g. `item.path` of deleted files); use defaults instead of failing.
            coerceInputValues = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun getInstance(): AdoClient = service()
    }
}
