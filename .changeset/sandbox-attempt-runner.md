---
"hephaestus": patch
---

The worker metric for orphaned sandboxes now counts each removed sandbox once, with `resource="sandbox"`, instead of its containers and networks separately. A practice review whose sandbox cleanup fails now counts once, under the step `remove sandbox`, instead of once for each Docker step that failed.
