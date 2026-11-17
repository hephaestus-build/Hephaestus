---
"hephaestus": minor
---

Workspace admins can try a practice on one GitHub or GitLab pull request, merge request or issue before they rely on it. A trial reviews the work as a requested review does and spends from the same AI budget, but it records no observation, sends no feedback and counts in no standing, overview or review metric. The developer whose work it read does not see it, and their own reviews of that work run as usual. Start a trial with `POST /api/workspaces/{slug}/practice-trials` and read what it found with `GET /api/workspaces/{slug}/practice-trials/{id}`. Only one trial of a workspace runs at a time (`hephaestus.practices.trials.max-active`). What a trial found is deleted 30 days after it ends (`hephaestus.practices.trials.retention`), and at once when a person whose work it read is erased. Operators get the metric `practice_trial_ended_total`.
