package se.personal.adoprreview.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import se.personal.adoprreview.api.FakeTransport.Companion.ok
import se.personal.adoprreview.api.FakeTransport.Companion.status
import se.personal.adoprreview.repo.AdoRemote
import java.io.IOException

class AdoClientTest {

    private class FakeAuth : AdoAuth {
        var generation = 1
        val invalidated = mutableListOf<String>()
        val asked = mutableListOf<String>()
        override suspend fun authHeader(org: String): String { asked += org; return "Bearer t$generation" }
        override suspend fun invalidate(org: String) { invalidated += org; generation++ }
    }

    private val remote = AdoRemote("my org", "proj", "repo")
    private val auth = FakeAuth()

    private fun client(vararg replies: Any) = FakeTransport(*replies).let { it to AdoClient(it, auth) }

    private suspend fun expectAdoError(block: suspend () -> Unit): AdoException {
        try {
            block()
        } catch (e: AdoException) {
            return e
        }
        fail("expected AdoException")
        error("unreachable")
    }

    // ---- Auth ----

    @Test
    fun `a rejected token is refreshed and the request retried once`() = runBlocking {
        val (transport, ado) = client(status(401), ok("""{"defaultBranch": "refs/heads/develop"}"""))
        assertEquals("refs/heads/develop", ado.repository(remote).defaultBranch)
        assertEquals(listOf("my org"), auth.invalidated)
        assertEquals(listOf("Bearer t1", "Bearer t2"), transport.calls.map { it.headers["Authorization"] })
    }

    @Test
    fun `a sign-in page (203 or redirect) counts as a rejected token`() = runBlocking {
        for (code in listOf(203, 302)) {
            val (transport, ado) = client(status(code), ok("""{"value": []}"""))
            ado.threads(remote, 1)
            assertEquals(2, transport.calls.size)
        }
        assertEquals(2, auth.invalidated.size)
    }

    @Test
    fun `rejected twice gives an auth error, without a third try`() = runBlocking {
        val (transport, ado) = client(status(401), status(401))
        val error = expectAdoError { ado.threads(remote, 1) }
        assertTrue(error.isAuthError)
        assertTrue(error.message!!.contains("Azure CLI token"))
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun `the org comes from the URL path, also on the identity host`() = runBlocking {
        val (_, ado) = client(ok("""{"value": [{"id": "a", "providerDisplayName": "Ada"}]}"""))
        assertEquals("Ada", ado.identities("my org", listOf("a")).single().displayName)
        assertEquals(listOf("my org"), auth.asked)
    }

    // ---- Errors ----

    @Test
    fun `403 explains the missing permission and keeps ADO's message`() = runBlocking {
        val (_, ado) = client(status(403, """{"message": "TF401027: You need Git 'PullRequestContribute'."}"""))
        val error = expectAdoError { ado.vote(remote, 1, "me", se.personal.adoprreview.api.dto.Vote.APPROVED) }
        assertEquals(403, error.status)
        assertTrue(error.message!!.startsWith("Access denied (HTTP 403): TF401027"))
        assertTrue(error.message!!.contains("Code (Read & write)"))
        assertTrue(auth.invalidated.isEmpty())
    }

    @Test
    fun `other failures show ADO's message, or a generic one`() = runBlocking {
        val (_, ado) = client(status(400, """{"message": "Bad branch"}"""), status(500, "<html>"))
        assertEquals("Bad branch (HTTP 400)", expectAdoError { ado.threads(remote, 1) }.message)
        assertEquals("Azure DevOps request failed (HTTP 500)", expectAdoError { ado.threads(remote, 1) }.message)
    }

    @Test
    fun `network failures are recorded and rethrown`() = runBlocking {
        val (_, ado) = client(IOException("offline"))
        try {
            ado.threads(remote, 1)
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(1, ado.stats.failedRequests)
        assertEquals("offline", ado.stats.lastFailure!!.message)
        assertNull(ado.stats.lastFailure!!.status)
        assertEquals("/my%20org/proj/_apis/git/repositories/repo/pullrequests/1/threads".let { java.net.URI(it).path }, ado.stats.lastFailure!!.path)
    }

    // ---- Requests ----

    @Test
    fun `requests carry api-version, except connectionData`() = runBlocking {
        val (transport, ado) = client(ok("""{"value": []}"""), ok("""{"authenticatedUser": {"id": "me"}}"""))
        ado.threads(remote, 7)
        ado.me("my org")
        assertTrue(transport.calls[0].url.endsWith("/pullrequests/7/threads?api-version=7.1"))
        assertTrue("api-version" !in transport.calls[1].url)
    }

    @Test
    fun `changes are paged until nextTop is 0, without folders`() = runBlocking {
        val (transport, ado) = client(
            ok("""{"changeEntries": [{"item": {"path": "/a.ts"}}, {"item": {"path": "/src", "isFolder": true}}], "nextSkip": 2000, "nextTop": 2000}"""),
            ok("""{"changeEntries": [{"item": {"path": "/b.ts"}}, {"item": {"path": "/t", "gitObjectType": "tree"}}], "nextTop": 0}"""),
        )
        assertEquals(listOf("/a.ts", "/b.ts"), ado.changes(remote, 1, iterationId = 3, compareTo = 0).map { it.path })
        assertTrue(transport.calls[0].url.contains("\$skip=0"))
        assertTrue(transport.calls[1].url.contains("\$skip=2000"))
    }

    @Test
    fun `liking posts a JSON body, so java net http sends a Content-Length`() = runBlocking {
        val (transport, ado) = client(ok(), ok())
        ado.setLiked(remote, 1, 2, 3, liked = true)
        ado.setLiked(remote, 1, 2, 3, liked = false)
        val (like, unlike) = transport.calls
        assertEquals("POST", like.method)
        assertEquals("{}", like.bodyText)
        assertEquals("application/json", like.headers["Content-Type"])
        assertEquals("DELETE", unlike.method)
        assertNull(unlike.body)
    }

    @Test
    fun `writes send JSON with defaults included`() = runBlocking {
        val (transport, ado) = client(ok("""{"pullRequestId": 9, "title": "T"}"""))
        val created = ado.createPullRequest(remote, se.personal.adoprreview.api.dto.NewPullRequest("refs/heads/a", "refs/heads/b", "T", "", isDraft = true))
        assertEquals(9, created.pullRequestId)
        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertTrue(call.bodyText!!.contains("\"isDraft\":true"))
    }

    @Test
    fun `the source branch filter is URL-encoded`() = runBlocking {
        val (transport, ado) = client(ok("""{"value": []}"""))
        assertNull(ado.activePullRequestFrom(remote, "feature/ABC-1"))
        assertTrue(transport.calls.single().url.contains("sourceRefName=refs%2Fheads%2Ffeature%2FABC-1"))
    }
}
