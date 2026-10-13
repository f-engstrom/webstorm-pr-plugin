package se.personal.adoprreview.repo

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/** An Azure DevOps Git repository on dev.azure.com, parsed from a Git remote URL. */
data class AdoRemote(val org: String, val project: String, val repo: String) {

    /** `https://dev.azure.com/{org}/{project}`, path segments encoded. */
    val projectUrl: String get() = "$HOST/${encode(org)}/${encode(project)}"

    val apiUrl: String get() = "$projectUrl/_apis/git/repositories/${encode(repo)}"

    fun webUrl(prId: Int): String = "$projectUrl/_git/${encode(repo)}/pullrequest/$prId"

    companion object {
        const val HOST = "https://dev.azure.com"

        private val HTTPS = Regex("""^https://(?:[^@/]+@)?dev\.azure\.com/([^/]+)/([^/]+)/_git/([^/?#]+?)/?$""")
        // Any SSH host is accepted so ~/.ssh/config aliases for ssh.dev.azure.com work (one alias per org). The
        // `v3/{org}/{project}/{repo}` path is specific to Azure DevOps and carries the real org.
        private val SSH = Regex("""^(?:ssh://)?[^@/\s]+@[^:/\s]+(?::v3|/v3)/([^/]+)/([^/]+)/([^/?#]+?)/?$""")

        fun parse(url: String): AdoRemote? {
            val match = HTTPS.matchEntire(url.trim()) ?: SSH.matchEntire(url.trim()) ?: return null
            val (org, project, repo) = match.destructured.toList().map(::decode)
            return AdoRemote(org, project, repo)
        }

        // URLDecoder treats '+' as a space, which is wrong for path segments.
        private fun decode(segment: String): String = URLDecoder.decode(segment.replace("+", "%2B"), UTF_8)

        fun encode(segment: String): String = URLEncoder.encode(segment, UTF_8).replace("+", "%20")
    }
}
