---
"hephaestus": patch
---

Listing a workspace's agent jobs no longer reads every review transcript into the server's memory to
throw it away. A page of a hundred jobs cost tens of megabytes of heap per request and grew with how
much each review had to say; it now reads only what the listing shows. The delivery-recovery sweep
reads a job whole only for the delivery it actually re-attempts.
