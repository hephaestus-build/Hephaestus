---
"hephaestus": patch
---

Opening an observation, a review page or a practice page no longer waits on GitHub or GitLab, and no longer holds other writes while Hephaestus fetches a repository to check whether the observation is still current. Before a practice starts and before feedback is sent, the server brings its repository copy up to date first, so a server that did not capture the work, or that cleared its copy, still checks it against the current work. If the provider cannot be reached when feedback is sent, the send is retried. An observation that cannot be checked now says "Cannot verify now", and its note gives the time of the work it is based on. It no longer claims that its record is missing. Feedback that still cannot be checked is not sent, and workspace admins see why. When a review could not fetch the repository, its linked work is recorded as unavailable, so a later check no longer reports the unchanged work as changed.
