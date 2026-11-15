---
"hephaestus": patch
---

A GitHub repository sync that times out before GitHub answers, for example while it fetches the installation token, now retries like other temporary GitHub errors. Before, it skipped the repository until the next sync without retrying. When the retries run out, the sync still skips the repository for this round and logs which repository failed and why.
