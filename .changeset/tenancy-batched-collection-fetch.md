---
"hephaestus": patch
---

Fixes teams and leaderboard pages failing to load on instances with synced labels. The tenancy self-check treats a keyed fetch as safe, but did not recognise the batched form Hibernate emits when it fills several repositories' label collections in one round trip, so it rejected the query and the request failed. Workspace isolation is unchanged — the rejected query was already pinned to keys the caller held.
