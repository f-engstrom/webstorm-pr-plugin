# Domain glossary

**Branch → PR workflow** (`workflow/PrWorkflow`): the module that turns a branch into a pull request: **Create PR** from the current branch, and **Start Ticket**. It owns whether to push, which target branches to offer, what to undo when a step fails, and how Jira problems are reported. Actions only collect input and show the outcome.

**Start Ticket**: for a Jira ticket, create `type/KEY` from the remote head of the target branch, make an empty starter commit, push, open a draft PR, then assign the ticket and move it to In Progress. If the ticket already has a branch (local or on the remote), switch to it instead.

**Target branch**: the branch a PR merges into. Offered default branch first, then develop/main-like branches; the last one used is remembered per repo (`org/project/repo`).

**Stale remote**: the remote branch is behind local commits and the user chose not to push; the PR shows the remote's older head.

**Rollback** (Start Ticket): if the empty commit or the push fails, switch back to the previous branch and delete the new one. Once the push has succeeded the branch is kept, even if the PR fails; the user creates the PR with +.

**Jira problems**: a failed assign or a missing In Progress transition. These are reported as warnings and never undo the branch or the PR.
