---
"hephaestus": patch
---

A slow outbox action no longer delays the others on the same server. Before, a server started new outbox actions, such as emails, Slack posts and notices, feedback deliveries and their corrections, and GitHub issue filings, only after every action it had started earlier had finished, or after two minutes. Now each server starts a due action as soon as one of its slots is free. The log event `action.batch.outlived` is replaced by `action.attempt.outlived`, which names the action that ran past its lease.
