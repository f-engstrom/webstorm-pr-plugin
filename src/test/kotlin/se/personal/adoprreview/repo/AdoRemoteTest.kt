package se.personal.adoprreview.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdoRemoteTest {

    private val expected = AdoRemote("contoso", "Web Shop", "frontend")

    @Test
    fun `https`() {
        assertEquals(expected, AdoRemote.parse("https://dev.azure.com/contoso/Web%20Shop/_git/frontend"))
    }

    @Test
    fun `https with user`() {
        assertEquals(expected, AdoRemote.parse("https://contoso@dev.azure.com/contoso/Web%20Shop/_git/frontend"))
    }

    @Test
    fun `https with trailing slash`() {
        assertEquals(expected, AdoRemote.parse("https://dev.azure.com/contoso/Web%20Shop/_git/frontend/"))
    }

    @Test
    fun `ssh`() {
        assertEquals(expected, AdoRemote.parse("git@ssh.dev.azure.com:v3/contoso/Web%20Shop/frontend"))
    }

    @Test
    fun `ssh url form`() {
        assertEquals(expected, AdoRemote.parse("ssh://git@ssh.dev.azure.com/v3/contoso/Web%20Shop/frontend"))
    }

    @Test
    fun `ssh with host alias`() {
        assertEquals(expected, AdoRemote.parse("git@contoso-ado:v3/contoso/Web%20Shop/frontend"))
    }

    @Test
    fun `plus is not decoded as space`() {
        assertEquals("a+b", AdoRemote.parse("https://dev.azure.com/contoso/proj/_git/a+b")?.repo)
    }

    @Test
    fun `other hosts are ignored`() {
        assertNull(AdoRemote.parse("https://contoso.visualstudio.com/Web%20Shop/_git/frontend"))
        assertNull(AdoRemote.parse("git@github.com:contoso/frontend.git"))
        assertNull(AdoRemote.parse("https://dev.azure.com/contoso/Web%20Shop/_git"))
    }

    @Test
    fun `urls are re-encoded`() {
        assertEquals("https://dev.azure.com/contoso/Web%20Shop/_git/frontend/pullrequest/42", expected.webUrl(42))
        assertEquals("https://dev.azure.com/contoso/Web%20Shop/_apis/git/repositories/frontend", expected.apiUrl)
    }
}
