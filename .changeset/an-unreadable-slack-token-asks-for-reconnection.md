---
"hephaestus": patch
---

The Slack integration page no longer shows a connection as Connected, or promises that the weekly digest will post, when its stored bot token can't be read. It now says the token is unreadable and that you can fix it by restoring the original server key or reconnecting Slack. The test message stays unavailable until then, and your saved digest settings are kept.

The Disconnect Slack confirmation explains what happens to data stored in Hephaestus and messages already sent in Slack, without claiming whether Slack will still show the app as installed.
