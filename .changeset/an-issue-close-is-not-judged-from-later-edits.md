---
"hephaestus": minor
---

The practice "Confirm the outcome before closing the issue" now ships as needing human review. It asked
whether an issue's checklist and sub-issues were finished when the issue was closed, but a review only sees
the issue as it is later, after boxes may have been ticked, unticked or added, sub-issues finished or
attached, or the issue reopened and closed again, so it could record a lapse the developer never made or a
clean close that was not. The instance catalog shows it as needing human review, including where an
administrator customized it to ask for a review; a customization set to guidance only stays guidance only.
Every workspace copy recorded as coming from the catalog entry, including an edited one, is no longer
reviewed from the upgrade on: a review already under way records no new result for it, the upgraded server
switches it off at startup with the change in the configuration audit log, and it cannot be switched back
on. If that repair cannot finish, the server's health reports it. A copy made and edited before Hephaestus
recorded where copies came from has no such record, is treated as the workspace's own and is still
reviewed. Results recorded before the upgrade stay as they were, and the practice page no longer shows a
phrase for this practice.

**Operators:** after upgrading, find workspace practices with the slug `issue-closed-with-unmet-outcome`
and no recorded source, decide with each workspace whether it is an old catalog copy, and switch those off;
see the migration guide.
