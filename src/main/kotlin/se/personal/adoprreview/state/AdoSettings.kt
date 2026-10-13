package se.personal.adoprreview.state

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import se.personal.adoprreview.api.PrFilter

@Service
@State(name = "AdoPrReviewSettings", storages = [Storage("adoPrReview.xml")])
class AdoSettings : SimplePersistentStateComponent<AdoSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        var listFilter by enum(PrFilter.REVIEWER)

        /** Jira site per ADO org (lower case), e.g. `https://your-company.atlassian.net`, for ticket links in new PRs. */
        var jiraSites by map<String, String>()

        /** Last target branch per repo ("org/project/repo"), the default for the next PR. */
        var lastTargets by map<String, String>()

        var createAsDraft by property(false)

        var backgroundNotifications by property(true)

        var allFilesSettingsInitialized by property(false)

        /** Whether the all-files view's own combined-diff settings were set to collapse unchanged code once. */
        var allFilesCollapseApplied by property(false)
    }

    var listFilter: PrFilter
        get() = state.listFilter
        set(value) {
            state.listFilter = value
        }

    fun jiraSite(org: String): String? = state.jiraSites[org.lowercase()]

    fun setJiraSite(org: String, site: String) {
        if (site.isBlank()) state.jiraSites.remove(org.lowercase()) else state.jiraSites[org.lowercase()] = site.trim().trimEnd('/')
        state.intIncrementModificationCount()
    }

    fun lastTarget(repoKey: String): String? = state.lastTargets[repoKey]

    fun setLastTarget(repoKey: String, branch: String) {
        state.lastTargets[repoKey] = branch
        state.intIncrementModificationCount()
    }

    var createAsDraft: Boolean
        get() = state.createAsDraft
        set(value) {
            state.createAsDraft = value
        }

    var allFilesSettingsInitialized: Boolean
        get() = state.allFilesSettingsInitialized
        set(value) {
            state.allFilesSettingsInitialized = value
        }

    var allFilesCollapseApplied: Boolean
        get() = state.allFilesCollapseApplied
        set(value) {
            state.allFilesCollapseApplied = value
        }

    var backgroundNotifications: Boolean
        get() = state.backgroundNotifications
        set(value) {
            state.backgroundNotifications = value
        }

    companion object {
        fun getInstance(): AdoSettings = service()
    }
}
