---
"hephaestus": minor
---

Practice reviews of GitHub pull requests can now read the single check runs and commit statuses of the reviewed commit. A practice can also review a pull request again when its checks have finished, with the new **Checks finished** event: once for each push, when every check GitHub had listed has ended. A re-run on the same push starts no new review. A check run that has not finished yet never counts as passed or failed, and when Hephaestus has not read the check runs, a practice reports that it could not determine them instead of reporting missing checks. GitLab pipelines are not read yet.
