---
"hephaestus": patch
---

Slack replies now honour the existing `HEPHAESTUS_INTEGRATION_SLACK_API_BASE` setting, as connecting a workspace already did. The setting is for tests only: the server refuses to start unless it is an `http` or `https` URL with a host, and the bot token and messages of every workspace go to the host that it names.
