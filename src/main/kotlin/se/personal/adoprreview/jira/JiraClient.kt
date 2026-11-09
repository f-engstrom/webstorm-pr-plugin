package se.personal.adoprreview.jira

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.personal.adoprreview.api.HttpCall
import se.personal.adoprreview.api.HttpReply
import se.personal.adoprreview.api.HttpTransport
import se.personal.adoprreview.api.JavaHttpTransport
import java.io.IOException
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * Where API calls for a site go, once known: the Atlassian API gateway (`api.atlassian.com/ex/jira/{cloudId}`),
     * which scoped API tokens require, or the site itself, for classic tokens the gateway turns away.
     */
    private val apiBases = ConcurrentHashMap<String, String>()

    /** Which API base [site] uses, for the Status tab; null until the first request. */
    fun apiBaseOf(site: String): String? = apiBases[JiraAuth.normalize(site)]

    /**
     * Sends [path] for [site]. The first request per site looks up its cloud ID and tries the gateway; if the gateway
     * rejects the token (401), the same request goes to the site, and whichever answered is used from then on.
     */
    private suspend fun send(
        site: String,
        login: JiraAuth.Login,
        path: String,
        method: String = "GET",
        /** JSON. */
        body: String? = null,
    ): String {
        val siteBase = JiraAuth.normalize(site)
        apiBases[siteBase]?.let { return result(exchange(it, login, path, method, body)) }

        LOG.info("Jira: first request for $siteBase as ${login.email} (token ${describe(login.token)}); looking up the cloud ID")
        val gateway = cloudId(siteBase)?.let { "$GATEWAY/$it" }
        if (gateway == null) {
            LOG.info("Jira: no cloud ID for $siteBase, calling the site directly")
            val reply = exchange(siteBase, login, path, method, body)
            if (reply.status != 401) apiBases[siteBase] = siteBase
            return result(reply)
        }
        val reply = exchange(gateway, login, path, method, body)
        if (reply.status != 401) {
            LOG.info("Jira: using the API gateway $gateway for $siteBase")
            apiBases[siteBase] = gateway
            return result(reply)
        }
        // A classic (unscoped) token may only work on the site itself.
        LOG.info("Jira: the gateway rejected the token (401), retrying on the site $siteBase")
        val direct = exchange(siteBase, login, path, method, body)
        if (direct.status != 401) {
            LOG.info("Jira: using the site $siteBase directly")
            apiBases[siteBase] = siteBase
            return result(direct)
        }
        LOG.info("Jira: both the gateway and the site rejected the token; reporting the gateway's answer")
        return result(reply)
    }

    /** The site's cloud ID from its public `/_edge/tenant_info`, or null if it doesn't say (e.g. not Jira Cloud). */
    private suspend fun cloudId(siteBase: String): String? {
        val reply = call(siteBase, HttpCall("GET", "$siteBase/_edge/tenant_info", mapOf("Accept" to "application/json"), timeout = TIMEOUT))
        if (reply.status !in 200..299) return null
        val id = runCatching { json.parseToJsonElement(reply.text).jsonObject["cloudId"]?.jsonPrimitive?.content }
            .getOrNull()?.takeIf { it.isNotBlank() }
        LOG.info("Jira: cloud ID for $siteBase is ${id ?: "missing (body: ${snippet(reply.text)})"}")
        return id
    }

    private suspend fun exchange(base: String, login: JiraAuth.Login, path: String, method: String, body: String?): HttpReply {
        val auth = Base64.getEncoder().encodeToString("${login.email}:${login.token}".toByteArray(UTF_8))
        val headers = buildMap {
            put("Authorization", "Basic $auth")
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
        }
        return call(base, HttpCall(method, base + path, headers, body?.toByteArray(UTF_8), TIMEOUT))
    }

    private suspend fun call(base: String, call: HttpCall): HttpReply = try {
        transport.send(call).also { reply ->
            if (reply.status in 200..299) LOG.info("Jira: ${call.method} ${call.url} -> ${reply.status}")
            else LOG.info("Jira: ${call.method} ${call.url} -> ${reply.status}: ${snippet(reply.text)}")
        }
    } catch (e: IOException) {
        LOG.info("Jira: ${call.method} ${call.url} failed", e)
        fail("Couldn't reach $base: ${e.message ?: e.javaClass.simpleName}", null)
    } catch (e: IllegalArgumentException) {
        fail("Not a valid Jira site: $base", null)
    } catch (e: URISyntaxException) {
        fail("Not a valid Jira site: $base", null)
    }

    private fun result(reply: HttpReply): String {
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
            // Jira's own errors, or the gateway's `{"code": 401, "message": "Unauthorized; scope does not match"}`.
            (obj["errorMessages"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty() +
                obj["errors"]?.jsonObject?.values?.map { it.jsonPrimitive.content }.orEmpty() +
                listOfNotNull(obj["message"]?.jsonPrimitive?.content))
                .joinToString("; ").takeIf { it.isNotBlank() }
        }.getOrNull()
        return when (status) {
            401 -> "Jira rejected the email or API token (HTTP 401${detail?.let { ": $it" }.orEmpty()}). " +
                "A scoped token needs every scope listed in Help → Starting a Jira ticket."
            in 300..399 -> "Jira redirected the request (HTTP $status). Check the site URL."
            else -> "${detail ?: "Jira request failed"} (HTTP $status)"
        }
    }

    companion object {
        private val LOG = Logger.getInstance(JiraClient::class.java)

        /** The token's shape for the log, never the token itself. */
        private fun describe(token: String): String =
            "${token.length} chars${if (token != token.trim()) ", with surrounding whitespace!" else ""}"

        private fun snippet(text: String): String = text.replace(Regex("\\s+"), " ").take(500).ifBlank { "(empty body)" }

        private val FIELDS = listOf("summary", "issuetype", "status", "assignee")
        private const val GATEWAY = "https://api.atlassian.com/ex/jira"
        private val TIMEOUT: Duration = Duration.ofSeconds(30)

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        fun getInstance(): JiraClient = service<JiraClientHolder>().client
    }
}
