---
"hephaestus": patch
---

A GitHub repository with more changed pull requests or issues than one sync run reads, for example after a long outage, now catches up over the next runs. Before, every run read the same newest changes again, stopped at its limit, and never reached the rest.
