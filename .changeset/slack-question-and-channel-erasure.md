---
"hephaestus": patch
---

Hephaestus withdraws a pending Slack reply if you delete its question before the send is authorized.
After a backup restore, replaying a Slack channel erasure removes tool results that copied channel content while the Slack deletion event was in transit.
Replaying a channel erasure also preserves data collected under consent granted after the erasure.
Update your Slack app manifest to receive deletion events for private channels.
