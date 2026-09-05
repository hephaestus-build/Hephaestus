---
"hephaestus": patch
---

A practice review interrupted mid-run can start again. Restarting the application while reviews were
running left each one's isolated network behind, and because a review's network is named after the
review, every later attempt was refused for a name already in use. Those reviews spent every attempt
they had on the same refusal and ended as failed without producing feedback. A review now clears the
network its interrupted run left behind.
