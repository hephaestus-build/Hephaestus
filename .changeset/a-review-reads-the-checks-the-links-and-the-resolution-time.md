---
"hephaestus": minor
---

A practice review now reads three more facts from the record, on GitHub and GitLab alike: what the
checks said about the pull request's head (GitHub's status check rollup, GitLab's head pipeline),
which issues the provider records the pull request as closing — including links made in the
provider's UI that no `#N` in the text names — and when each review thread was resolved. The
schema migration that stores them applies automatically.

**Operators:** GitHub Apps created from an earlier manifest need the `check_suite` and `status`
event subscriptions added under the app's Permissions & events, and the Checks and Commit statuses
read permissions if the app predates them; GitLab group webhooks registered by an earlier release
need Pipeline events enabled, or the hook deleted so it is registered again. Until then the head's
check state arrives only with the scheduled sync.
