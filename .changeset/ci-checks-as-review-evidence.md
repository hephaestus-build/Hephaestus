---
"hephaestus": minor
---

Practice reviews of GitHub pull requests can now read the single check runs and commit statuses of the reviewed commit. A practice can also review a pull request again when its checks have finished, with the new **Checks finished** event: when every check GitHub had listed has ended. A re-run on the same push starts a new review when its checks finish again, even if the results match. A check run that has not finished yet never counts as passed or failed, and when Hephaestus has not read the check runs, a practice reports that it could not determine them instead of reporting missing checks. Checks that wait for a maintainer to approve them, as on a pull request from a fork, count as not finished, and Hephaestus's own check run is never counted as one of the pull request's checks. GitLab pipelines are not read yet.
