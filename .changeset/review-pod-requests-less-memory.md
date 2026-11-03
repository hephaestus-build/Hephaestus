---
"hephaestus": patch
---

A review sandbox now requests 384 MiB of memory instead of 640 MiB, so one node holds more reviews at once. Its limit stays 1,280 MiB, because a review of a large change can use more than it requests.
