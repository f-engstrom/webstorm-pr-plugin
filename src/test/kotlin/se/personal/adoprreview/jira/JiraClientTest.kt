package se.personal.adoprreview.jira

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    private suspend fun expectJiraError(block: suspend () -> Unit): JiraException {
        try {
            block()
        } catch (e: JiraException) {
            return e
        }
        fail("expected JiraException")
        error("unreachable")
    }

    @Test
    fun `requests use Basic auth against the normalised site`() = runBlocking {
        val transport = FakeTransport(ok("""{"accountId": "a1", "displayName": "Ada"}"""))
        val jira = JiraClient(transport)
        assertEquals("a1", jira.myself(site, login).accountId)
        val call = transport.calls.single()
        assertEquals("https://acme.atlassian.net/rest/api/3/myself", call.url)
        val expected = "Basic " + Base64.getEncoder().encodeToString("ada@example.com:secret".toByteArray())
        assertEquals(expected, call.headers["Authorization"])
        assertEquals("Ada", jira.lastIdentity!!.second.displayName)
    }

    @Test
    fun `transitions post JSON`() = runBlocking {
        val transport = FakeTransport(ok(""))
        JiraClient(transport).transition(site, login, "ABC-1", "31")
        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertEquals("""{"transition":{"id":"31"}}""", call.bodyText)
        assertEquals("application/json", call.headers["Content-Type"])
    }

    @Test
    fun `a rejected token says so`() = runBlocking {
        val jira = JiraClient(FakeTransport(status(401)))
        val error = expectJiraError { jira.myself(site, login) }
        assertTrue(error.isAuthError)
        assertEquals("Jira rejected the email or API token (HTTP 401).", error.message)
        assertEquals(error.message, jira.lastError!!.second)
    }

    @Test
    fun `Jira's error messages are joined`() = runBlocking {
        val body = """{"errorMessages": ["Issue does not exist"], "errors": {"assignee": "Not assignable"}}"""
        val error = expectJiraError { JiraClient(FakeTransport(status(400, body))).assign(site, login, "ABC-1", "a1") }
        assertEquals("Issue does not exist; Not assignable (HTTP 400)", error.message)
    }

    @Test
    fun `a redirect points at the site URL`() = runBlocking {
        val error = expectJiraError { JiraClient(FakeTransport(status(302))).myself(site, login) }
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
