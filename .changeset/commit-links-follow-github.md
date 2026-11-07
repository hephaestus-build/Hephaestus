---
"hephaestus": minor
---

A new job corrects commit links from GitHub's own answer for each email on a repository. It is off by default: set `config.commitIdentityResolutionEnabled` to `true` in your Helm values to run it. It replaces a link that GitHub contradicts, and it clears a link when GitHub says that no account owns the email. Each changed link is recorded for 90 days. The operations runbook has the steps. Load the rule file of the release to get the new alert `HephaestusCommitIdentityResolutionStalled`.
