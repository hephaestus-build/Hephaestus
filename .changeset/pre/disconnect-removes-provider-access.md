---
"hephaestus": patch
---

Disconnecting an integration now reliably removes what Hephaestus set up at the provider: the GitHub App installation, the GitLab or Outline webhook, or the Slack token. Before, a restart or an unreachable provider at that moment left it in place without notice. If the provider stays unreachable, Hephaestus tries again for up to 24 hours. After that, the integration's page and an email to workspace admins say what to remove at the provider.
