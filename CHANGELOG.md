# Changelog

## 0.3.0

- Start Jira Ticket: pick one of your open tickets (or type any key), and get a `feature/ABC-123` or `bugfix/ABC-123` branch from the latest remote commit, an empty starter commit, a push and a draft PR. The ticket is assigned to you and moved to In Progress. If the ticket already has a branch, it is checked out instead.
- Jira sign-in with an email and API token, kept in the macOS Keychain. The Status tab shows the Jira login and has **Reset Jira Token**.
- Create PR: the title includes the Jira summary (`ABC-123 Add login form`) when Jira is signed in.
- Start Jira Ticket: if the empty commit or the push fails, the new branch is removed and you're back on your previous branch, so a retry starts clean. If the PR can't be created after the push, the branch is kept and you're told to create the PR with +.
- Create PR: choosing "Don't Push" for a branch whose remote is behind now says the PR shows the older remote commits.
- Builds against WebStorm 2026.3, where VCS and JNA moved into bundled plugins. 2026.2 is no longer supported.

## 0.2.0

- Create PR: target branch picker grouped by folder and numbered family, filterable by typing.
- Create PR: the title is optional and falls back to the branch name.
- Checkout now actually checks out the PR branch after fetching.
- Comment cards with likes; edit and delete your own comments.
- PRs with deleted files load; deleted files are shown with strikethrough.

## 0.1.0

- First version: PR list, details, diffs with inline threads, voting, creating PRs, activity notifications.
