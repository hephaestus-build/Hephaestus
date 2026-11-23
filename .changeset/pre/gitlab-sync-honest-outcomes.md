---
"hephaestus": patch
---

A GitLab sync that you stop after its last step now finishes as succeeded instead of cancelled, because nothing was left to stop. A GitLab sync that fails now shows its real cause instead of a generic message. A GitLab sync that ends with warnings now says which step did not complete and for which project or group.
