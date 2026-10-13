# Azure DevOps PR Review for WebStorm

A personal WebStorm plugin for reviewing Azure DevOps (`dev.azure.com`) pull requests without leaving the IDE. It covers listing, diffing, commenting, voting, creating PRs and activity notifications.

It's built for one machine and one IDE: **WebStorm 2026.2** on macOS, authenticating with the **Azure CLI**. It uses internal IntelliJ Platform APIs on purpose, so a WebStorm upgrade may need small fixes.

- **User guide:** the **Help** tab in the tool window ([`src/main/resources/help/help.md`](src/main/resources/help/help.md)).
- **Design notes and hard-won lessons:** [`PLAN.md`](PLAN.md).

---

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Build and install](#build-and-install)
- [Signing in](#signing-in)
- [Development](#development)
- [How it works](#how-it-works)
- [Project layout](#project-layout)
- [Azure DevOps REST calls](#azure-devops-rest-calls)
- [Things to know before changing code](#things-to-know-before-changing-code)
- [Troubleshooting](#troubleshooting)

---

## Features

All of this lives in the **ADO PRs** tool window, which has three tabs: **Pull Requests**, **Status** and **Help**.

| Area | What you get |
|---|---|
| **List** | Active PRs for the open repo: *Assigned to me*, *Created by me* or *All active*. Rows show the draft marker, your vote, and **New** / **New commits** / **New activity** badges. |
| **Details** | Title, branches, reviewers and votes, the description rendered as Markdown with `@mentions` resolved to names, and general comments. Buttons for **Vote**, **Publish** (drafts), **Edit…** and **Checkout**. |
| **Files** | The changed-files tree with per-file thread counts and comment rows. **Viewed** marks clear when a file changes. **updated** marks files changed since your last visit. **Compare** picks any update range. |
| **Diff** | The IDE diff, stepped through with F7. Each file loads lazily. Threads appear inline with reply and resolve. Add comments with the gutter **+** or a selection, then **Cmd+Enter**. One tab per PR. |
| **All Files** | Every changed file in one scrolling view (the platform's combined diff), with unchanged code collapsed. Comments work there too. |
| **Local file** | When the PR is checked out and a file matches it exactly, the diff shows your real project file, so TypeScript, ESLint and navigation work. A banner appears and commenting is locked if the file drifts from the PR. |
| **Create PR** | **+** in the list toolbar. Pushes first if needed (checked against the server). The title is the ticket key from the branch (`ABC-123 `, or the branch name if left empty), the description links to the Jira ticket, and the last target branch is remembered. |
| **Activity** | Every 5 minutes, checks for review requests, votes, new comments and replies. Shows a dot on the tool window icon, list badges, and an entry in the Notifications tool window. **Show** goes straight to the comment. Pauses when the computer is idle. |
| **Status** | Live diagnostics: auth and token, identity, repo, polling state, network counts and failures, caches and build. Buttons for **Poll Now**, **Refresh Token** and **Copy Diagnostics**. |

---

## Requirements

- **WebStorm 2026.2.x** in `/Applications/WebStorm.app`. The build compiles against this installation, and its bundled JBR 25 is the JDK.
- **Azure CLI:** `brew install azure-cli`.
- **A Git remote on `dev.azure.com`** in the project you open. HTTPS, SSH and `~/.ssh/config` host aliases all work. See [Repo resolution](#repo-resolution).

There's no separate JDK to install. If WebStorm lives elsewhere, change `platformLocalPath` and `org.gradle.java.installations.paths` in [`gradle.properties`](gradle.properties).

---

## Build and install

Gradle needs a JDK to start. Use the one bundled with WebStorm:

```bash
export JAVA_HOME=/Applications/WebStorm.app/Contents/jbr/Contents/Home
```

Build the plugin zip. This doesn't run the tests; see below for those.

```bash
./gradlew buildPlugin
```

The result is `build/distributions/ado-pr-review-<version>.zip`. It contains a single jar; kotlinx, coroutines and serialization come from the IDE.

Install it in WebStorm:

1. Open **Settings → Plugins → ⚙︎ → Install Plugin from Disk…**
2. Pick the zip from `build/distributions/`.
3. Restart WebStorm.

To update, build again and repeat the install; it replaces the old version. To uninstall, use **Settings → Plugins → Installed → Azure DevOps PR Review → Uninstall**.

Run the unit tests:

```bash
./gradlew test
```

---

## Signing in

The plugin has no login screen and stores no credentials. It runs:

```
az account get-access-token --resource 499b84ac-1321-427f-aa17-267ca6975798
```

The token is cached in memory only and refreshed 60 s before it expires, or after a 401.

```bash
az login
```

**One org needing a different account** (for example a separate work account): give that org its own Azure CLI config directory.

```bash
AZURE_CONFIG_DIR=~/.azure-<org> az login --allow-no-subscriptions
```

If `~/.azure-<org>` exists (org in lower case, as in the remote URL), the plugin uses it for that org. Every other org uses `~/.azure`. The **Status** tab shows which login each org uses and the signed-in account.

---

## Development

### The sandbox IDE

```bash
./gradlew runIde
```

This starts a separate WebStorm with the plugin installed. Its config, caches and logs live in `.intellijPlatform/sandbox/…`; the log is `…/log_runIde/idea.log`. Open a project with an Azure DevOps remote in it.

- **Fast polling:** the sandbox checks for activity every **30 s** instead of every 5 minutes, so notifications are quick to test. This is set only in the `runIde` task, via `-DadoPrReview.pollIntervalSeconds=30` in [`build.gradle.kts`](build.gradle.kts), so the zip you install always uses 5 minutes.
- **Restart after changes:** WebStorm can't hot-reload this plugin. Quit the sandbox and run `runIde` again after a rebuild. Its window shows your real checkout, so **Checkout** and **Create PR** act on real branches and real PRs.

### Inspecting the running sandbox

These commands use tools bundled with WebStorm, so they don't need a separate JDK.

```bash
/Applications/WebStorm.app/Contents/jbr/Contents/Home/bin/jcmd $(pgrep -f sandbox/ado-pr-review | head -1) Thread.print
```

```bash
/Applications/WebStorm.app/Contents/jbr/Contents/Home/bin/jcmd $(pgrep -f sandbox/ado-pr-review | head -1) GC.class_histogram
```

The JBR has no `javap`. To check a platform API's signature, use a small reflection program run with `java Foo.java` against `/Applications/WebStorm.app/Contents/lib/*.jar`.

### Tests

The unit tests cover the logic that's easy to get wrong:

- remote URL parsing
- system-thread filtering
- the last-seen and badge logic
- activity diffing
- PR title and description templates
- mention replacement

The UI is tested by hand in the sandbox.

---

## How it works

### Big picture

```
Git remote ──► AdoRepoResolver ──► AdoRemote(org, project, repo)
                                          │
az CLI ──► AzCliToken ──► AdoClient (java.net.http, kotlinx.serialization) ──► dev.azure.com
                                          │
                       PrReviewService (project service: PR lists, details, threads as StateFlows)
                         │              │                    │
                 PrListPanel     PrDetailsPanel        PrDiffOpener ──► IDE diff / All Files
                 (list, badges)  (overview, files)     PrDiffExtension ──► inlays, gutter +, banners
                         │
                     PrWatcher (background polling) ──► notifications, sidebar dot, list badges
```

- **Services** are light services (`@Service`). Project-level ones get an injected `CoroutineScope`. I/O runs on `Dispatchers.IO`, UI work on `Dispatchers.EDT`.
- **Threads** for a PR are a `StateFlow` in `PrReviewService`. Everything that shows them subscribes to it: the details panel, every open diff, and the All Files blocks. After a reply, resolve or new comment, the service reloads the threads and every view updates. The background watcher also pushes fresh threads into PRs you've opened.

### Repo resolution

`AdoRepoResolver` takes the first Git remote that parses as Azure DevOps, preferring `origin`. `AdoRemote.parse` accepts:

- `https://[user@]dev.azure.com/{org}/{project}/_git/{repo}`
- `git@ssh.dev.azure.com:v3/{org}/{project}/{repo}`
- `git@{alias}:v3/{org}/{project}/{repo}` (a `~/.ssh/config` alias; the org comes from the path)

Legacy `*.visualstudio.com` remotes aren't supported.

### Diffs

`PrDiffOpener` builds one lazy `DiffRequestProducer` per changed file:

- **Content:** file contents come from the Items API. The left side is the base (the merge base, or the base update's source commit), and the right side is the head update's source commit. `DiffContentFactory.createFromBytes` handles highlighting, charsets and binaries.
- **Local file:** if the PR is checked out and the project file's text equals the PR's version exactly, the right side is the real file. `LocalFileSync` then watches it and locks commenting on that side once it drifts.
- **Single diff:** a `SimpleDiffRequestChain` in a `ChainDiffVirtualFile`, one tab per PR. Picking another file switches the tab with `setCurrentRequest`.
- **All Files:** the same producers wrapped as `CombinedBlockProducer`s in the platform's combined diff. Changes over 600 lines become a placeholder.

`PrDiffExtension` (a `DiffExtension`) runs for every viewer showing a PR file. `DiffLayout` maps PR lines (per side) to editor lines for **side-by-side**, **one-sided** (added or deleted files) and **unified** viewers. On top of that:

- **`ThreadInlays`** embeds a `ThreadPanel` under each thread's line, using `EditorEmbeddedComponentManager`.
- **`AddCommentGutter`** shows the hover **+**. It and **Add PR Comment** open a `NewCommentInlay`.
- **New threads** are posted with `threadContext` (lines and offsets on the right or left side) plus `pullRequestThreadContext` (`changeTrackingId`, `iterationContext`), so Azure DevOps keeps them anchored after new pushes.

### Seen state and activity

**`LastSeenService`** stores per-PR snapshots in the project's workspace file:
- the latest update seen and its source commit
- the latest comment by someone else
- viewed files, keyed by path to blob ID

These drive the list badges, the **updated** marks and **Viewed**.

**`PrWatcher`** polls the PRs you review and the ones you authored, plus their threads:
- at most 4 requests at a time
- every 5 minutes
- skipped after 30 minutes without input anywhere on the computer, measured with macOS `CGEventSourceSecondsSinceLastEventType` through the bundled JNA

`Activity` compares states between polls (a pure function, unit-tested) into events: added as reviewer, voted, commented. They become one notification per PR in the `ADO PR Activity` group, which defaults to no popup, plus a dot on the tool window icon (`BadgeIconSupplier`).

### Storage

| What | Where |
|---|---|
| Tokens | Memory only, never written to disk |
| Filter, Jira site per org, last target branch, draft default, notifications on/off | IDE settings: `adoPrReview.xml` |
| Seen state and viewed files | The project's `.idea/workspace.xml` |
| Name lookups for `@mentions`, the activity baseline | Memory |

Network calls go only to `dev.azure.com`, plus `vssps.dev.azure.com/{org}/_apis/identities` to resolve `@mention` names.

---

## Project layout

```
src/main/kotlin/se/personal/adoprreview/
  AdoNotifications.kt        balloons in group "ADO PR Review"
  auth/AzCliToken.kt         az token per login dir, account from token claims (never shown)
  api/AdoClient.kt           REST client, auth retry, network stats
  api/Mentions.kt            @<GUID> to **@Name**
  api/dto/                   PRs, iterations, changes, threads, request bodies
  repo/AdoRemote.kt          remote URL parsing and ADO URLs
  repo/AdoRepoResolver.kt    picks the project's ADO remote
  repo/GitOps.kt             ls-remote push check, push, last commit subject
  state/PrReviewService.kt   loads PRs, details and update ranges; thread flows; reveal requests
  state/LastSeenService.kt   per-PR snapshots, badges, viewed files
  state/PrWatcher.kt         background polling, notifications, sidebar dot
  state/Activity.kt          what changed between polls
  state/SystemIdle.kt        macOS idle time
  state/PrTemplates.kt       ticket key, PR title and description
  state/AdoSettings.kt       app-level settings
  toolwindow/                list, details, file tree, dialogs, Status and Help tabs
  diff/PrDiffOpener.kt       single diff chain, All Files view, per-file producer, local file
  diff/PrDiffExtension.kt    hooks every PR diff viewer: threads, gutter +, banners
  diff/DiffLayout.kt         PR line to editor line, for each viewer type
  diff/ThreadPanel.kt        one thread: comments, reply, resolve
  diff/NewCommentInlay.kt    new comment box, comment target and range
  actions/                   toolbar, vote, checkout, create/edit PR, All Files, comment on selection
src/main/resources/
  META-INF/plugin.xml        tool window, diff extension, actions, notification groups, startup activity
  help/help.md               user guide shown in the Help tab
src/test/kotlin/…            unit tests
```

---

## Azure DevOps REST calls

Every call goes to `https://dev.azure.com/{org}/{project}/_apis/git/repositories/{repo}` and uses `api-version=7.1`, except where the table says otherwise.

| Purpose | Call |
|---|---|
| Me | `GET https://dev.azure.com/{org}/_apis/connectionData` |
| Repo (default branch) | `GET /` |
| PRs | `GET /pullrequests?searchCriteria.status=active&searchCriteria.reviewerId=…` (or `creatorId`, or `sourceRefName`) |
| PR | `GET /pullrequests/{id}`, `PATCH` for title, description and draft |
| Create PR | `POST /pullrequests` |
| Updates | `GET /pullrequests/{id}/iterations` |
| Changed files | `GET /pullrequests/{id}/iterations/{head}/changes?$compareTo={base}` (paged) |
| File content | `GET /items?path=…&versionDescriptor.version={sha}&versionDescriptor.versionType=commit&$format=octetStream` |
| Threads | `GET`/`POST /pullrequests/{id}/threads`, `PATCH …/threads/{tid}`, `POST …/threads/{tid}/comments` |
| Vote | `PUT /pullrequests/{id}/reviewers/{me}` |
| Names for mentions | `GET https://vssps.dev.azure.com/{org}/_apis/identities?identityIds=…` |

---

## Things to know before changing code

These are recorded in more detail in [`PLAN.md`](PLAN.md).

- **WebStorm 2026.x module split:** Git4Idea's base classes are in separate modules, so the build declares `bundledModule("intellij.platform.vcs.dvcs")` and `…dvcs.impl`.
- **Threading:** coroutines on `Dispatchers.EDT` and `AnAction`s hold the write-intent lock, but plain Swing listeners don't. Wrap editor, inlay or diff work started from a Swing listener in `WriteIntentReadAction.run { }`.
- **Kotlin and protected Swing fields:** inside a `JTree` subclass, `cellRenderer = …` and `showsRootHandles = …` write protected fields and bypass the UI delegate. Call the setters instead.
- **All Files view (internal combined-diff API):**
  - Open it like the platform does, with `CombinedDiffVirtualFileImpl` and `DiffEditorTabFilesManager.showDiffFile`. Anything else stays on "Loading…".
  - It caches its settings at creation, so give it its own collapsed settings object.
  - It disposes block viewers without Disposer, so per-viewer state hangs on `DiffLayout.onDispose`.
  - Very tall blocks fail with a Metal `can't create offscreen surface` error, which the IDE reports as low memory.
- **Per-viewer cleanup:** never `Disposer.register(viewer, …)` for per-viewer state. Use the lifetime disposed from `onDispose`.
- **The help text** is a resource. Update `help.md` when behaviour changes.

---

## Troubleshooting

Start with the **Status** tab. **Copy Diagnostics** gives you the whole page as text.

| Symptom | Likely cause |
|---|---|
| "No dev.azure.com remote found" | The project's remotes aren't on `dev.azure.com`. Check `git remote -v`. |
| "Not logged in to the Azure CLI" | Run `az login`, or the `AZURE_CONFIG_DIR=… az login` command for that org. |
| *Assigned to me* is empty, or HTTP 401 | `az` is logged in as another account. Compare **Status → Auth** with the account on the PRs. |
| TF401398 when creating a PR | The source branch isn't on the server. Click **+** again and choose **Push**. |
| Diff shows no "local file" after checkout | The file has uncommitted or unsaved changes, and a banner says so. Stash them and reopen the diff. |
| Everything is "New" | Expected the first time, because nothing has been seen yet. |
| Sandbox-only problems | Read `.intellijPlatform/sandbox/ado-pr-review/WS-*/log_runIde/idea.log`. |
