---
"hephaestus": patch
---

A server restart while a review runs no longer ends the review as failed. A review that is running, and an Ask Hephaestus reply, now wait for the server to come back until their work ends, and then continue. If the server stays away longer, the review tries again in a new attempt and makes no claim about the work.
