---
"hephaestus": patch
---

A practice review now reads the whole review record. The commits of a change are staged as a record
a review can quote — each with its message and the files it touched — so feedback about commit
subjects and cohesion cites the commit, not the pull request title. Inline comments carry their
thread, reply and side, threads carry their id and opening time, every submitted review decision is
kept with its summary text, and the pull request record names its labels, assignees, milestone and
whether it merged. A GitLab note on a removed line keeps its line. The practices that judge review
engagement, unresolved threads at merge and merging after approval read the record as rows the
review decides on, and a review is told which record files were not captured so it does not look
for them. Hephaestus's own inline notes are no longer fed back to a review as a reviewer's comments.
