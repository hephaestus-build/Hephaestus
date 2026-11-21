---
"hephaestus": patch
---

When someone uninstalls the GitHub App, Hephaestus now removes the workspace's Outline webhook subscription and Slack bot token before it erases the workspace. Before, these removals waited for an instance admin under **Provider removals**, and Outline kept sending document events that Hephaestus refused.
