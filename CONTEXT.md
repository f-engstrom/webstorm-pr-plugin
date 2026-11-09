# Domain glossary

**Branch → PR workflow** (`workflow/PrWorkflow`): the module that turns a branch into a pull request: **Create PR** from the current branch, and **Start Ticket**. It owns whether to push, which target branches to offer, what to undo when a step fails, and how Jira problems are reported. Actions only collect input and show the outcome.

**Start Ticket**: for a Jira ticket, create `type/KEY` from the remote head of the target branch, make an empty starter commit, push, open a draft PR, then assign the ticket and move it to In Progress. If the ticket already has a branch (local or on the remote), switch to it instead.

**Target branch**: the branch a PR merges into. Offered default branch first, then develop/main-like branches; the last one used is remembered per repo (`org/project/repo`).

**Stale remote**: the remote branch is behind local commits and the user chose not to push; the PR shows the remote's older head.

**Rollback** (Start Ticket): if the empty commit or the push fails, switch back to the previous branch and delete the new one. Once the push has succeeded the branch is kept, even if the PR fails; the user creates the PR with +.

**Jira problems**: a failed assign or a missing In Progress transition. These are reported as warnings and never undo the branch or the PR.

**PR conversation** (`conversation/PrConversations`): the review threads of a PR and every way to change them: post, reply, like, edit, delete, resolve. Views observe its threads; every write reloads them. Whoever fetches (details, diff, list badges, background poll) gets threads with system threads removed and @mentions resolved, and PRs open in a view are updated. An older fetch finishing after a newer one is dropped.

**Line anchor**: where a new comment goes: a file, a side (source = right/new, target = left/old) and 0-based editor lines and columns. `Anchors` converts to and from ADO's 1-based left/right positions.

**Reveal**: a request (from a notification's "Show") to expand and scroll to one thread when a diff shows it; taken once.

**PR activity** (`state/PrActivity`): what's new on the listed PRs. It owns the list **badges** (New: never opened; New commits: pushes since last opened; New activity: comments by others since then, or anything the poll reported), the **unseen** set behind the dot on the tool window icon, and the poll **baseline**. The first poll after starting, switching repo or turning polling off only records the baseline. Opening a PR (**seen**) clears its activity badge and dot. The tool window observes it and holds no state of its own; services and actions ask it to **select** a PR (and thread) rather than reaching for the list panel.

**Snapshot** (`LastSeenService`): what the user saw of a PR when they last opened it (iteration, source commit, latest comment by others, viewed files). Badges compare against it.

**Repo key** (`AdoRemote.key`): `org/project/repo`, lower-cased. Identifies the repo for the remembered target branch and the poll baseline.

**Active repo** (`AdoRepoResolver`): the project's first Git repository with a dev.azure.com remote, preferring `origin`. Cached; cleared when repositories or remotes change.

**User operation** (`PrReviewService.launchOperation`): something the user started (a click or an action). It runs on the EDT; a failure becomes an error notification. Cancellation is never reported.
