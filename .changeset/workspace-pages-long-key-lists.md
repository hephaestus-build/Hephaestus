---
"hephaestus": patch
---

The workspace practice overview and its tiles open in large workspaces again. Before, a workspace with a few hundred reviews in the window got a server error on both pages, because the workspace isolation check ran out of stack on the long list of reviews that the pages look up. Other reads that look up many records by their keys had the same limit and no longer fail.
