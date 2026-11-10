---
"hephaestus": patch
---

A scan review no longer spends past its model budget when two workers serve its model calls: its calls now run one at a time across all workers. If a worker stops during a call, the next call waits up to one minute.
