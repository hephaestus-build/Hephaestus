---
"hephaestus": patch
---

Reviews that a push or another pull request event starts now give feedback again. Before this fix, every practice of such a review was withheld with "The sources changed before this practice started", and no review reached the model. The cause was the check that runs before a practice starts: it compared the pull request as the event recorded it with the pull request after the next sync. That sync adds the merge state and the checks of the new commit, also when the repository runs no CI, so unchanged work looked changed on GitHub and GitLab. The check now reads the same record that the review read. Feedback from these reviews also stays current when the next sync runs. A new commit, a new comment or other real changes still withhold the practice with that reason. The labels of linked issues are now always read in the same order. Because of this, feedback that read a linked issue with several labels can show as outdated once after the upgrade.
