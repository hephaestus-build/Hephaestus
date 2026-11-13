---
"hephaestus": patch
---

Product feedback reports go to GitHub through the same outbox as email. When GitHub's answer to a new issue is lost, Hephaestus looks for that issue by its marker before it files again, so a report never becomes two issues. A report that a server was filing during the upgrade is looked up first, and filed again only if it is not on GitHub. Operators can watch filing in the `action.ended` metric with `kind=FILE_ISSUE`.
