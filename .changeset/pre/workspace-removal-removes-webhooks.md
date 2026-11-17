---
"hephaestus": patch
---

Deleting a workspace now removes its GitLab webhooks, its Outline webhook subscription and its Slack bot token before it erases their credentials, as a disconnect does. Before, these stayed at the provider and waited under **Provider removals** for an instance admin. A removal that the provider does not confirm in the first 30 seconds still shows there, so nothing stays behind unseen. A workspace that GitHub removes when its app is uninstalled still leaves its Slack and Outline removals there.
