---
"hephaestus": patch
---

After a server restart, webhook events for repositories a workspace already monitors, and its Slack and Outline activity, are processed while the startup sync runs instead of after it, including events that queued up while the server was down.

When a GitLab group is connected, Hephaestus registers the group webhook only after it has listed every project in the group and is ready to receive their events, then runs the full sync while those events are processed. If it cannot list every project or cannot get ready, it leaves the webhook unregistered, and the integration's sync status shows the webhook as missing; the next scheduled sync, or **Sync now**, tries again. A deployment with NATS disabled no longer registers GitLab group webhooks, because nothing there would receive their events.

A group webhook that is already registered is unchanged: events for projects Hephaestus does not monitor yet, such as newly created ones, are still not picked up.
