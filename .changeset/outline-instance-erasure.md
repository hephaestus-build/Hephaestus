---
"hephaestus": patch
---

Erasing an Outline contributor by provider instance and user ID now covers every team on that instance.
Hephaestus stops reading the affected documents when erasure starts.
Erasure hides their titles in the item directory and removes their local document data.
It redacts context copies and their answers by Outline instance and team.
If the saved selection misses a document or a relevant copy has no source key, erasure stops.
A request that spans providers waits for the Slack and SCM copies too.
Use a stopped upgrade when you install this release.
