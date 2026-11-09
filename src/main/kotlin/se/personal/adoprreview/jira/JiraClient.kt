package se.personal.adoprreview.jira

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.personal.adoprreview.api.HttpCall
import se.personal.adoprreview.api.HttpTransport
import se.personal.adoprreview.api.JavaHttpTransport
import java.io.IOException
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.Base64

class JiraException(message: String, val status: Int? = null) : Exception(message) {
    val isAuthError: Boolean get() = status == 401 || status == 403
}

@Service
internal class JiraClientHolder {
    val client = JiraClient(JavaHttpTransport())
}

/** Jira Cloud REST v3 with Basic auth (email + API token from [JiraAuth]). */
class JiraClient(private val transport: HttpTransport) {

    /** For the Status tab. */
    @Volatile
    var lastIdentity: Pair<String, JiraUser>? = null
        private set

    @Volatile
    var lastError: Pair<Instant, String>? = null
        private set

    /** Who [login] is on [site]; also how a newly entered token is checked. */
    suspend fun myself(site: String, login: JiraAuth.Login): JiraUser =
        get<JiraUser>(site, login, "/rest/api/3/myself").also { lastIdentity = site to it }

    /** Unresolved tickets assigned to the token's user, most recently updated first. */
    suspend fun myOpenIssues(site: String, login: JiraAuth.Login): List<JiraIssue> {
        val body = json.encodeToString(JqlSearch(TicketBranches.MY_OPEN_JQL, FIELDS, maxResults = 100))
        return json.decodeFromString<JqlSearchResult>(send(site, login, "/rest/api/3/search/jql", "POST", body)).issues
    }

    suspend fun issue(site: String, login: JiraAuth.Login, key: String): JiraIssue =
        get(site, login, "/rest/api/3/issue/${URLEncoder.encode(key, UTF_8)}?fields=${FIELDS.joinToString(",")}")

    suspend fun assign(site: String, login: JiraAuth.Login, key: String, accountId: String) {
        val body = json.encodeToString(AssigneeRequest(accountId))
        send(site, login, "/rest/api/3/issue/${URLEncoder.encode(key, UTF_8)}/assignee", "PUT", body)
    }

    suspend fun transitions(site: String, login: JiraAuth.Login, key: String): List<Transition> =
        get<Transitions>(site, login, "/rest/api/3/issue/${URLEncoder.encode(key, UTF_8)}/transitions").transitions

    suspend fun transition(site: String, login: JiraAuth.Login, key: String, transitionId: String) {
        val body = json.encodeToString(TransitionRequest(TransitionId(transitionId)))
        send(site, login, "/rest/api/3/issue/${URLEncoder.encode(key, UTF_8)}/transitions", "POST", body)
    }

    private suspend inline fun <reified T> get(site: String, login: JiraAuth.Login, path: String): T =
        json.decodeFromString(send(site, login, path))

    private suspend fun send(
        site: String,
        login: JiraAuth.Login,
        path: String,
        method: String = "GET",
        /** JSON. */
        body: String? = null,
    ): String {
        val base = JiraAuth.normalize(site)
        val auth = Base64.getEncoder().encodeToString("${login.email}:${login.token}".toByteArray(UTF_8))
        val headers = buildMap {
            put("Authorization", "Basic $auth")
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
        }
        val reply = try {
            transport.send(HttpCall(method, base + path, headers, body?.toByteArray(UTF_8), Duration.ofSeconds(30)))
        } catch (e: IOException) {
            fail("Couldn't reach $base: ${e.message ?: e.javaClass.simpleName}", null)
        } catch (e: IllegalArgumentException) {
            fail("Not a valid Jira site: $base", null)
        } catch (e: URISyntaxException) {
            fail("Not a valid Jira site: $base", null)
        }
        if (reply.status in 200..299) return reply.text
        fail(errorMessage(reply.status, reply.text), reply.status)
    }

    private fun fail(message: String, status: Int?): Nothing {
        lastError = Instant.now() to message
        throw JiraException(message, status)
    }

    private fun errorMessage(status: Int, body: String): String {
        val detail = runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            (obj["errorMessages"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty() +
                obj["errors"]?.jsonObject?.values?.map { it.jsonPrimitive.content }.orEmpty())
                .joinToString("; ").takeIf { it.isNotBlank() }
        }.getOrNull()
        return when (status) {
            401 -> "Jira rejected the email or API token (HTTP 401)."
            in 300..399 -> "Jira redirected the request (HTTP $status). Check the site URL."
            else -> "${detail ?: "Jira request failed"} (HTTP $status)"
        }
    }

    companion object {
        private val FIELDS = listOf("summary", "issuetype", "status", "assignee")

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        fun getInstance(): JiraClient = service<JiraClientHolder>().client
    }
}
