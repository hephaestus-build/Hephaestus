---
"hephaestus": patch
---

Product feedback drafts no longer wait behind every practice review. Before, a draft got model capacity only when no other call waited, so a busy instance could leave a report in **Drafting** for many minutes. Now a draft waits its turn in the model's call pool and moves up while it waits, like other background calls, so practice reviews that keep arriving cannot hold it back.
