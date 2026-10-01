---
"hephaestus": minor
---

GitLab reconciliation now removes comments deleted on GitLab, including merge request diff notes, only after a complete note listing for each issue or merge request. Incomplete listings keep the mirror unchanged.

GitLab tokens are checked daily and rotated before expiry when permitted. Workspace owners and administrators who enable Workspace alerts receive an email when a token needs replacement and when it recovers. Refused tokens show a degraded connection with replacement guidance, and the connection page shows the observed token expiry without exposing the token. Use a dedicated token: rotation immediately revokes the old token.
