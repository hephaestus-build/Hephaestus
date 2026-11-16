---
"hephaestus": patch
---

A provider or database outage no longer fills the log with errors. Each retry of a failed provider event logs a warning with its delivery count, and a scheduled job that fails on a transient outage logs a warning, because its next run retries it. An error marks only the final outcome: a provider event that becomes a dead letter, or a failure that a retry cannot fix.
