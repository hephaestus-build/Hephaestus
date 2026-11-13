---
---

Fixes a defect that only unreleased code had: a requeued review attempt that its sandbox pulls now starts from an empty `/workspace`, instead of failing while it extracts its workspace over the earlier attempt's files.
