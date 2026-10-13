# Azure DevOps PR Review

Review pull requests on **dev.azure.com** without leaving WebStorm: list, diff, comment, resolve and vote.

## Signing in

The plugin uses the **Azure CLI** (`az`) for authentication. There is no token or password to enter.

1. Install the CLI: `brew install azure-cli`
2. Log in with the account that is a reviewer on your PRs: `az login`
3. Click **Refresh** in the Pull Requests tab.

Tokens are fetched with `az account get-access-token`, kept in memory only, and refreshed automatically.

The plugin finds `az` on your `PATH`, or in `/opt/homebrew/bin` or `/usr/local/bin`.

## Several orgs or accounts

Some orgs need a different account, for example a separate work account. Give each of those orgs its own Azure CLI login directory:

```
AZURE_CONFIG_DIR=~/.azure-<org> az login --allow-no-subscriptions
```

If `~/.azure-<org>` exists (the org name in lower case, as in the remote URL), the plugin uses that login for the org. Every other org uses your normal `~/.azure` login.

After switching accounts, click **Refresh**. It re-checks who you are logged in as.

**Symptoms of the wrong account:**

- PRs show under *All active* but not under *Assigned to me* or *Created by me*.
- HTTP 401 errors.

## Which repository is used

The plugin uses the first Git remote in the project that points to Azure DevOps, preferring `origin`. These remote forms are recognised:

- `https://dev.azure.com/<org>/<project>/_git/<repo>` (with or without a `user@` prefix)
- `git@ssh.dev.azure.com:v3/<org>/<project>/<repo>`
- `git@<alias>:v3/<org>/<project>/<repo>`, for a `~/.ssh/config` host alias. The org is read from the path.

Legacy `*.visualstudio.com` remotes are not supported.

## The pull request list

The **filter** button switches between *Assigned to me*, *Created by me* and *All active*. Your choice is remembered.

**Badges:**

- **New**: you haven't opened this PR here yet.
- **New commits**: the source branch moved since you last opened it.
- **New activity**: someone else commented since you last opened it. Your own comments don't count.

Opening a PR clears its badges. Double-click a PR to open it in the browser.

## Creating a pull request

Click **+** in the Pull Requests toolbar to create a PR from the current branch.

**Before the dialog opens:**

- If a PR from the branch is already open, it is selected instead.
- If the branch isn't on the remote yet, or the remote isn't at your latest commit, you're offered to push first. This is checked on the server, so a branch deleted there (for example after abandoning its PR) is pushed again even if your local `origin/…` ref still exists.

**Defaults in the dialog:**

- **Title:** the ticket key from the branch name, so `feature/ABC-123` gives `ABC-123 `. Type the summary after it. Branches without a key use the last commit message.
- **Description:** a link to the ticket, for example `[ABC-123](https://your-company.atlassian.net/browse/ABC-123)`.
- **Jira site:** used for that link. Enter it once per Azure DevOps org; it's remembered.
- **Target branch:** the one you used last for this repo, or else the repo's default branch.
- **Draft:** the choice you made last time.

After it's created, the new PR is selected. The list switches to *Created by me* if needed.

## Activity notifications

While the project is open, the plugin checks every 5 minutes for activity that concerns you:

- You were added as a reviewer.
- Someone voted on your PR.
- Someone else commented on your PR.
- Someone replied in a thread where you commented, on a PR you review.

**Where it shows up:**

- **A dot on the ADO PRs icon** in the sidebar. It clears as you open those PRs.
- **The PR in the list** gets **New activity** (or **New**, when you were just added as a reviewer).
- **WebStorm's Notifications tool window** (the bell) gets an entry, with **Show** and **Open in Browser**. For a comment, **Show** goes straight to the thread: the diff at that line with the thread expanded, or the Overview tab for a general comment. When several threads got comments, **Show** opens the PR, and each thread has its own **Go to … comment** action. There's no popup by default. For popups, go to *Settings → Appearance & Behavior → Notifications → ADO PR Activity* and choose *Balloon*.
- **PRs you've opened this session** get new comments live, in the details panel and in open diffs.

The first check after opening the project only records the current state, so you aren't flooded on startup.

**When checks pause:**

- After 30 minutes without keyboard or mouse input anywhere on the computer (macOS). Checks resume within about 30 seconds of you coming back, and the first one covers everything you missed.
- Nothing runs while the computer sleeps.

Each check is a few dozen read-only requests, made as you, so it doesn't affect anyone else.

To turn checking off or on, use **Notify About PR Activity** in the tool window's ⋮ menu.

## Overview tab

- **Vote**: Approve, Approve with suggestions, Wait for author, Reject or Reset.
- **Publish** (drafts only): publishes the draft, so reviewers are notified.
- **Edit…**: changes the title, description or draft state.
- **Checkout**: fetches the source branch and checks it out as a local branch that tracks the remote one. If the branch already exists locally, it switches to it without pulling.
- **Comments**: general (file-less) comments, with reply and resolve.

## Files tab

**Opening and stepping through files:**

- Double-click a file, or press **Enter**, to open the diff.
- Use **F7** / **Shift+F7** to step through all files. Each PR uses a single diff tab.

**All files at once:** click **All Files** (next to Compare, or on any PR diff's toolbar) to scroll through every changed file in one view, like the Files page in Azure DevOps.

- Each file is a block with unchanged code collapsed, loaded as you scroll.
- Comments, replies, resolving and the gutter **+** work there too.
- Unchanged code is collapsed by default. You can change that in the view's ⚙ menu without affecting your other diffs.
- Files with more than about 600 changed lines show a placeholder. Open those from the Files tab, because blocks that tall are more than macOS can draw.

**Markers in the tree:**

- **File names are coloured by change:** green for added, blue for modified, grey with strikethrough for deleted.

- **2 open · 1 resolved**: the comment threads on that file.
- **updated**: the file changed since your previous visit.
- **Comment rows** under a file (author and first line, grey when resolved). Double-click one to open the diff at that comment.

**Viewed marks:** click a file's icon, or press **Space**, to mark it as viewed (✓). The mark clears by itself if the file changes in a later push.

**Compare:**

- The default, *Merge base → latest update*, is the whole PR.
- Pick two updates, for example *Update 2 → Update 3*, to see only what one push changed.
- Existing comments are positioned for the latest version, so in older ranges they can sit a few lines off.

## Commenting in the diff

- Hover a line and click the **+** in the gutter to comment on it.
- To comment on several lines, select them first, then click **+** or right-click → **Add PR Comment**.
- **Cmd+Enter** posts, **Esc** cancels.
- Threads appear under their line, on the side they belong to. Resolved threads start collapsed; click the header to expand.
- Each thread has a reply box and **Resolve** / **Reactivate**.
- If posting fails, your text is kept and also copied to the clipboard.
- `@mentions` from ADO are shown as names. Typing `@Name` yourself posts plain text, not a real mention.

## Linting and type checking in the diff

The right side of a PR diff can be your real project file instead of a downloaded copy. TypeScript errors, ESLint, inspections, Cmd+click navigation and editing then work in the diff.

**When it happens:**

- You've checked out the PR (the **Checkout** button), and the file's current text, including unsaved edits, is exactly the PR's latest version.
- You're viewing the whole PR, not an older update range.

The right pane's title then says **local file**. In every other case the downloaded copy is used, so what you see when a diff opens is always the PR.

If you're on the PR's commit but the file has uncommitted or unsaved changes, a blue banner says so. To get inspections, stash or discard those changes and reopen the diff.

**If the file stops matching:** after you edit it, switch branches or pull, a yellow banner appears and commenting on that side is turned off. The line numbers would no longer match the PR. Undo the edits, or reopen the diff, to comment again.

**Uncommitted changes:** if your working tree has changes elsewhere, a blue banner reminds you that the inspection results may include them.

## Status tab

The **Status** tab shows what the plugin is doing and what it depends on, updated live:

- **Auth:** where `az` is, which login each org uses, the signed-in account and when its token expires, and the last `az` error.
- **Identity, Repository:** your Azure DevOps user ID, and the remote and branch in use.
- **Polling:** whether checks are on, running, waiting (with a countdown) or paused because the computer is idle, plus the last check's result.
- **Network:** request counts and the last failed request.
- **Caches and Build:** cache sizes, the plugin version and the IDE build.

**Buttons:**

- **Poll Now:** checks for activity right away.
- **Refresh Token:** asks `az` for a new token, for example after `az login` as another account.
- **Copy Diagnostics:** copies the page as text.

Tokens are never shown. The account name is read from the token in memory.

## What is stored where

- **Tokens:** memory only, never written to disk.
- **List filter:** IDE settings (`adoPrReview.xml`).
- **Seen state and Viewed marks:** this project's workspace file (`.idea/workspace.xml`), so it isn't shared through Git.
- **Activity notifications:** what was last seen is kept in memory only, so restarting the IDE starts from a fresh baseline.
- **Network calls:** only `dev.azure.com`, plus `vssps.dev.azure.com` to look up names for @mentions.

## Troubleshooting

- **"No dev.azure.com remote found"**: check `git remote -v`. See *Which repository is used* above.
- **"Not logged in to the Azure CLI"**: run `az login`, or the `AZURE_CONFIG_DIR=… az login` command for that org.
- **Empty "Assigned to me" or HTTP 401**: `az` is probably logged in as another account. Run `az account show`.
- **"TF401398 … branch no longer exists"** when creating a PR: the source branch isn't on Azure DevOps. Try **+** again and choose **Push**.
- **Everything is "New"**: expected the first time, because nothing has been seen yet.

## Updating the plugin

From the plugin's source folder:

```
JAVA_HOME=/Applications/WebStorm.app/Contents/jbr/Contents/Home ./gradlew buildPlugin
```

Then go to **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick `build/distributions/ado-pr-review-*.zip`, and restart WebStorm.
