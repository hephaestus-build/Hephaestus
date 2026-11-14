---
"hephaestus": patch
---

The product feedback private repository now stays out of every workspace. An installation that covers all repositories no longer adds it, and a workspace admin cannot add it. If a workspace already monitors it, or monitored it before, Hephaestus removes it after the upgrade, with a repair every 15 minutes until that succeeds. This deletes rows: that repository's mirrored issues and comments, the Activity that each of those workspaces recorded from it, and the text of its work items. The issues stay on GitHub, and reports and filings are not affected. A private repository that you replace can be monitored again, so check who could then see it.
