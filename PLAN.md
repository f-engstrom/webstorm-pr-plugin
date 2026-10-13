# Azure DevOps PR Review – WebStorm plugin

Plan for agents building this plugin. It's a personal, local-only plugin for one user and one WebStorm version, so keep it small and pragmatic. Work one phase at a time. Each phase must build, run in the sandbox IDE (`./gradlew runIde`), and be reviewed by the user before the next one starts.

## Goal

Review Azure DevOps pull requests inside WebStorm:

- Listing PRs for the open repo.
- Diffing changed files in the IDE diff viewer.
- Showing threads inline.
- Commenting, replying, resolving and voting.

## Out of scope

- Publishing to the Marketplace, compatibility with other IDE versions, localisation.
- Any auth other than the Azure CLI (no PATs, no settings page).
- Legacy `*.visualstudio.com` hosts and Azure DevOps Server. Only `dev.azure.com` is supported.
- Org- or project-wide PR inbox. Only the open repo is covered.
- Creating, completing or abandoning PRs. Branch policies and builds. Use the web UI for these.

## Stack

- Kotlin 2.4, JVM 25 (WebStorm 2026.2 requires Java 25). No separate JDK is installed: the build uses WebStorm's bundled JBR as the toolchain, so run Gradle with `JAVA_HOME=/Applications/WebStorm.app/Contents/jbr/Contents/Home`.
- IntelliJ Platform Gradle Plugin 2.x (latest), Gradle 9.8.
- Scaffold from https://github.com/JetBrains/intellij-platform-plugin-template. Delete the sample code.
- Target platform: the locally installed WebStorm (`local("/Applications/WebStorm.app")`, 2026.2.3, build 262.10968.77), `sinceBuild = 262`, no `untilBuild`.
- Plugin dependencies: `com.intellij.modules.platform`, `Git4Idea`. In 2026.x the DVCS classes are split out, so the build also needs `bundledModule("intellij.platform.vcs.dvcs")` and `bundledModule("intellij.platform.vcs.dvcs.impl")`. `VcsRepositoryManager` is now in `com.intellij.dvcs.repo`.
- kotlinx.serialization and coroutines come from the platform; don't bundle them.
- HTTP: `java.net.http.HttpClient`.
- JSON: kotlinx.serialization with `ignoreUnknownKeys = true`.
- Markdown rendering: the platform's bundled JetBrains markdown parser (`org.intellij.markdown`, GFM flavour) to HTML, shown in a `JEditorPane`. Use commonmark-java only if that falls short.
- Coroutines: services get an injected `CoroutineScope`. Do I/O on `Dispatchers.IO` and UI work on `Dispatchers.EDT`.
- Internal platform APIs are allowed (e.g. `EditorEmbeddedComponentManager`, `collaboration-tools` UI pieces) since this only needs to work in one IDE version. Prefer public APIs when they're just as easy.

Install for daily use: `./gradlew buildPlugin`, then Settings → Plugins → Install Plugin from Disk with the zip from `build/distributions/`.

## Layout

```
src/main/kotlin/<pkg>/
  auth/        AzCliToken.kt
  api/         AdoClient.kt, dto/*.kt
  repo/        AdoRepoResolver.kt
  state/       PrReviewService.kt
  toolwindow/  PrToolWindowFactory.kt, PrListPanel.kt, PrDetailsPanel.kt, PrFileTree.kt
  diff/        PrDiffOpener.kt, PrDiffExtension.kt, ThreadInlay.kt, NewCommentInlay.kt
  actions/     RefreshAction.kt, VoteAction.kt, OpenInBrowserAction.kt
src/main/resources/META-INF/plugin.xml
src/test/kotlin/...
```

## plugin.xml registrations

```xml
<depends>com.intellij.modules.platform</depends>
<depends>Git4Idea</depends>
<extensions defaultExtensionNs="com.intellij">
  <toolWindow id="ADO PRs" anchor="left" factoryClass="...toolwindow.PrToolWindowFactory"/>
  <diff.DiffExtension implementation="...diff.PrDiffExtension"/>
  <notificationGroup id="ADO PR Review" displayType="BALLOON"/>
</extensions>
```

Services are light services (`@Service`, or `@Service(Service.Level.PROJECT)`). They aren't registered in XML. Persisted ones need `@State`.

## Azure DevOps REST

- Base URL: `https://dev.azure.com/{org}/{project}/_apis/git/repositories/{repo}`.
- Add `api-version=7.1` to every call.
- Only one org is used for now, but always take the org from the resolved remote rather than hardcoding it.

| Purpose | Call |
|---|---|
| Me | `GET https://dev.azure.com/{org}/_apis/connectionData` (`authenticatedUser.id` is the reviewer ID) |
| List PRs | `GET /pullrequests?searchCriteria.status=active&searchCriteria.reviewerId={me}` (`creatorId` for the author filter, neither for all) |
| PR | `GET /pullrequests/{id}` (`lastMergeSourceCommit`, `lastMergeTargetCommit`, `reviewers`, `isDraft`) |
| Iterations | `GET /pullrequests/{id}/iterations` |
| Changes | `GET /pullrequests/{id}/iterations/{latest}/changes?$top=2000&$skip=n` (paginate) |
| File content | `GET /items?path={p}&versionDescriptor.version={sha}&versionDescriptor.versionType=commit&includeContent=true` |
| Threads | `GET /pullrequests/{id}/threads` |
| New thread | `POST /pullrequests/{id}/threads` |
| Reply | `POST /pullrequests/{id}/threads/{tid}/comments` |
| Thread status | `PATCH /pullrequests/{id}/threads/{tid}` with `{ "status": "active" \| "fixed" \| "closed" \| ... }` |
| Vote | `PUT /pullrequests/{id}/reviewers/{me}` with `{ "vote": 10 \| 5 \| 0 \| -5 \| -10 }` |

Notes:

- Thread lines are 1-based. `threadContext.rightFileStart` is the source (new) side and `leftFileStart` is the target (old) side.
- Filter out system threads. A thread is a system thread when `properties` contains `CodeReviewThreadType`, or when it has no `threadContext` and every comment has `commentType == "system"`.
- When you create a thread, include `pullRequestThreadContext.iterationContext` and `changeTrackingId` (from the changes response). Without them, the web UI treats the thread as outdated after the next push.
- Detect binary files with `contentMetadata.isBinary` from the items/changes response.
- On a 401, refresh the token once and retry. If it still fails, notify "Run `az login`".

## Auth (Azure CLI only)

`AzCliToken` is an application-level light service:

- Runs `az account get-access-token --resource 499b84ac-1321-427f-aa17-267ca6975798 -o json`.
- Reads `accessToken` and `expires_on` (POSIX seconds). Fall back to `expiresOn` (local-time string) for older `az` versions.
- Per-org logins: the user has different identities per org (e.g. a separate work account for some orgs), so if `~/.azure-{org}` exists, `az` runs with `AZURE_CONFIG_DIR` set to it. Otherwise it uses the default `~/.azure`. Log in with `AZURE_CONFIG_DIR=~/.azure-{org} az login --allow-no-subscriptions`.
- Caches tokens in memory only, keyed by config dir. Refreshes 60 s before expiry or on a 401. The user ID per org is re-fetched on a manual Refresh.
- Header: `Bearer <token>`.
- Find `az` using `PathEnvironmentVariableUtil.findInPath("az")`, then `/opt/homebrew/bin/az`, then `/usr/local/bin/az`.
- Use `GeneralCommandLine.withParentEnvironmentType(CONSOLE)`. GUI-launched macOS apps don't inherit the shell PATH.
- If `az` is missing or not logged in, show a notification saying so.
- Never log tokens.

## Repo resolution

`AdoRepoResolver` reads `GitRepositoryManager.getInstance(project).repositories`, then each repo's `remotes` and `urls`, and uses the first remote that parses. It parses these URL forms to `{org, project, repo}`:

- `https://dev.azure.com/{org}/{project}/_git/{repo}`
- `https://{user}@dev.azure.com/{org}/{project}/_git/{repo}`
- `git@ssh.dev.azure.com:v3/{org}/{project}/{repo}`
- `git@{alias}:v3/{org}/{project}/{repo}`, where `{alias}` is a `~/.ssh/config` host alias for `ssh.dev.azure.com`. The user has one alias per org, so any SSH host with the `v3/` path is accepted and the org is taken from the path.

Keep the parsing in a pure `parse(url)` function. URL-decode the segments, because project names may contain `%20`. Unit-test every form.

## Phases

### Phase 1: scaffold, auth and list

- Scaffold from the template. Set the plugin ID, name, vendor, package and target platform. Confirm `./gradlew runIde` opens WebStorm with the empty plugin.
- `AzCliToken`, `AdoRepoResolver` and its tests.
- `AdoClient` with: me, list PRs, get PR.
- Tool window with a `JBList` of PRs. Each row shows title, `#id`, author, target branch, a draft marker, and my vote.
- Toolbar actions: refresh, open in browser (`https://dev.azure.com/{org}/{project}/_git/{repo}/pullrequest/{id}`), filter (reviewer / author / all). Persist the filter in a small `@State` service.
- **Done when:** the list shows real PRs for the open repo.

### Phase 2: details and diff

- Details panel in a splitter below the list. It shows:
  - title, description (rendered Markdown), source → target branch
  - reviewers with their votes
  - the changed files tree (`PrFileTree`, grouped by directory, showing change type).
- `PrDiffOpener` opens a `SimpleDiffRequestChain`:
  - one `SimpleDiffRequest` per changed file
  - left side is `lastMergeTargetCommit` at the original path; right side is `lastMergeSourceCommit` at the path
  - content comes from the Items API
  - use `DiffContentFactory.create(project, text, fileType)` so syntax highlighting works.
- Handle adds and deletes with `DiffContentFactory.createEmpty()`. Show renames using `originalPath`. Show binary files as "binary, not shown".
- Put `PR_KEY`, `PATH_KEY` and `CHANGE_KEY` on each request using `putUserData`.
- Double-clicking a file in the tree opens the chain at that file.
- Spike for Phase 3: confirm a Swing component can be embedded as a block inlay in a `TwosideTextDiffViewer` editor.
- **Done when:** you can step through every file with F7 / Shift+F7 and highlighting is correct.

### Phase 3: threads

- `PrReviewService` caches threads per PR, grouped by path.
- `PrDiffExtension.onViewerCreated`:
  - return unless the request has `PR_KEY`
  - for a `TwosideTextDiffViewer`, add a block inlay per thread below its line, on the matching side
  - use `editor.inlayModel.addBlockElement(...)` with an embedded component (`EditorEmbeddedComponentManager` or a `collaboration-tools` equivalent).
  - added and deleted files open in a `OnesideTextDiffViewer`, not a two-sided one; handle both (only the shown side gets inlays)
- `PrDiffOpener` opens the chain as a `ChainDiffVirtualFile` and reuses one tab per PR, jumping with `CacheDiffRequestChainProcessor.setCurrentRequest`; the tab is replaced when the PR gets new commits.
- `ThreadInlay` shows author, date, rendered Markdown per comment and the thread status. Resolved threads start collapsed.
- Reply box and Resolve / Reactivate buttons in `ThreadInlay`.
- File tree shows a comment count per file.
- General (file-less) threads are shown in the details panel, with a reply box.
- **Done when:** threads match the web UI on a PR with comments on both sides, and reply and resolve round-trip.

### Phase 4: write

- A gutter "+" on hover on either side of the diff opens `NewCommentInlay`, a text area with Submit and Cancel.
- An action "Comment on selection" sets `start` and `end` lines from the selection.
- POST the thread with `threadContext` and `pullRequestThreadContext`, then refresh the threads.
- If a submit fails, keep the typed text in the inlay.
- `VoteAction` is a dropdown in the details panel with Approve, Approve with suggestions, Wait for author, Reject and Reset.
- **Done when:** a comment made in WebStorm shows up on the right line in the web UI, and votes show in the web UI.

### Phase 5 (optional): extras

Only build these if the user asks for them after using phases 1–4:

- Per-file "Viewed" checkbox, stored by `objectId` in a project-level `@State` service (workspace file), cleared when the file changes.
- List badges for new commits / new activity since last opened.
- Iteration picker (base and head) that reloads changes for the chosen range, using `$compareTo`.
- Checkout of the PR source branch through Git4Idea.

## Testing

- Unit-test remote URL parsing and system-thread filtering. Nothing else needs automated tests.
- Everything else is verified by hand against real PRs with `./gradlew runIde`.

## Conventions

- Keep it small: no DI frameworks, no abstraction layers, no interfaces with a single implementation.
- Hardcoded English strings are fine.
- Threading (2026.x): coroutines on `Dispatchers.EDT` and `AnAction`s get the write-intent lock, but plain Swing listeners (mouse, button, component listeners) do not. Wrap any editor, inlay or diff work started from a Swing listener in `WriteIntentReadAction.run { }`.
- All Files view (platform combined diff, internal API). Lessons from getting it to work:
  - Open it exactly like the platform's "All in One Diff": `CombinedDiffVirtualFileImpl` through `DiffEditorTabFilesManager.showDiffFile`. A custom `CombinedDiffVirtualFile` subclass, or `FileEditorManager.openFile`, stays on "Loading…" forever.
  - It caches `TextDiffSettings` in its context (`TextDiffSettings.KEY`) at creation and uses the IDE-wide `Default` place, so give it its own settings object right after opening rather than changing the user's global diff settings.
  - It disposes block viewers directly when they scroll out, without going through Disposer. Hang per-viewer state on a lifetime disposed from `DiffViewerListener.onDispose` (see `DiffLayout.onDispose`), never on `Disposer.register(viewer, …)`.
  - Very tall blocks (large added files, huge diffs) fail with `OutOfMemoryError: can't create offscreen surface` (Metal), which the IDE reports as low memory. Changes over 600 lines get a placeholder.
- Report errors through `Notifications` (group `ADO PR Review`). Never use `Messages.showErrorDialog` from background threads.
- Only send network calls to `dev.azure.com`, plus `vssps.dev.azure.com/{org}/_apis/identities` to resolve `@<GUID>` mentions to names (names already in the PR data are used first).
- User-facing help lives in `src/main/resources/help/help.md`, shown in the tool window's Help tab. Update it when behaviour changes.

## References

- Plugin template: https://github.com/JetBrains/intellij-platform-plugin-template
- Platform SDK docs: https://plugins.jetbrains.com/docs/intellij/
- Git4Idea and inline-comment examples: the GitHub plugin in intellij-community (`plugins/github`)
- ADO Git REST 7.1: https://learn.microsoft.com/rest/api/azure/devops/git/?view=azure-devops-rest-7.1
- Thread context: https://learn.microsoft.com/rest/api/azure/devops/git/pull-request-threads/create?view=azure-devops-rest-7.1
