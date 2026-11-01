---
"hephaestus": patch
---

Conversations write less to the database's write-ahead log, so busy installs need less disk throughput, replication bandwidth and backup storage. Keeping an answer in progress alive now updates its row in place, and model usage records are stored in the order they happen.
