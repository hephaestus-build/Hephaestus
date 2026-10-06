---
"hephaestus": patch
---

A stopping worker now keeps its sandbox gateway open while it waits for active reviews, so a review can keep making model requests until it finishes or the drain timeout runs out.
