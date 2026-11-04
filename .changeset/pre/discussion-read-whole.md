---
"hephaestus": minor
---

A review no longer reads a partly synced discussion as the whole discussion. When a sync does not reach the end of a pull request's threads, reviews or comments, capture now reports them as partial, so no practice can conclude that nobody commented or that every thread was resolved. A practice that needs the whole discussion stops with its recorded reason until the next read finishes it. A sync stopped by a rate limit now reports that and retries the same window, instead of reporting success. A past-work scan now reads every thread and comment of a pull request, not only the first page.

**Operator step.** GitHub pull requests whose threads, reviews or comments end exactly at the size of a first page start as not read whole, because a past sync may have stopped there. The next sync of each settles it. GitLab merge requests are settled by their next sync and have no repair step. To settle unchanged ones now, repair the repository's historical scan from the admin console. It reads the repository again, which uses provider quota.
