---
"hephaestus": patch
---

The upgrade adds three empty tables for the personal mentor's intake. Each row belongs to one account, and PostgreSQL row-level security limits it to that account. Nothing writes them yet, so nothing changes for operators or people. A workspace purge and a Slack disconnect already erase them. Person export and erasure include them from the next release on, before anything writes them.
