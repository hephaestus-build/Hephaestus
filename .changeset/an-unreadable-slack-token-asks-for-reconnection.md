---
"hephaestus": patch
---

The Slack integration page no longer shows a connection as Connected, or promises that the weekly digest will post, when its stored bot token can't be read. It now says the token is unreadable and that you can fix it by restoring the server key it was written with, or by disconnecting and connecting Slack again, which erases the Slack messages and channel consents stored in Hephaestus. The test message stays unavailable until then, and your saved digest settings are kept.

The Disconnect Slack confirmation no longer says the app is uninstalled from Slack: disconnecting removes the Slack connection for this workspace and erases its stored Slack data, and the app stays installed in Slack.
