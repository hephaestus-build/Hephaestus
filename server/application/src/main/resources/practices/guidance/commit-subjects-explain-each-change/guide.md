## How to do it

- In each commit subject, name what that commit changed, for a reader who will not open the diff.
- Name the thing that you changed. For example, instead of “fix”, write “Stop the export from dropping the last row”.
- Before you push, read your subjects from top to bottom. Fix a subject that a teammate cannot follow without the diff while the commit is still local.
- After you push, leave those commits as they are, and use the better form in your next commits. The one exception is a committed secret: purge it from history and rotate it.
- While your pull or merge request is open, make its title say what changed. If your repository squash-merges, make the squash message say it too.

## When it does not apply

- Merge commits, which have two or more parents, such as “Merge branch …” or “Merge pull request …”. Git or your provider writes these subjects, so they do not count.
- A pull or merge request that has only merge commits. There is no subject that you wrote.

It still applies when the pull or merge request has only one commit.

## Common mistakes

- Thinking that a subject needs a prefix such as “feat:” or a set length. It needs neither. “Update the changelog” is short and still names what changed. Instead, check that each subject names the thing that you changed.
- Writing a word with no object, such as “wip”, “cleanup” or “small changes”. Instead, add the thing you changed, such as “Remove unused imports from the parser”.
- Giving several commits the same subject, such as “fix” three times. Instead, name what each step did, so a reviewer can follow each step.
- Writing only an issue number, a branch name or “address comments”. Instead, add a short summary beside the reference, such as “#39: switch logging to the shared logger”.

## Sources

- [The canonical patch format](https://docs.kernel.org/process/submitting-patches.html#the-canonical-patch-format), Linux kernel documentation. The summary phrase should describe its patch, and a series should not use the same phrase for every patch. People who scan history with `git log --oneline` may see only that phrase.
- [git-commit](https://git-scm.com/docs/git-commit#_discussion), Git documentation. Git recommends a first line that summarizes the change. Git uses the text up to the first blank line as the commit title.
- [How to write a Git commit message](https://cbea.ms/git-commit/), Chris Beams, 2014. A clear log lets you review other people’s commits on your own. It also helps you learn later why a change happened.
- [What makes a good commit message?](https://doi.org/10.48550/arXiv.2202.02974), Tian, Zhang, Stol, Jiang and Liu, 2022. A commit message is an audit trail of how code changed and why. Many messages in active open source projects lack information.
