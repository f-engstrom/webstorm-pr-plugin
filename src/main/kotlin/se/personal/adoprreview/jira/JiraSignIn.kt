package se.personal.adoprreview.jira

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.personal.adoprreview.state.AdoSettings
import se.personal.adoprreview.toolwindow.JiraLoginDialog

/** A checked Jira login for one Azure DevOps org's Jira site. */
data class JiraSession(val site: String, val login: JiraAuth.Login, val me: JiraUser)

object JiraSignIn {

    /** The saved login for [org]'s Jira site, or null when there is no site or no saved token. Never prompts. */
    suspend fun saved(org: String): Pair<String, JiraAuth.Login>? {
        val site = AdoSettings.getInstance().jiraSite(org)?.takeIf { it.isNotBlank() } ?: return null
        val login = withContext(Dispatchers.IO) { JiraAuth.get(site) } ?: return null
        return site to login
    }

    /**
     * A working login for [org]: the saved one if Jira accepts it, else asks until it works or the user cancels
     * (null). A login that works is saved, and so is the site for [org].
     */
    suspend fun session(project: Project, org: String): JiraSession? {
        val client = JiraClient.getInstance()
        val settings = AdoSettings.getInstance()
        var site = settings.jiraSite(org)
        var login = site?.let { withContext(Dispatchers.IO) { JiraAuth.get(it) } }
        var error: String? = null

        while (true) {
            if (site != null && login != null) {
                val me = try {
                    withBackgroundProgress(project, "Signing in to Jira") { client.myself(site!!, login!!) }
                } catch (e: JiraException) {
                    error = e.message
                    null
                }
                if (me != null) {
                    withContext(Dispatchers.IO) { JiraAuth.set(site!!, login!!) }
                    settings.setJiraSite(org, site!!)
                    return JiraSession(site!!, login!!, me)
                }
            }
            val dialog = withContext(Dispatchers.EDT) {
                JiraLoginDialog(project, site, login?.email, error).takeIf { it.showAndGet() }
            } ?: return null
            site = dialog.site
            login = dialog.login
        }
    }
}
