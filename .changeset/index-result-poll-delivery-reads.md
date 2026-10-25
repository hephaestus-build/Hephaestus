---
"hephaestus": patch
---

The server's check for finished reviews to process, a developer's newest result and the reviews of an item now use indexes, so they stay fast as review history grows. The upgrade builds the new indexes without blocking writes; on a large database, the first start after the upgrade takes longer while it builds them. It also removes two `activity_event` indexes that duplicated others.
