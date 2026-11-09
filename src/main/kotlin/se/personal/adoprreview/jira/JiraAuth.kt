package se.personal.adoprreview.jira

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/** Jira email + API token per site, in the IDE's PasswordSafe (the macOS Keychain). Blocking; call off the EDT. */
object JiraAuth {

    data class Login(val email: String, val token: String)

    private fun attributes(site: String) =
        CredentialAttributes(generateServiceName("ADO PR Review Jira", normalize(site)))

    fun get(site: String): Login? {
        val stored = PasswordSafe.instance.get(attributes(site)) ?: return null
        val email = stored.userName?.takeIf { it.isNotBlank() } ?: return null
        val token = stored.getPasswordAsString()?.takeIf { it.isNotBlank() } ?: return null
        return Login(email, token)
    }

    fun set(site: String, login: Login) {
        PasswordSafe.instance.set(attributes(site), Credentials(login.email, login.token))
    }

    fun clear(site: String) {
        PasswordSafe.instance.set(attributes(site), null)
    }

    /** Trims, drops a trailing slash and adds `https://` when no scheme is given. */
    fun normalize(site: String): String = site.trim().trimEnd('/').let {
        if (it.isEmpty() || it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
    }
}
