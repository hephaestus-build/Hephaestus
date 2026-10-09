---
"hephaestus": patch
---

Build validation prepares GitHub actions sequentially so they do not share concurrent writes to event data. Independent jobs and test shards still run concurrently.
