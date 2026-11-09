package se.personal.adoprreview.jira

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import se.personal.adoprreview.api.FakeTransport
import se.personal.adoprreview.api.FakeTransport.Companion.ok
import se.personal.adoprreview.api.FakeTransport.Companion.status
import java.io.IOException
import java.util.Base64

class JiraClientTest {

    private val login = JiraAuth.Login("ada@example.com", "secret")
    private val site = "https://acme.atlassian.net/"
    private val gateway = "https://api.atlassian.com/ex/jira/c-123"
    private val tenant = ok("""{"cloudId": "c-123"}""")
    private val me = ok("""{"accountId": "a1", "displayName": "Ada"}""")

    private suspend fun expectJiraError(block: suspend () -> Unit): JiraException {
        try {
            block()
        } catch (e: JiraException) {
            return e
        }
        fail("expected JiraException")
        error("unreachable")
    }

    // ---- Which API base ----

    @Test
    fun `requests go through the API gateway with the site's cloud ID, with Basic auth`() = runBlocking {
        val transport = FakeTransport(tenant, me)
        val jira = JiraClient(transport)
        assertEquals("a1", jira.myself(site, login).accountId)
        val (lookup, call) = transport.calls
        assertEquals("https://acme.atlassian.net/_edge/tenant_info", lookup.url)
        assertNull("the cloud ID lookup is anonymous", lookup.headers["Authorization"])
        assertEquals("$gateway/rest/api/3/myself", call.url)
        val expected = "Basic " + Base64.getEncoder().encodeToString("ada@example.com:secret".toByteArray())
        assertEquals(expected, call.headers["Authorization"])
        assertEquals(gateway, jira.apiBaseOf(site))
    }

    @Test
    fun `the cloud ID is looked up once per site`() = runBlocking {
        val transport = FakeTransport(tenant, me, ok("""{"transitions": []}"""))
        val jira = JiraClient(transport)
        jira.myself(site, login)
        jira.transitions(site, login, "ABC-1")
        assertEquals(1, transport.calls.count { it.url.endsWith("tenant_info") })
        assertEquals("$gateway/rest/api/3/issue/ABC-1/transitions", transport.calls.last().url)
    }

    @Test
    fun `a classic token the gateway rejects falls back to the site, and stays there`() = runBlocking {
        val transport = FakeTransport(tenant, status(401), me, ok("""{"transitions": []}"""))
        val jira = JiraClient(transport)
        assertEquals("a1", jira.myself(site, login).accountId)
        jira.transitions(site, login, "ABC-1")
        assertEquals(
            listOf("$gateway/rest/api/3/myself", "https://acme.atlassian.net/rest/api/3/myself", "https://acme.atlassian.net/rest/api/3/issue/ABC-1/transitions"),
            transport.calls.drop(1).map { it.url },
        )
        assertEquals("https://acme.atlassian.net", jira.apiBaseOf(site))
    }

    @Test
    fun `a site without a cloud ID is called directly`() = runBlocking {
        val transport = FakeTransport(status(404), me)
        val jira = JiraClient(transport)
        jira.myself("jira.example.com", login)
        assertEquals("https://jira.example.com/rest/api/3/myself", transport.calls.last().url)
    }

    @Test
    fun `rejected on both routes reports the gateway's reason and the scopes hint`() = runBlocking {
        val jira = JiraClient(FakeTransport(tenant, status(401, """{"code": 401, "message": "Unauthorized; scope does not match"}"""), status(401)))
        val error = expectJiraError { jira.myself(site, login) }
        assertTrue(error.isAuthError)
        assertEquals(
            "Jira rejected the email or API token (HTTP 401: Unauthorized; scope does not match). " +
                "A scoped token needs every scope listed in Help → Starting a Jira ticket.",
            error.message,
        )
        assertEquals(error.message, jira.lastError!!.second)
        assertNull("nothing worked, so the next request tries again", jira.apiBaseOf(site))
    }

    // ---- Requests and errors ----

    @Test
    fun `transitions post JSON`() = runBlocking {
        val transport = FakeTransport(tenant, ok(""))
        JiraClient(transport).transition(site, login, "ABC-1", "31")
        val call = transport.calls.last()
        assertEquals("POST", call.method)
        assertEquals("""{"transition":{"id":"31"}}""", call.bodyText)
        assertEquals("application/json", call.headers["Content-Type"])
    }

    @Test
    fun `Jira's error messages are joined`() = runBlocking {
        val body = """{"errorMessages": ["Issue does not exist"], "errors": {"assignee": "Not assignable"}}"""
        val error = expectJiraError { JiraClient(FakeTransport(tenant, status(400, body))).assign(site, login, "ABC-1", "a1") }
        assertEquals("Issue does not exist; Not assignable (HTTP 400)", error.message)
    }

    @Test
    fun `a redirect points at the site URL`() = runBlocking {
        val error = expectJiraError { JiraClient(FakeTransport(tenant, status(302))).myself(site, login) }
        assertTrue(error.message!!.contains("Check the site URL"))
    }

    @Test
    fun `an unreachable or invalid site becomes a Jira error`() = runBlocking {
        val offline = expectJiraError { JiraClient(FakeTransport(IOException("timeout"))).myself(site, login) }
        assertEquals("Couldn't reach https://acme.atlassian.net: timeout", offline.message)
        val invalid = expectJiraError { JiraClient(FakeTransport(IllegalArgumentException("scheme"))).myself("acme", login) }
        assertTrue(invalid.message!!.startsWith("Not a valid Jira site"))
    }
}
