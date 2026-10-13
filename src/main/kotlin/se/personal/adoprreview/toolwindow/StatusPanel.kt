package se.personal.adoprreview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.launch
import se.personal.adoprreview.api.AdoClient
import se.personal.adoprreview.auth.AzCliToken
import se.personal.adoprreview.repo.AdoRepoResolver
import se.personal.adoprreview.state.AdoSettings
import se.personal.adoprreview.state.LastSeenService
import se.personal.adoprreview.state.PrReviewService
import se.personal.adoprreview.state.PrWatcher
import se.personal.adoprreview.state.SystemIdle
import java.awt.datatransfer.StringSelection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Icon
import javax.swing.Timer
import javax.swing.text.DefaultCaret
import kotlin.time.toJavaDuration

/** The Status tab: what the plugin is doing and what it depends on. Refreshes every second while visible. */
class StatusPanel(private val project: Project, parentDisposable: Disposable) : SimpleToolWindowPanel(true, true) {

    private enum class Level { OK, WARN, ERROR, INFO }

    private class Row(val label: String, val value: String, val level: Level = Level.INFO)
    private class Section(val title: String, val rows: List<Row>)

    private val pane = Markdown.createPane().apply {
        border = JBUI.Borders.empty(8, 12)
        // Keep the scroll position when the text is replaced every second.
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
    }
    private var lastHtml = ""

    init {
        val actions = DefaultActionGroup(
            action("Poll Now", "Check for PR activity right away", AllIcons.Actions.Refresh) {
                PrWatcher.getInstance(project).pollNow()
            },
            action("Refresh Token", "Drop cached tokens and ask az again (e.g. after az login as another account)", AllIcons.Actions.ForceRefresh) {
                val service = PrReviewService.getInstance(project)
                service.scope.launch {
                    AzCliToken.getInstance().invalidateAll()
                    // Fetch one right away so the account shows up here.
                    AdoRepoResolver.getInstance(project).resolve()?.let { runCatching { AzCliToken.getInstance().authHeader(it.remote.org) } }
                }
            },
            action("Copy Diagnostics", "Copy this page as text", AllIcons.Actions.Copy) {
                CopyPasteManager.getInstance().setContents(StringSelection(asText(collect())))
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("AdoPrStatus", actions, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(pane, true))

        val timer = Timer(1000) { if (isShowing) update() }
        timer.start()
        Disposer.register(parentDisposable) { timer.stop() }
        update()
    }

    private fun update() {
        val html = asHtml(collect())
        if (html != lastHtml) {
            lastHtml = html
            pane.text = html
        }
    }

    private fun collect(): List<Section> {
        val token = AzCliToken.getInstance()
        val service = PrReviewService.getInstance(project)
        val watcher = PrWatcher.getInstance(project)
        val stats = AdoClient.getInstance().stats
        val repo = AdoRepoResolver.getInstance(project).resolve()

        val auth = buildList {
            add(token.azPath()?.let { Row("Azure CLI", it, Level.OK) } ?: Row("Azure CLI", "not found (brew install azure-cli)", Level.ERROR))
            repo?.let { add(Row("Login used for ${it.remote.org}", token.loginDirFor(it.remote.org))) }
            val logins = token.logins()
            if (logins.isEmpty()) add(Row("Token", "none fetched yet"))
            logins.forEach { login ->
                val valid = login.expiresAt.isAfter(Instant.now())
                add(Row(
                    login.configDir,
                    "${login.account ?: "unknown account"} · token ${if (valid) "valid until ${time(login.expiresAt)} (${until(login.expiresAt)})" else "expired"}",
                    if (valid) Level.OK else Level.WARN,
                ))
            }
            add(token.lastError?.let { (at, message) -> Row("Last az error", "${ago(at)}: $message", Level.ERROR) } ?: Row("Last az error", "none", Level.OK))
        }

        val identity = service.knownIdentities().map { (org, id) -> Row(org, id, Level.OK) }
            .ifEmpty { listOf(Row("Azure DevOps user", "not resolved yet")) }

        val repository = if (repo == null) {
            listOf(Row("Remote", "no dev.azure.com remote in this project", Level.ERROR))
        } else {
            val git = repo.gitRepository
            listOf(
                Row("Remote", "${repo.gitRemote.name} → ${repo.gitRemote.firstUrl.orEmpty()}", Level.OK),
                Row("Org / project / repo", "${repo.remote.org} / ${repo.remote.project} / ${repo.remote.repo}"),
                Row("Branch", "${git.currentBranchName ?: "detached"} @ ${git.currentRevision?.take(8) ?: "?"}"),
            )
        }

        val polling = buildList {
            val enabled = AdoSettings.getInstance().backgroundNotifications
            add(Row("Notifications", if (enabled) "on" else "off (⋮ menu → Notify About PR Activity)", if (enabled) Level.OK else Level.WARN))
            val phase = watcher.phase
            val next = watcher.nextPoll?.takeIf { phase == PrWatcher.Phase.WAITING }
                ?.let { mark -> (-mark.elapsedNow()).takeIf { it.isPositive() }?.let { " · next check in ${duration(it.toJavaDuration())}" } }
            add(Row("State", phase.label + next.orEmpty(), if (phase == PrWatcher.Phase.PAUSED_IDLE || phase == PrWatcher.Phase.DISABLED) Level.WARN else Level.OK))
            add(Row(
                "Schedule",
                "every ${duration(watcher.interval.toJavaDuration())}; pauses after ${duration(watcher.idlePause.toJavaDuration())} idle " +
                    "(idle now: ${duration(SystemIdle.idleTime().toJavaDuration())})",
            ))
            add(watcher.lastPoll?.let { poll ->
                val result = poll.error?.let { "failed: $it" } ?: "ok, ${poll.watchedPrs} PRs watched"
                Row("Last check", "${ago(poll.at)} · took ${duration(poll.duration.toJavaDuration())} · $result", if (poll.error == null) Level.OK else Level.ERROR)
            } ?: Row("Last check", "not yet"))
            add(Row("Baseline", if (watcher.hasBaseline) "recorded (changes since then are reported)" else "not yet (first check only records state)"))
            add(Row("Unseen activity", "${watcher.unseenCount} PR(s)"))
        }

        val network = listOf(
            Row("Requests", "${stats.totalRequests} this session · ${stats.requestsInLast(5)} in the last 5 min"),
            Row("Failed", "${stats.failedRequests}", if (stats.failedRequests == 0) Level.OK else Level.WARN),
            stats.lastFailure?.let { f ->
                Row("Last failure", "${ago(f.at)}: ${f.method} ${f.path} · ${f.message}", Level.ERROR)
            } ?: Row("Last failure", "none", Level.OK),
        )

        val caches = listOf(
            Row("Names for @mentions", "${service.cachedNameCount()} cached"),
            Row("PRs with threads loaded", "${service.loadedThreadPrCount()}"),
            Row("Seen-state snapshots", "${LastSeenService.getInstance(project).size} (in .idea/workspace.xml)"),
        )

        val app = ApplicationInfo.getInstance()
        val build = listOf(
            Row("Plugin", PluginManagerCore.getPlugin(PluginId.getId("se.personal.adoprreview"))?.version ?: "?"),
            Row("IDE", "${app.fullApplicationName} (${app.build.asString()})"),
            Row("Runtime", "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}"),
        )

        return listOf(
            Section("Auth", auth), Section("Identity", identity), Section("Repository", repository),
            Section("Polling", polling), Section("Network", network), Section("Caches", caches), Section("Build", build),
        )
    }

    private fun asHtml(sections: List<Section>): String = buildString {
        for (section in sections) {
            append("<h3>").append(StringUtil.escapeXmlEntities(section.title)).append("</h3><table cellpadding='2'>")
            for (row in section.rows) {
                val color = when (row.level) {
                    Level.OK -> OK
                    Level.WARN -> WARN
                    Level.ERROR -> ERROR
                    Level.INFO -> null
                }
                append("<tr><td valign='top' nowrap><b>").append(StringUtil.escapeXmlEntities(row.label)).append("</b></td><td>")
                val value = StringUtil.escapeXmlEntities(row.value)
                if (color != null) append("<font color='#").append(ColorUtil.toHex(color)).append("'>").append(value).append("</font>") else append(value)
                append("</td></tr>")
            }
            append("</table>")
        }
    }

    private fun asText(sections: List<Section>): String = sections.joinToString("\n\n") { section ->
        "## ${section.title}\n" + section.rows.joinToString("\n") { "${it.label}: ${it.value}" }
    }

    private fun action(text: String, description: String, icon: Icon, perform: () -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private companion object {
        val OK = JBColor(0x2E7D32, 0x6CBF6C)
        val WARN = JBColor(0xB26A00, 0xE0A040)
        val ERROR = JBColor(0xC62828, 0xE06C6C)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        fun time(at: Instant): String = TIME.format(at)
        fun ago(at: Instant): String = "${time(at)} (${duration(Duration.between(at, Instant.now()))} ago)"
        fun until(at: Instant): String = "in ${duration(Duration.between(Instant.now(), at))}"

        fun duration(d: Duration): String = when {
            d.toMillis() < 1000 -> "${d.toMillis()} ms"
            d.seconds < 60 -> "${d.seconds} s"
            d.toMinutes() < 60 -> "${d.toMinutes()} min" + (d.seconds % 60).takeIf { it > 0 && d.toMinutes() < 10 }?.let { " $it s" }.orEmpty()
            else -> "${d.toHours()} h ${d.toMinutesPart()} min"
        }
    }
}
