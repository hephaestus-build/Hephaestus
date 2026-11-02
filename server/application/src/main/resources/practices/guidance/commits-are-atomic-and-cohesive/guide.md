## How to do it

- Commit each piece of work when it’s done, before you start the next one. It’s easier to join small commits later than to split a big one.
- Keep everything one change needs in its commit. A fix goes with its test. A feature goes with its view, its model and its package entry.
- Give unrelated chores their own commits. For example, instead of one commit that fixes a paging bug and reformats the file, commit the fix first. Then commit the reformat.
- Before you push, read the subject, the body and the files of each commit. If a local commit holds two pieces of work that each make sense alone, split it. After you push, leave those commits as they are, and give your next piece of work its own commit. The one exception is a committed secret: purge it from history and rotate it.
- If one commit needs another, keep them in order. Each commit should still build and pass the tests.

## When it does not apply

- Every commit in the pull request or merge request is an automatic merge commit, such as “Merge branch …”. There is no split of your own to check.

It still applies when the pull request or merge request has only one commit that you wrote.

## Common mistakes

- Counting files or the word “and” in a subject. One change can touch many files and need two verbs, such as “Parse the header and validate it”. Instead, ask whether each part makes sense alone.
- Cutting one change into pieces that cannot stand alone, such as a fix and its test in two commits. Instead, keep the parts of one change together.
- Mixing an unrelated rename, lint fix or dependency update into a feature commit. Instead, put the unrelated work in its own commit. Keep the changes that the feature needs with the feature.
- Squashing separate ideas into one commit to keep the history short. Instead, keep each idea in its own commit. Several commits are fine, and so is a revert in its own commit.

## Sources

- [Separate your changes](https://docs.kernel.org/process/submitting-patches.html#separate-your-changes), Linux kernel documentation. Each logical change gets its own patch, even across many files, and each patch must keep the build working.
- [Separate changes](https://git-scm.com/docs/gitworkflows#_separate_changes), Git documentation. Split work into small logical steps, and edit the commits before you publish them.
- [Partitioning composite code changes](https://hunkim.cse.ust.hk/papers/tao-msr2015.pdf), Tao and Kim, 2015. In a study with 18 students, partitioned changes led to better review answers in a similar time.
- [The impact of tangled code changes](https://www.microsoft.com/en-us/research/publication/the-impact-of-tangled-code-changes/), Herzig and Zeller, 2013. Unrelated changes within bug fixes made associations between files and bug reports less accurate. One author worked at Microsoft, which sells version control tools.
